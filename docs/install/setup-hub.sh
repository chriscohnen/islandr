#!/usr/bin/env bash
#
# Islandr hub setup — Debian/Ubuntu, amd64 or arm64, fresh VPS.
#
#   sudo ./setup-hub.sh
#
# Checks prerequisites, downloads and verifies the release binary, creates the
# islandr user, sudoers entry, /etc/default/islandr and the systemd unit, then
# starts the service and prints what it installed.
#
# It does NOT touch your firewall: islandr starts in dry-run mode and applies
# nothing to WireGuard or nftables until you switch that off in the Admin
# Console. Installing over an active SSH-over-WireGuard session is safe.
#
# Needs: `wireguard` and `nftables` installed, a configured WireGuard
# interface (islandr manages peers, not the interface), ports 80+443 free,
# and access to github.com. The script stops with a fixable message if one of
# these is missing. NOT idempotent — clean up manually before re-running.
#
# Options (all optional):
#   WG_INTERFACE=wg1              interface name                (default wg0)
#   ISLANDR_VERSION=v0.20.0       pin a release                 (default latest)
#   ISLANDR_BINARY=/tmp/islandr   use a local build, no download
#   ISLANDR_HTTP_HOST=127.0.0.1   bind loopback for a reverse proxy
#   ISLANDR_HTTP_PORT=8080        + ISLANDR_HTTPS_PORT=8443
#   ISLANDR_SKIP_MEM_CHECK=1      install despite too little RAM
#
# Full walkthrough:      docs/install.md
# Why the unit and sudoers look the way they do: docs/install/hardening.md
# Reverse proxy setups:  docs/install/reverse-proxy.md

set -euo pipefail

WG_INTERFACE="${WG_INTERFACE:-wg0}"
ISLANDR_VERSION="${ISLANDR_VERSION:-latest}"
ISLANDR_BINARY="${ISLANDR_BINARY:-}"
ISLANDR_HTTP_HOST="${ISLANDR_HTTP_HOST:-0.0.0.0}"
ISLANDR_HTTP_PORT="${ISLANDR_HTTP_PORT:-80}"
ISLANDR_HTTPS_PORT="${ISLANDR_HTTPS_PORT:-443}"

REPO="chriscohnen/islandr"

fail() {
    echo ""
    echo "ERROR: $1"
    shift
    for line in "$@"; do echo "  $line"; done
    exit 1
}

# ---------------------------------------------------------------------------
# 1. Preflight — everything that makes the service fail *after* install
# ---------------------------------------------------------------------------
echo ">>> 1/7 Preflight"

[[ $EUID -eq 0 ]] || fail "This script must run as root." "sudo ./setup-hub.sh"

case "$(uname -m)" in
    x86_64)  ARCH=amd64 ;;
    aarch64) ARCH=arm64 ;;
    *) fail "Unsupported architecture: $(uname -m)." \
            "Release binaries exist for x86_64 (amd64) and aarch64 (arm64) only." ;;
esac
echo "  Architecture: $ARCH"

# Resolve real paths — sudoers must name the binary sudo actually executes.
WG_BIN="$(command -v wg || true)"
[[ -n "$WG_BIN" ]] || fail "WireGuard tools are not installed ('wg' not found)." \
    "sudo apt-get install -y wireguard"

NFT_BIN="$(command -v nft || true)"
[[ -n "$NFT_BIN" ]] || fail "nftables is not installed ('nft' not found)." \
    "sudo apt-get install -y nftables"
echo "  wg:  $WG_BIN"
echo "  nft: $NFT_BIN"

# islandr configures peers on an existing interface; without one it dies on
# the first wg call, after a seemingly successful install.
if "$WG_BIN" show "$WG_INTERFACE" >/dev/null 2>&1; then
    WG_PEERS="$("$WG_BIN" show "$WG_INTERFACE" peers | grep -c . || true)"
    echo "  Interface $WG_INTERFACE is up ($WG_PEERS peer(s) currently configured)."
