#!/usr/bin/env bash
# update.sh — install the latest Islandr release (including RCs) on a production hub
#
# Usage:
#   sudo bash update.sh               # latest release (including RC)
#   sudo bash update.sh v0.9.0        # specific version
#   sudo bash update.sh v0.9.0-rc.5   # specific RC
#
# Requires: curl, sha256sum, systemctl
# Assumes the standard install layout from docs/install.md:
#   binary  →  /opt/islandr/islandr
#   service →  islandr.service (systemd)

set -euo pipefail

REPO="chriscohnen/islandr"
INSTALL_BIN="/opt/islandr/islandr"
SERVICE="islandr"
API_BASE="https://api.github.com/repos/${REPO}"
DL_BASE="https://github.com/${REPO}/releases/download"

# ── helpers ───────────────────────────────────────────────────────────────────
die()  { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
info() { printf '  %s\n' "$*"; }

# ── root check ────────────────────────────────────────────────────────────────
[[ $EUID -eq 0 ]] || die "run as root: sudo bash $0"

# ── detect architecture ───────────────────────────────────────────────────────
case "$(uname -m)" in
  x86_64)  ARCH=amd64 ;;
  aarch64) ARCH=arm64 ;;
  *)       die "unsupported architecture: $(uname -m)" ;;
esac

# ── resolve target version ────────────────────────────────────────────────────
if [[ ${1:-} ]]; then
  TARGET="$1"
  # normalise: strip leading v if present, then re-add — keeps both "v0.9.0" and "0.9.0" working
  TARGET="v${TARGET#v}"
else
  info "Fetching latest release from GitHub (including RC) ..."
  JSON=$(curl -fsSL \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "${API_BASE}/releases?per_page=1")

  if command -v jq &>/dev/null; then
    TARGET=$(printf '%s' "$JSON" | jq -r '.[0].tag_name')
  else
    # Fallback — no jq required
    TARGET=$(printf '%s' "$JSON" | grep -m1 '"tag_name"' \
      | sed 's/.*"tag_name": *"\([^"]*\)".*/\1/')
  fi
  [[ -n "$TARGET" && "$TARGET" != "null" ]] \
    || die "could not read tag_name from GitHub API response"
fi

# ── show plan ─────────────────────────────────────────────────────────────────
ASSET="islandr-runner-linux-${ARCH}"

printf '\nIslandr updater\n'
printf '─────────────────────────────────────────\n'
info "Target version : ${TARGET}"
info "Architecture   : ${ARCH}"
info "Binary         : ${INSTALL_BIN}"
info "Service        : ${SERVICE}"
printf '\n'

# ── download ──────────────────────────────────────────────────────────────────
DL_URL="${DL_BASE}/${TARGET}/${ASSET}"
SHA_URL="${DL_URL}.sha256"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

info "Downloading ${TARGET}/${ASSET} ..."
curl -fSL --progress-bar "$DL_URL"  -o "${TMP}/${ASSET}" \
  || die "download failed — does ${TARGET} exist? Check: https://github.com/${REPO}/releases"
curl -fsSL "$SHA_URL" -o "${TMP}/${ASSET}.sha256" \
  || die "checksum file not found at ${SHA_URL}"

# ── verify checksum ───────────────────────────────────────────────────────────
info "Verifying checksum ..."
EXPECTED=$(awk '{print $1}' "${TMP}/${ASSET}.sha256")
ACTUAL=$(sha256sum "${TMP}/${ASSET}" | awk '{print $1}')
[[ "$EXPECTED" == "$ACTUAL" ]] \
  || die "checksum mismatch\n  expected: ${EXPECTED}\n  actual:   ${ACTUAL}"
info "Checksum OK (${ACTUAL:0:16}...)"

# ── swap binary ───────────────────────────────────────────────────────────────
WAS_ACTIVE=false
if systemctl is-active --quiet "$SERVICE" 2>/dev/null; then
  WAS_ACTIVE=true
  info "Stopping ${SERVICE} ..."
  systemctl stop "$SERVICE"
fi

install -o islandr -g islandr -m 0755 "${TMP}/${ASSET}" "$INSTALL_BIN"
info "Binary installed."

# ── restart ───────────────────────────────────────────────────────────────────
if $WAS_ACTIVE; then
  info "Starting ${SERVICE} ..."
  systemctl start "$SERVICE"
  sleep 2
  if systemctl is-active --quiet "$SERVICE"; then
    info "Service is running."
  else
    printf '\nWARNING: service did not start — check logs:\n'
    printf '  journalctl -u %s -n 40\n' "$SERVICE"
    exit 1
  fi
else
  info "Service was not running — not started. Start manually: systemctl start ${SERVICE}"
fi

printf '\nDone. Islandr %s installed.\n' "$TARGET"
