#!/usr/bin/env bash
#
# Islandr — Hub setup (Ubuntu 24.04, x86_64).
#
# What it does:
#   - Creates the `islandr` service user
#   - Creates /opt/islandr (binary) and /var/lib/islandr/data (SQLite DB)
#   - Writes the sudoers entry for nft + wg (Option B from docs/install.md)
#   - Writes /etc/default/islandr with env variables
#   - Installs and enables the systemd unit
#
# Prerequisites:
#   - Native x86_64 binary is at /tmp/islandr and chmod +x'd
#   - apt packages `wireguard` and `nftables` are installed (already the case
#     on an existing wg0 VPS)
#   - SSH user has sudo rights
#
# Idempotency: this script is NOT idempotent. It assumes it runs on a fresh
# VPS. Clean up manually before re-running.
#
# Usage:
#   chmod +x setup-hub.sh
#   sudo ./setup-hub.sh
#   # Different interface name (default: wg0):
#   sudo WG_INTERFACE=wg1 ./setup-hub.sh

set -euo pipefail

# WireGuard interface name. Must match the interface configured outside this
# script (see prerequisites above) — the name here is only used to set the
# sudoers rules and ISLANDR_WG_INTERFACE.
WG_INTERFACE="${WG_INTERFACE:-wg0}"

# ---------------------------------------------------------------------------
# 1. User + directories
# ---------------------------------------------------------------------------
echo ">>> 1/5 User and directories"

if id islandr &>/dev/null; then
    echo "  User 'islandr' already exists, skipping."
else
    useradd -r -s /usr/sbin/nologin -d /var/lib/islandr -m islandr
fi

install -d -o islandr -g islandr /opt/islandr
install -d -o islandr -g islandr /var/lib/islandr/data

if [[ ! -f /tmp/islandr ]]; then
    echo "ERROR: /tmp/islandr is missing. Upload it first via scp:"
    echo "  scp build/islandr-0.1.0-SNAPSHOT-runner USER@HOST:/tmp/islandr"
    exit 1
fi
install -o islandr -g islandr -m 0755 /tmp/islandr /opt/islandr/islandr
rm /tmp/islandr

# ---------------------------------------------------------------------------
# 2. sudoers entry (Option B: sudo instead of CAP_NET_ADMIN)
# ---------------------------------------------------------------------------
echo ">>> 2/5 sudoers entry (interface: $WG_INTERFACE)"

cat > /etc/sudoers.d/islandr <<SUDOERS
# Islandr service user: scoped sudo for nft and wg only (ADR-0011).
# Wildcard on the nft path — RealNftablesAdapter writes a fresh randomly-named
# temp file per apply (islandr-nft-<random>.nft), not a fixed name.
islandr ALL=(root) NOPASSWD: /usr/sbin/nft -c -f /var/lib/islandr/islandr-nft-*.nft
islandr ALL=(root) NOPASSWD: /usr/sbin/nft -f /var/lib/islandr/islandr-nft-*.nft
islandr ALL=(root) NOPASSWD: /usr/sbin/nft delete table inet islandr
islandr ALL=(root) NOPASSWD: /usr/bin/wg set $WG_INTERFACE *
islandr ALL=(root) NOPASSWD: /usr/bin/wg syncconf $WG_INTERFACE *
islandr ALL=(root) NOPASSWD: /usr/bin/wg show $WG_INTERFACE
islandr ALL=(root) NOPASSWD: /usr/bin/wg show $WG_INTERFACE dump
SUDOERS
chmod 0440 /etc/sudoers.d/islandr

if ! visudo -c -f /etc/sudoers.d/islandr >/dev/null; then
    echo "ERROR: sudoers syntax broken — removing the file."
    rm /etc/sudoers.d/islandr
    exit 1
fi

# NOTE: the 30s activity poller's "wg show" calls are noisy in the journal
# (sudo's own log line + PAM session open/close per tick). Do NOT try to
# silence that with a scoped `Defaults!cmnd_alias !pam_session` rule — the
# systemd unit below runs with ProtectSystem=strict, which makes /run
# read-only for the service and everything it spawns (including sudo). Sudo
# tolerates the resulting "/run/sudo/ts: Read-only file system" as long as it
# can still complete a PAM session; disabling pam_session removes that
# tolerance and sudo falls back to demanding interactive auth instead —
# breaking every sudo call, not just the noisy one. Confirmed the hard way
# in production 2026-07-21.

# ---------------------------------------------------------------------------
# 3. Env file with configuration variables
# ---------------------------------------------------------------------------
echo ">>> 3/5 /etc/default/islandr"

# Generate a strong admin password (can be overridden manually before start).
ADMIN_PW="$(openssl rand -base64 24)"