else
    OTHER="$("$WG_BIN" show interfaces 2>/dev/null || true)"
    if [[ -n "$OTHER" ]]; then
        fail "WireGuard interface '$WG_INTERFACE' is not up, but these are:" \
             "  $OTHER" \
             "" \
             "Re-run with the right name:" \
             "  sudo WG_INTERFACE=<name> ./setup-hub.sh"
    fi
    if [[ -f "/etc/wireguard/$WG_INTERFACE.conf" ]]; then
        fail "/etc/wireguard/$WG_INTERFACE.conf exists but the interface is down." \
             "sudo systemctl enable --now wg-quick@$WG_INTERFACE"
    fi
    fail "No WireGuard interface is configured." \
         "Islandr manages peers on an existing interface, it does not create one." \
         "Create /etc/wireguard/$WG_INTERFACE.conf with a keypair and an [Interface]" \
         "section (Address, ListenPort, PrivateKey), then:" \
         "  sudo systemctl enable --now wg-quick@$WG_INTERFACE" \
         "See docs/install.md for a minimal example."
fi

# 256 MB is the documented floor (arc42 ch. 7), and below roughly 192 MB the
# native image is reliably SIGKILLed by the OOM killer mid-startup — a crash
# with no stack trace and nothing in the service journal. Not worth installing
# into, so this is a hard stop rather than a warning.
AVAIL_MB="$(awk '/MemAvailable/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)"
SWAP_MB="$(awk '/SwapTotal/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)"
if [[ -z "${ISLANDR_SKIP_MEM_CHECK:-}" && "$AVAIL_MB" -gt 0 && $(( AVAIL_MB + SWAP_MB )) -lt 192 ]]; then
    fail "Not enough memory: ${AVAIL_MB} MB available, ${SWAP_MB} MB swap." \
         "Islandr needs ~256 MB. The kernel would kill it during startup with" \
         "SIGKILL and no error message." \
         "" \
         "Add a swap file:" \
         "  sudo fallocate -l 1G /swapfile && sudo chmod 600 /swapfile" \
         "  sudo mkswap /swapfile && sudo swapon /swapfile" \
         "  echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab" \
         "" \
         "or move to a larger instance. Override with ISLANDR_SKIP_MEM_CHECK=1" \
         "if you know this host frees up memory before islandr starts."
fi
echo "  Memory: ${AVAIL_MB} MB available, ${SWAP_MB} MB swap."
if [[ $(( AVAIL_MB + SWAP_MB )) -lt 256 ]]; then
    echo "  Tight — 256 MB is the recommended floor."
fi

