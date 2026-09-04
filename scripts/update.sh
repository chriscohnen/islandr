#!/usr/bin/env bash
# update.sh — install the latest Islandr release (including RCs) on a production hub
#
# Usage:
#   sudo bash update.sh               # latest stable release
#   sudo bash update.sh --pre         # latest release including release candidates
#   sudo bash update.sh v0.9.0        # specific version
#   sudo bash update.sh v0.9.0-rc.5   # specific RC
#   sudo bash update.sh --rollback    # undo the last update from its backups
#
# "Stable" means what GitHub's /releases/latest returns: the newest release
# that is neither a draft nor a prerelease — the same one Islandr's own version
# check in the Admin Console compares against. Release candidates are for
# testing a hub before the real thing and are never picked up by default.
#
# Requires: curl, sha256sum, systemctl (sqlite3 for the database backup)
# Assumes the standard install layout from docs/install.md and setup-hub.sh:
#   binary  →  /opt/islandr/islandr
#   service →  islandr.service (systemd)
#   data    →  /var/lib/islandr/data/islandr.db
#
# Before swapping the binary this takes two backups: the current binary
# (islandr.prev) and a hot copy of the database (islandr.db.prev). If the new
# version does not come up, both are restored and the old version is started
# again, so a failed update ends where it began rather than on a hub with no
# working binary.
#
# The database backup is not optional paranoia: Flyway migrates at startup, so
# a version that migrates and *then* fails leaves a schema the previous binary
# refuses to validate. Restoring the binary alone would not start either.

set -euo pipefail

REPO="chriscohnen/islandr"
INSTALL_BIN="/opt/islandr/islandr"
PREV_BIN="/opt/islandr/islandr.prev"
DB_PATH="${DB_PATH:-/var/lib/islandr/data/islandr.db}"
PREV_DB="${DB_PATH}.prev"
SERVICE="islandr"
# Seconds to watch the new version before calling the update good. Must exceed
# the unit's RestartSec (10) so a process that starts, dies, and gets restarted
# is not mistaken for a healthy one.
SETTLE_SECONDS="${SETTLE_SECONDS:-15}"
API_BASE="https://api.github.com/repos/${REPO}"
DL_BASE="https://github.com/${REPO}/releases/download"

# ── helpers ───────────────────────────────────────────────────────────────────
die()  { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
info() { printf '  %s\n' "$*"; }

# "Was it supposed to be running?" — is-active alone answers no for a service
# stuck in activating (auto-restart), i.e. exactly the crash loop an update is
# most often meant to fix. Such a service must be started again afterwards.
service_should_run() {
  local state
  state=$(systemctl is-active "$SERVICE" 2>/dev/null || true)
  [[ "$state" == "active" || "$state" == "activating" || "$state" == "failed" ]]
}

restart_count() {
  systemctl show -p NRestarts --value "$SERVICE" 2>/dev/null || echo 0
}

# Start, then watch. A unit can report "active" the instant it forks and die a
# second later; NRestarts moving proves that happened.
start_and_settle() {
  systemctl start "$SERVICE" || true
  local before after i
  before=$(restart_count)
  for ((i = 0; i < SETTLE_SECONDS; i++)); do
    sleep 1
    after=$(restart_count)
    if [[ "$after" != "$before" ]]; then return 1; fi
    systemctl is-active --quiet "$SERVICE" || return 1
  done
  return 0
}

# Everything a SIGKILL hides. The journal carries no stack trace for one, so
# the kernel log is the only place the reason exists.
diagnose() {
  printf '\nLast log lines:\n'
  journalctl -u "$SERVICE" -b --no-pager -n 40 | sed 's/^/  /' || true
  if journalctl -u "$SERVICE" -b --no-pager | grep -q "status=9/KILL"; then
    printf '\nThe process was SIGKILLed — Islandr never does that to itself.\n'
    if { dmesg 2>/dev/null; journalctl -k -b --no-pager 2>/dev/null; } \
         | grep -iE "oom-kill|out of memory|killed process" | grep -qi islandr; then
      printf 'The kernel OOM killer took it. Free memory on this host:\n'
      free -m | sed 's/^/  /'
    else
      printf 'No OOM entry found, but the kernel log may be restricted. Check:\n'
      printf '  sudo dmesg -T | grep -iE "oom|killed process"\n'
      printf '  sudo journalctl -k -b | grep -iE "oom|killed process"\n'
    fi
  fi
}

restore_backups() {
  if [[ -f "$PREV_BIN" ]]; then
    install -o islandr -g islandr -m 0755 "$PREV_BIN" "$INSTALL_BIN"
  fi
  if [[ -f "$PREV_DB" ]]; then
    install -o islandr -g islandr -m 0600 "$PREV_DB" "$DB_PATH"
    # A newer schema left behind by the failed version's migration would make
    # the restored binary refuse to start, so the database goes back too.
    rm -f "${DB_PATH}-wal" "${DB_PATH}-shm"
  fi
}

# ── root check ────────────────────────────────────────────────────────────────
[[ $EUID -eq 0 ]] || die "run as root: sudo bash $0"

# ── explicit rollback ─────────────────────────────────────────────────────────
if [[ "${1:-}" == "--rollback" ]]; then
  [[ -f "$PREV_BIN" ]] || die "no backup at ${PREV_BIN} — nothing to roll back to"
  info "Stopping ${SERVICE} ..."
  systemctl stop "$SERVICE" 2>/dev/null || true
  restore_backups
  info "Previous binary and database restored."
  if start_and_settle; then
    printf '\nRolled back. %s is running the previous version.\n' "$SERVICE"
    exit 0
  fi
  diagnose
  die "rolled back, but ${SERVICE} still does not start — this was already broken before the update"
fi

# ── detect architecture ───────────────────────────────────────────────────────
case "$(uname -m)" in
  x86_64)  ARCH=amd64 ;;
  aarch64) ARCH=arm64 ;;
  *)       die "unsupported architecture: $(uname -m)" ;;