# Encryption key for "encrypted" private-key retention (ADR-0007). Without
# this key, only "never"/"plaintext" are selectable in the Admin Console.
# Always generated here so "encrypted" is available from the first start —
# can be upgraded to a TPM2-bound systemd-creds key at any time later
# (see docs/install.md, section 9).
ENCRYPTION_KEY="$(openssl rand -base64 32)"

cat > /etc/default/islandr <<ENV
# Local admin (recovery user) — empty variable = login disabled, /api/v1/auth/login -> 503.
ISLANDR_ADMIN_USER=admin
ISLANDR_ADMIN_PASSWORD=$ADMIN_PW

# Private key retention (ADR-0007)
ISLANDR_ENCRYPTION_KEY=$ENCRYPTION_KEY

# WireGuard / nftables
ISLANDR_WG_INTERFACE=$WG_INTERFACE
ISLANDR_WG_MODE=real
ISLANDR_NFT_MODE=real
ISLANDR_USE_SUDO=true
# Device discovery (ADR-0014) scans for real by default — no setting needed.
# Set ISLANDR_DISCOVERY_MODE=mock to get two fixed synthetic hosts instead.

# Database
QUARKUS_DATASOURCE_JDBC_URL=jdbc:sqlite:/var/lib/islandr/data/islandr.db

# HTTP + HTTPS — binds to all interfaces; islandr terminates TLS itself
# (ADR-0015) and can auto-provision a Let's Encrypt certificate (ADR-0019).
# Port 80 must stay reachable on the public interface for the ACME HTTP-01
# challenge (RFC 8555 always validates on port 80 — not configurable, on
# either side).
#
# Running a reverse proxy on this host instead of islandr's own TLS/ACME?
# Bind islandr to loopback on different ports and point the proxy at those:
#   QUARKUS_HTTP_HOST=127.0.0.1
#   QUARKUS_HTTP_PORT=8080
#   QUARKUS_HTTP_SSL_PORT=8443
# See ../reverse-proxy.md for both paths side by side.
QUARKUS_HTTP_HOST=0.0.0.0
QUARKUS_HTTP_PORT=80
QUARKUS_HTTP_SSL_PORT=443

QUARKUS_LOG_LEVEL=INFO
ENV
chown root:islandr /etc/default/islandr
chmod 0640 /etc/default/islandr

echo ""
echo "  ===================================================================="
echo "  ADMIN PASSWORD (note it down now! otherwise it's only in /etc/default/islandr):"
echo "  $ADMIN_PW"
echo "  ===================================================================="
echo ""

# ---------------------------------------------------------------------------
# 4. systemd unit
# ---------------------------------------------------------------------------
echo ">>> 4/5 systemd unit"

cat > /etc/systemd/system/islandr.service <<'UNIT'
[Unit]
Description=Islandr — WireGuard access management
After=network-online.target
Wants=network-online.target

[Service]
User=islandr
Group=islandr
WorkingDirectory=/var/lib/islandr
EnvironmentFile=/etc/default/islandr
ExecStart=/opt/islandr/islandr

NoNewPrivileges=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/islandr
# Temp files for nft -c -f / nft -f live under /tmp.
PrivateTmp=false

# Binding ports 80/443 (built-in TLS) and 53 (optional resource DNS resolver,
# ADR-0023) as the unprivileged islandr user needs this one narrow capability
# — same scoped-privilege principle as the sudoers rules above (islandr never
# runs as root, never gets CAP_NET_ADMIN or a setuid binary).
AmbientCapabilities=CAP_NET_BIND_SERVICE
CapabilityBoundingSet=CAP_NET_BIND_SERVICE

Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload

# ---------------------------------------------------------------------------
# 5. Start
# ---------------------------------------------------------------------------
echo ">>> 5/5 Starting service"

systemctl enable islandr
systemctl start islandr

# Give it a moment to boot
sleep 4

systemctl --no-pager status islandr || true

echo ""
echo "Done. Follow logs with:"
echo "  sudo journalctl -u islandr -f"
echo ""
echo "Listening on: http://<host>:80 and https://<host>:443"
echo "  Port 80 must stay open in your firewall/security group for Let's"
echo "  Encrypt's HTTP-01 challenge, even once a real certificate is issued."
echo ""
echo "Running a reverse proxy instead? Set QUARKUS_HTTP_HOST=127.0.0.1 plus"
echo "  loopback ports (e.g. QUARKUS_HTTP_PORT=8080, QUARKUS_HTTP_SSL_PORT=8443)"
echo "  in /etc/default/islandr, then: sudo systemctl restart islandr"
echo "  See docs/install/reverse-proxy.md for both paths side by side."
echo ""
echo "Smoke test from your Mac:"
echo "  ssh -L 8443:127.0.0.1:443 USER@HOST"
echo "  # then on the Mac: https://localhost:8443 (dummy cert until you configure one)"