# islandr terminates TLS itself, so a host already running nginx/caddy would
# install fine and then die on "Address already in use".
if command -v ss >/dev/null 2>&1; then
    BUSY=""
    for port in "$ISLANDR_HTTP_PORT" "$ISLANDR_HTTPS_PORT"; do
        HOLDER="$(ss -ltnpH "( sport = :$port )" 2>/dev/null | head -n1 || true)"
        if [[ -n "$HOLDER" ]]; then
            NAME="$(sed -n 's/.*users:(("\([^"]*\)".*/\1/p' <<<"$HOLDER")"
            BUSY+="    port $port — ${NAME:-unknown process}"$'\n'
        fi
    done
    if [[ -n "$BUSY" ]]; then
        fail "Ports are already in use:" \
             "" \
             "${BUSY%$'\n'}" \
             "Either stop that service, or install islandr behind it on loopback" \
             "ports and let the proxy keep 80/443:" \
             "" \
             "  sudo ISLANDR_HTTP_HOST=127.0.0.1 ISLANDR_HTTP_PORT=8080 \\" \
             "       ISLANDR_HTTPS_PORT=8443 ./setup-hub.sh" \
             "" \
             "See docs/install/reverse-proxy.md for both paths side by side."
    fi
    echo "  Ports $ISLANDR_HTTP_PORT and $ISLANDR_HTTPS_PORT are free."
else
    echo "  NOTE: 'ss' not found, skipping the port check. If ports"
    echo "  $ISLANDR_HTTP_PORT/$ISLANDR_HTTPS_PORT are taken, the service will fail to start."
fi

# A host firewall in front of islandr's own ports is the most common reason a
# running service looks dead from the outside. ufw never filters loopback, so a
# local curl succeeds and hides it.
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | grep -q "^Status: active"; then
    UFW_RULES="$(ufw status 2>/dev/null || true)"
    UFW_MISSING=""
    for port in "$ISLANDR_HTTP_PORT" "$ISLANDR_HTTPS_PORT"; do
        grep -qE "^${port}[[:space:]/]" <<<"$UFW_RULES" || UFW_MISSING+="$port "
    done
    if [[ -n "$UFW_MISSING" ]]; then
        echo "  NOTE: ufw is active and has no rule for port(s): ${UFW_MISSING% }"
        echo "  A local curl will still work — ufw does not filter loopback — while"
        echo "  browsers, tunnel clients included, get nothing. Allow them on the"
        echo "  interface you administer over:"
        for port in $UFW_MISSING; do
            echo "    sudo ufw allow in on $WG_INTERFACE to any port $port proto tcp"
        done
    else
        echo "  ufw is active and already has rules for $ISLANDR_HTTP_PORT/$ISLANDR_HTTPS_PORT."
    fi
fi

# ---------------------------------------------------------------------------
# 2. Binary
# ---------------------------------------------------------------------------
echo ">>> 2/7 Binary"

STAGED=/tmp/islandr.staged
trap 'rm -f "$STAGED" "$STAGED.sha256"' EXIT

if [[ -n "$ISLANDR_BINARY" ]]; then
    [[ -f "$ISLANDR_BINARY" ]] || fail "ISLANDR_BINARY=$ISLANDR_BINARY does not exist."
    echo "  Using local binary: $ISLANDR_BINARY (no download, no checksum check)"
    cp "$ISLANDR_BINARY" "$STAGED"
else
    command -v curl >/dev/null 2>&1 || fail "curl is not installed." "sudo apt-get install -y curl"

    if [[ "$ISLANDR_VERSION" == "latest" ]]; then
        BASE="https://github.com/$REPO/releases/latest/download"
    else
        BASE="https://github.com/$REPO/releases/download/$ISLANDR_VERSION"
    fi
    ASSET="islandr-runner-linux-$ARCH"

    echo "  Downloading $ASSET ($ISLANDR_VERSION)..."
    curl -fsSL "$BASE/$ASSET" -o "$STAGED" \
        || fail "Download failed: $BASE/$ASSET" \
                "Check the version tag and that this host can reach github.com."
    curl -fsSL "$BASE/$ASSET.sha256" -o "$STAGED.sha256" \
        || fail "Checksum file download failed: $BASE/$ASSET.sha256"

    # Compare digests directly — older releases ship a .sha256 naming the
    # asset by its build-time path, which `sha256sum -c` cannot resolve here.
    EXPECTED="$(awk '{print $1}' "$STAGED.sha256")"
    ACTUAL="$(sha256sum "$STAGED" | awk '{print $1}')"
    [[ "$EXPECTED" == "$ACTUAL" ]] || fail "Checksum mismatch — refusing to install." \
        "expected: $EXPECTED" "actual:   $ACTUAL"
    echo "  Checksum OK ($ACTUAL)"
fi

# ---------------------------------------------------------------------------
# 3. User + directories
# ---------------------------------------------------------------------------
echo ">>> 3/7 User and directories"

if id islandr &>/dev/null; then
    echo "  User 'islandr' already exists, skipping."
else
    useradd -r -s /usr/sbin/nologin -d /var/lib/islandr -m islandr
fi

install -d -o islandr -g islandr /opt/islandr
install -d -o islandr -g islandr /var/lib/islandr/data

install -o islandr -g islandr -m 0755 "$STAGED" /opt/islandr/islandr

# ---------------------------------------------------------------------------
# 4. sudoers entry (Option B: sudo instead of CAP_NET_ADMIN)
# ---------------------------------------------------------------------------
echo ">>> 4/7 sudoers entry (interface: $WG_INTERFACE)"

cat > /etc/sudoers.d/islandr <<SUDOERS
# Islandr service user: scoped sudo for nft and wg only (ADR-0011).
# See docs/install/hardening.md before editing — the wildcard, the absolute
# paths and the interface name are all load-bearing.
islandr ALL=(root) NOPASSWD: $NFT_BIN -c -f /var/lib/islandr/islandr-nft-*.nft
islandr ALL=(root) NOPASSWD: $NFT_BIN -f /var/lib/islandr/islandr-nft-*.nft
islandr ALL=(root) NOPASSWD: $NFT_BIN delete table inet islandr
islandr ALL=(root) NOPASSWD: $WG_BIN set $WG_INTERFACE *
islandr ALL=(root) NOPASSWD: $WG_BIN syncconf $WG_INTERFACE *
islandr ALL=(root) NOPASSWD: $WG_BIN show $WG_INTERFACE
islandr ALL=(root) NOPASSWD: $WG_BIN show $WG_INTERFACE dump
# Network diagnostics (ADR-0025) need no entry: ping and tracepath do not run
# through sudo. See hardening.md if pings fail with "Operation not permitted".
SUDOERS
chmod 0440 /etc/sudoers.d/islandr

if ! visudo -c -f /etc/sudoers.d/islandr >/dev/null; then
    echo "ERROR: sudoers syntax broken — removing the file."
    rm /etc/sudoers.d/islandr
    exit 1
fi

# The activity poller makes sudo's "wg show" calls noisy in the journal. Do
# NOT silence that with `Defaults!cmnd_alias !pam_session` — it breaks every
# sudo call under ProtectSystem=strict. See hardening.md.

# ---------------------------------------------------------------------------
# 5. Env file with configuration variables
# ---------------------------------------------------------------------------
echo ">>> 5/7 /etc/default/islandr"

ADMIN_PW="$(openssl rand -base64 24)"

# Key for "encrypted" private-key retention (ADR-0007) — without it only
# "never"/"plaintext" are selectable. Upgradable to a TPM2-bound
# systemd-creds key later, see docs/install.md section 10.
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
# Device discovery (ADR-0014) and network diagnostics (ADR-0025) are real by
# default. ISLANDR_DISCOVERY_MODE=mock / ISLANDR_DIAG_MODE=mock simulate them.

# Database
QUARKUS_DATASOURCE_JDBC_URL=jdbc:sqlite:/var/lib/islandr/data/islandr.db

# islandr terminates TLS itself (ADR-0015) and can provision a Let's Encrypt
# certificate (ADR-0019) — port 80 must stay publicly reachable for the ACME
# HTTP-01 challenge, always, even after a certificate is issued.
# Behind a reverse proxy instead: 127.0.0.1 + 8080/8443, see reverse-proxy.md.
QUARKUS_HTTP_HOST=$ISLANDR_HTTP_HOST
QUARKUS_HTTP_PORT=$ISLANDR_HTTP_PORT
QUARKUS_HTTP_SSL_PORT=$ISLANDR_HTTPS_PORT

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
# 6. systemd unit
# ---------------------------------------------------------------------------
echo ">>> 6/7 systemd unit"

cat > /etc/systemd/system/islandr.service <<'UNIT'
[Unit]
Description=Islandr — WireGuard access management
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
User=islandr
Group=islandr
WorkingDirectory=/var/lib/islandr
EnvironmentFile=/etc/default/islandr
ExecStart=/opt/islandr/islandr

# Do not "harden" the next four lines without reading
# docs/install/hardening.md — NoNewPrivileges=true and a CapabilityBoundingSet
# each break every sudo nft/wg call, silently, until the first one runs.
NoNewPrivileges=false
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/islandr
PrivateTmp=true

# Lets the unprivileged user bind 80/443 (TLS) and 53 (resource DNS). Grants
# nothing else — not root, not CAP_NET_ADMIN.
AmbientCapabilities=CAP_NET_BIND_SERVICE

# Slow on purpose: a crash loop at 3s floods the journal past the first, only
# useful stack trace. StartLimit* above gives up rather than looping forever.
Restart=on-failure
RestartSec=10

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload

# ---------------------------------------------------------------------------
# 7. Start
# ---------------------------------------------------------------------------
echo ">>> 7/7 Starting service"

systemctl enable islandr
systemctl start islandr

# Give it a moment to boot
sleep 4

systemctl --no-pager status islandr || true

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
if [[ "$ISLANDR_HTTP_HOST" == "0.0.0.0" ]]; then
    LISTEN_NOTE="http://<host>:$ISLANDR_HTTP_PORT and https://<host>:$ISLANDR_HTTPS_PORT
  Port $ISLANDR_HTTP_PORT must stay open in your firewall/security group for Let's
  Encrypt's HTTP-01 challenge, even once a real certificate is issued.
  Running a reverse proxy instead? Set QUARKUS_HTTP_HOST=127.0.0.1 plus loopback
  ports in /etc/default/islandr, then: sudo systemctl restart islandr
  See docs/install/reverse-proxy.md for both paths side by side."
else
    LISTEN_NOTE="http://$ISLANDR_HTTP_HOST:$ISLANDR_HTTP_PORT and https://$ISLANDR_HTTP_HOST:$ISLANDR_HTTPS_PORT"
    if [[ "$ISLANDR_HTTP_HOST" == "127.0.0.1" || "$ISLANDR_HTTP_HOST" == "localhost" ]]; then
        WG_IP="$(ip -4 -o addr show "$WG_INTERFACE" 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | head -n1)"
        LISTEN_NOTE+="
  LOOPBACK ONLY — reachable from this host, and from nothing else. A local
  curl will succeed while a browser, including one coming in over the
  WireGuard tunnel, gets no connection at all.

  That is correct if a reverse proxy on this host front-ends islandr.
  To reach the console over the tunnel instead, bind to ${WG_INTERFACE}'s
  address (still not exposed publicly):
    sudo sed -i 's|^QUARKUS_HTTP_HOST=.*|QUARKUS_HTTP_HOST=${WG_IP:-<wg-address>}|' /etc/default/islandr
    sudo systemctl restart islandr
  Or 0.0.0.0 to listen on every interface, public one included.
  See docs/install/reverse-proxy.md."
    else
        LISTEN_NOTE+="
  Point your reverse proxy at these ports. Public port 80 must still reach the
  proxy and be forwarded for the ACME HTTP-01 challenge.
  See docs/install/reverse-proxy.md."
    fi
fi

if [[ -n "${UFW_MISSING:-}" ]]; then
    LISTEN_NOTE+="

  ufw is active with no rule for port(s) ${UFW_MISSING% } — until you add one,
  only this host itself can connect:"
    for port in $UFW_MISSING; do
        LISTEN_NOTE+="
    sudo ufw allow in on $WG_INTERFACE to any port $port proto tcp"
    done
fi

if systemctl is-active --quiet islandr; then
    STATE="running"
else
    STATE="NOT running — diagnosis below"
    echo ""
    echo "  ==================================================================="
    echo "  The service did not come up. Last log lines:"
    echo "  ==================================================================="
    journalctl -u islandr -b --no-pager -n 40 | sed 's/^/  /' || true
    echo ""

    # A native image killed by SIGKILL usually means the kernel ran out of
    # memory, not that islandr failed — the journal above shows no stack trace
    # in that case, so say it explicitly.
    if journalctl -u islandr -b --no-pager | grep -q "status=9/KILL"; then
        echo "  The process was SIGKILLed, which islandr never does to itself."
        # dmesg can be restricted (kernel.dmesg_restrict), so read the kernel
        # ring buffer through the journal as well before giving up on it.
        KERNLOG="$( { dmesg 2>/dev/null; journalctl -k -b --no-pager 2>/dev/null; } | grep -iE "oom-kill|out of memory|killed process" || true)"
        if grep -qi "islandr" <<<"$KERNLOG"; then
            echo "  The kernel OOM killer took it — this host has too little free RAM:"
            free -m | sed 's/^/    /'
            echo ""
            echo "  Add a swap file, then start the service again:"
            echo "    sudo fallocate -l 1G /swapfile && sudo chmod 600 /swapfile"
            echo "    sudo mkswap /swapfile && sudo swapon /swapfile"
            echo "    echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab"
            echo "    sudo systemctl start islandr"
        else
            echo "  No OOM entry found for islandr, but the kernel log may be"
            echo "  restricted. Check by hand:"
            echo "    sudo dmesg -T | grep -iE 'oom|killed process'"
            echo "    sudo journalctl -k -b | grep -iE 'oom|killed process'"
            echo "    free -m"
        fi
        echo ""
    fi

    echo "  Reproduce it in the foreground, with the same environment, to get"
    echo "  the full startup output:"
    echo "    sudo systemctl stop islandr"
    echo "    sudo -u islandr env \$(grep -vE '^#|^\$' /etc/default/islandr | xargs) \\"
    echo "         /opt/islandr/islandr"
    echo ""
fi

cat <<SUMMARY

====================================================================
Islandr is installed. Service: $STATE

Installed files
  /opt/islandr/islandr              the binary
  /var/lib/islandr/data/islandr.db  SQLite database (created on first start)
  /etc/default/islandr              configuration (0640, root:islandr)
  /etc/sudoers.d/islandr            scoped sudo for $NFT_BIN + $WG_BIN
  /etc/systemd/system/islandr.service
  /var/lib/islandr                  service user home / working directory

Firewall writes are PAUSED (dry-run)
  Nothing has been written to WireGuard or nftables. Islandr generates and
  validates the ruleset but does not apply it. The Dashboard shows a banner
  while this is on.

  Turn it off in Settings -> Firewall once the generated ruleset looks right:
    sudo nft list table inet islandr    # empty until you activate
  If you reach this host through $WG_INTERFACE, check the ruleset first — an
  activated policy that drops your own session locks you out. Recovery is
  console/rescue access on the provider side, plus:
    sudo nft delete table inet islandr

Managing the service
  sudo systemctl status islandr        state and last log lines
  sudo systemctl restart islandr       after editing /etc/default/islandr
  sudo systemctl stop islandr
  sudo systemctl start islandr
  sudo systemctl disable --now islandr stop and remove from boot

Logs
  sudo journalctl -u islandr -f        follow live
  sudo journalctl -u islandr -n 200    last 200 lines
  sudo journalctl -u islandr -p err    errors only
  sudo journalctl -u islandr -b        this boot

WireGuard state (islandr manages the peers on this interface)
  sudo wg show $WG_INTERFACE
  sudo nft list table inet islandr

Removing it again
  sudo systemctl disable --now islandr
  sudo rm /etc/systemd/system/islandr.service /etc/sudoers.d/islandr /etc/default/islandr
  sudo systemctl daemon-reload
  sudo rm -rf /opt/islandr /var/lib/islandr   # deletes the database too
  sudo userdel islandr

Listening on
  $LISTEN_NOTE

Smoke test through an SSH tunnel:
  ssh -L 8443:127.0.0.1:443 USER@HOST
  # then locally: https://localhost:8443 (dummy cert until you configure one)
====================================================================
SUMMARY