esac

# ── resolve target version ────────────────────────────────────────────────────
# Pull tag_name out of an API response, with or without jq. The jq filter
# differs because /releases/latest returns one object and /releases an array.
tag_from() {
  local json="$1" filter="$2"
  if command -v jq &>/dev/null; then
    printf '%s' "$json" | jq -r "$filter"
  else
    printf '%s' "$json" | grep -m1 '"tag_name"' \
      | sed 's/.*"tag_name": *"\([^"]*\)".*/\1/'
  fi
}

github_api() {
  curl -fsSL \
    -H "Accept: application/vnd.github+json" \
    -H "X-GitHub-Api-Version: 2022-11-28" \
    "$1"
}

case "${1:-}" in
  --pre|--prerelease|--rc)
    info "Fetching the newest release, prereleases included ..."
    # /releases is newest-first and includes prereleases; /releases/latest never does.
    TARGET=$(tag_from "$(github_api "${API_BASE}/releases?per_page=1")" '.[0].tag_name')
    ;;
  "")
    info "Fetching the latest stable release ..."
    # /releases/latest is GitHub's own "newest non-draft, non-prerelease". It
    # 404s when only prereleases exist — a meaningful answer, not a transport
    # error, so it is reported as such rather than as a failed download.
    if JSON=$(github_api "${API_BASE}/releases/latest" 2>/dev/null); then
      TARGET=$(tag_from "$JSON" '.tag_name')
    else
      TARGET=""
    fi
    if [[ -z "$TARGET" || "$TARGET" == "null" ]]; then
      die "no stable release found (only prereleases so far?).
  Install a release candidate explicitly:  sudo bash $0 --pre
  or name a version:                       sudo bash $0 v0.19.0"
    fi
    ;;
  *)
    # normalise: strip leading v if present, then re-add — keeps both "v0.9.0" and "0.9.0" working
    TARGET="v${1#v}"
    ;;
esac

[[ -n "$TARGET" && "$TARGET" != "null" ]] \
  || die "could not read tag_name from the GitHub API response"

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

# ── stop ──────────────────────────────────────────────────────────────────────
SHOULD_RUN=false
if service_should_run; then
  SHOULD_RUN=true
  info "Stopping ${SERVICE} ..."
  systemctl stop "$SERVICE" 2>/dev/null || true
fi

# ── back up what a failed update would otherwise destroy ──────────────────────
if [[ -f "$INSTALL_BIN" ]]; then
  install -o islandr -g islandr -m 0755 "$INSTALL_BIN" "$PREV_BIN"
  info "Previous binary saved to ${PREV_BIN}"
else
  info "No binary at ${INSTALL_BIN} yet — nothing to back up."
fi

if [[ -f "$DB_PATH" ]]; then
  if command -v sqlite3 &>/dev/null; then
    # .backup, not cp — SQLite's own hot-backup API, so the service having
    # written up to a moment ago cannot leave a torn copy behind.
    sqlite3 "$DB_PATH" ".backup '${PREV_DB}'" \
      || die "database backup failed — refusing to swap the binary"
    chown islandr:islandr "$PREV_DB"; chmod 0600 "$PREV_DB"
    info "Database backed up to ${PREV_DB}"
  else
    printf '\nWARNING: sqlite3 not found — no database backup taken.\n'
    printf '  A failed update that has already migrated the schema cannot then be\n'
    printf '  rolled back: the old binary refuses a newer schema. Install sqlite3\n'
    printf '  (apt-get install -y sqlite3) and re-run to get that safety net.\n\n'
  fi
fi

# ── swap binary ───────────────────────────────────────────────────────────────
install -o islandr -g islandr -m 0755 "${TMP}/${ASSET}" "$INSTALL_BIN"
info "Binary installed."

# ── start and watch ───────────────────────────────────────────────────────────
if ! $SHOULD_RUN; then
  printf '\nDone. Islandr %s installed. The service was not running, so it was not\n' "$TARGET"
  printf 'started: systemctl start %s\n' "$SERVICE"
  exit 0
fi

info "Starting ${SERVICE} and watching it for ${SETTLE_SECONDS}s ..."
if start_and_settle; then
  printf '\nDone. Islandr %s is running.\n' "$TARGET"
  printf 'Previous version kept at %s — undo with: sudo bash %s --rollback\n' "$PREV_BIN" "$0"
  exit 0
fi

printf '\n%s did not stay up on %s.\n' "$SERVICE" "$TARGET"
diagnose

printf '\nRolling back to the previous version ...\n'
systemctl stop "$SERVICE" 2>/dev/null || true
restore_backups
if start_and_settle; then
  printf '\nRolled back. The previous version is running again; %s was NOT applied.\n' "$TARGET"
  printf 'The failed binary is gone; re-download it to investigate.\n'
  exit 1
fi
diagnose
die "${TARGET} failed AND the rollback did not come up either — the hub is down.
  Binary backup:   ${PREV_BIN}
  Database backup: ${PREV_DB}
  Both have been restored already; the problem is not the Islandr version."
