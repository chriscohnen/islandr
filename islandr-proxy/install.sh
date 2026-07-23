#!/usr/bin/env bash
#
# install.sh — one-time host bootstrap for the islandr socket proxy (ADR-0012).
#
# Adds the privileged wg/nft helper alongside a containerised islandr app: the
# `islandr` system user, scoped sudoers, the runtime dir, and the socket-activated
# systemd units. After this runs, mount /run/islandr/proxy.sock into the container
# and islandr enforces real rules instead of running in the degraded state.
#
# Idempotent: safe to re-run. Requires root. Does NOT create the wg0 interface —
# see docs/install.md for the WireGuard hub setup.
set -euo pipefail

BIN_SRC="${1:-$(dirname "$0")/islandr-proxy}"   # path to the built binary
HERE="$(cd "$(dirname "$0")" && pwd)"
UNIT_DIR="$HERE/systemd"

if [[ $EUID -ne 0 ]]; then
  echo "error: run as root (sudo $0)" >&2
  exit 1
fi

# Interface must match the container's islandr.wg.interface (default wg0).
IFACE="${ISLANDR_WG_INTERFACE:-wg0}"
if ! [[ "$IFACE" =~ ^[A-Za-z0-9][A-Za-z0-9.-]{0,14}$ ]]; then
  echo "error: invalid ISLANDR_WG_INTERFACE '$IFACE' (1-15 chars: letters, digits, '.' '-')" >&2
  exit 1
fi

if [[ ! -x "$BIN_SRC" ]]; then
  echo "error: proxy binary not found/executable at: $BIN_SRC" >&2
  echo "       pass the path as the first argument, or build it first:" >&2
  echo "       CGO_ENABLED=0 go build -trimpath -o islandr-proxy ." >&2
  exit 1
fi

echo "→ Checking for wg / nft"
command -v wg  >/dev/null || { echo "error: 'wg' not found — install wireguard-tools" >&2; exit 1; }
command -v nft >/dev/null || { echo "error: 'nft' not found — install nftables" >&2; exit 1; }
WG_PATH="$(command -v wg)"
NFT_PATH="$(command -v nft)"

echo "→ Creating system user 'islandr' (if not already present)"
if ! id -u islandr >/dev/null 2>&1; then
  useradd --system --home-dir /var/lib/islandr --shell /usr/sbin/nologin islandr
fi

echo "→ Installing binary → /usr/local/bin/islandr-proxy (root:root 0755)"
install -o root -g root -m 0755 "$BIN_SRC" /usr/local/bin/islandr-proxy

echo "→ Installing tmpfiles / socket / service"
install -o root -g root -m 0644 "$UNIT_DIR/islandr.tmpfiles.conf" /etc/tmpfiles.d/islandr.conf
install -o root -g root -m 0644 "$UNIT_DIR/islandr-proxy.socket"   /etc/systemd/system/islandr-proxy.socket
install -o root -g root -m 0644 "$UNIT_DIR/islandr-proxy.service"  /etc/systemd/system/islandr-proxy.service
# Bind the interface into the service (default wg0 is the proxy's own fallback).
sed -i "/^\[Service\]/a Environment=ISLANDR_WG_INTERFACE=$IFACE" /etc/systemd/system/islandr-proxy.service

echo "→ Installing scoped sudoers (detected paths: $WG_PATH, $NFT_PATH)"
tmp_sudo="$(mktemp)"
sed "s#/usr/bin/wg#$WG_PATH#; s#/usr/sbin/nft#$NFT_PATH#" "$UNIT_DIR/islandr-proxy.sudoers" > "$tmp_sudo"
visudo -cf "$tmp_sudo"    # validate before installing — a broken sudoers file locks out sudo
install -o root -g root -m 0440 "$tmp_sudo" /etc/sudoers.d/islandr-proxy
rm -f "$tmp_sudo"

echo "→ Enabling socket activation"
systemd-tmpfiles --create /etc/tmpfiles.d/islandr.conf
systemctl daemon-reload
systemctl enable --now islandr-proxy.socket

echo "✓ Done. Socket: /run/islandr/proxy.sock (0600 islandr:islandr)"
echo "  Mount into the container:  -v /run/islandr/proxy.sock:/run/islandr/proxy.sock"
echo "  Status:  systemctl status islandr-proxy.socket"
