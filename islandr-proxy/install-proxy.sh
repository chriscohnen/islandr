#!/usr/bin/env bash
#
# install-proxy.sh — self-contained remote installer for the islandr socket proxy.
#
#   curl -fsSL https://github.com/chriscohnen/islandr/releases/latest/download/install-proxy.sh | sudo bash
#
# Downloads the islandr-proxy binary for this architecture from the GitHub
# release, VERIFIES its sha256, and installs it with the socket-activated systemd
# units + scoped sudoers (ADR-0012). Self-contained: the systemd units are
# embedded below, so no repo checkout is needed. Idempotent; requires root.
#
# Pin a version:  ISLANDR_PROXY_VERSION=v0.11.0 curl ... | sudo bash
# From source instead:  git clone the repo and run islandr-proxy/install.sh.
#
# NOTE: this installs a *privileged* helper. If you'd rather read it first:
#   curl -fsSL <url>/install-proxy.sh -o install-proxy.sh
#   less install-proxy.sh && sudo bash install-proxy.sh
set -euo pipefail

REPO="chriscohnen/islandr"
VERSION="${ISLANDR_PROXY_VERSION:-latest}"
# Interface must match the container's islandr.wg.interface (default wg0).
IFACE="${ISLANDR_WG_INTERFACE:-wg0}"
if ! [[ "$IFACE" =~ ^[A-Za-z0-9][A-Za-z0-9.-]{0,14}$ ]]; then
  echo "error: invalid ISLANDR_WG_INTERFACE '$IFACE' (1-15 chars: letters, digits, '.' '-')" >&2
  exit 1
fi

if [[ $EUID -ne 0 ]]; then
  echo "error: run as root (pipe to 'sudo bash', or 'sudo $0')" >&2
  exit 1
fi

case "$(uname -s)" in
  Linux) ;;
  *) echo "error: the socket proxy runs on the Linux hub only" >&2; exit 1 ;;
esac

case "$(uname -m)" in
  x86_64|amd64)  ARCH=amd64 ;;
  aarch64|arm64) ARCH=arm64 ;;
  *) echo "error: unsupported architecture $(uname -m) (amd64/arm64 only)" >&2; exit 1 ;;
esac

if [[ "$VERSION" == "latest" ]]; then
  BASE="https://github.com/$REPO/releases/latest/download"
else
  BASE="https://github.com/$REPO/releases/download/$VERSION"
fi

echo "→ Checking for wg / nft"
command -v wg  >/dev/null || { echo "error: 'wg' not found — install wireguard-tools" >&2; exit 1; }
command -v nft >/dev/null || { echo "error: 'nft' not found — install nftables" >&2; exit 1; }
WG_PATH="$(command -v wg)"
NFT_PATH="$(command -v nft)"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "→ Downloading islandr-proxy ($ARCH, $VERSION) + checksum"
curl -fsSL "$BASE/islandr-proxy-linux-$ARCH"        -o "$TMP/islandr-proxy"
curl -fsSL "$BASE/islandr-proxy-linux-$ARCH.sha256" -o "$TMP/islandr-proxy.sha256"

echo "→ Verifying sha256"
EXPECTED="$(cut -d' ' -f1 "$TMP/islandr-proxy.sha256")"
( cd "$TMP" && echo "$EXPECTED  islandr-proxy" | sha256sum -c - ) \
  || { echo "error: checksum mismatch — refusing to install" >&2; exit 1; }

echo "→ Creating system user 'islandr' (if not already present)"
if ! id -u islandr >/dev/null 2>&1; then
  useradd --system --home-dir /var/lib/islandr --shell /usr/sbin/nologin islandr
fi

echo "→ Installing binary → /usr/local/bin/islandr-proxy (root:root 0755)"
install -o root -g root -m 0755 "$TMP/islandr-proxy" /usr/local/bin/islandr-proxy

echo "→ Writing tmpfiles / socket / service / sudoers"
# Keep these embedded units in sync with islandr-proxy/systemd/* in the repo.
cat > /etc/tmpfiles.d/islandr.conf <<'EOF'
d /run/islandr 0700 islandr islandr -
EOF

cat > /etc/systemd/system/islandr-proxy.socket <<'EOF'
[Unit]
Description=islandr-proxy socket — privileged wg/nft helper for the containerised app
Documentation=https://github.com/chriscohnen/islandr/blob/main/docs/adr/0012-docker-socket-proxy.md

[Socket]
# The container mounts exactly this path. Ownership + mode are set here by
# systemd, not by the daemon — that is how R-120 (0600, islandr:islandr) is met
# without custom code. /run/islandr is created earlier by the tmpfiles.d entry.
ListenStream=/run/islandr/proxy.sock
SocketMode=0600
SocketUser=islandr
SocketGroup=islandr

[Install]
WantedBy=sockets.target
EOF

cat > /etc/systemd/system/islandr-proxy.service <<'EOF'
[Unit]
Description=islandr-proxy — executes the wg/nft allowlist for the islandr container
Documentation=https://github.com/chriscohnen/islandr/blob/main/docs/adr/0012-docker-socket-proxy.md
Requires=islandr-proxy.socket
After=islandr-proxy.socket

[Service]
Type=simple
User=islandr
Group=islandr
ExecStart=/usr/local/bin/islandr-proxy

# The listening socket is handed in by systemd (LISTEN_FDS); the process never
# opens one itself. sudo must be able to escalate, so NoNewPrivileges stays off.
NoNewPrivileges=false

# Light hardening that does not interfere with sudo wg/nft:
ProtectHome=true
ProtectSystem=full
PrivateTmp=true
# /run/islandr already exists (tmpfiles, islandr:islandr 0700); short-lived
# preshared-key files are written there. runtimeDir() falls back to it when
# RUNTIME_DIRECTORY is unset, so no RuntimeDirectory= is declared here.

[Install]
WantedBy=multi-user.target
EOF
# Bind the interface into the service (default wg0 is the proxy's own fallback).
sed -i "/^\[Service\]/a Environment=ISLANDR_WG_INTERFACE=$IFACE" /etc/systemd/system/islandr-proxy.service

SUDO_TMP="$(mktemp)"
cat > "$SUDO_TMP" <<EOF
# Scoped sudoers per ADR-0011: the islandr user may run exactly wg/nft as root.
islandr ALL=(root) NOPASSWD: $WG_PATH, $NFT_PATH
EOF
visudo -cf "$SUDO_TMP"   # validate before install — a broken sudoers file locks out sudo
install -o root -g root -m 0440 "$SUDO_TMP" /etc/sudoers.d/islandr-proxy
rm -f "$SUDO_TMP"

echo "→ Enabling socket activation"
systemd-tmpfiles --create /etc/tmpfiles.d/islandr.conf
systemctl daemon-reload
systemctl enable --now islandr-proxy.socket

echo "✓ Done. Socket: /run/islandr/proxy.sock (0600 islandr:islandr)"
echo "  Mount into the container:  -v /run/islandr/proxy.sock:/run/islandr/proxy.sock"
echo "  Status:  systemctl status islandr-proxy.socket"
