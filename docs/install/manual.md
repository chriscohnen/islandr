# Installing Islandr by hand

Every step [`setup-hub.sh`](setup-hub.sh) performs, written out. Read this if
you are adapting the install to a host that is not a fresh Debian/Ubuntu VPS,
or if you would rather see what happens before it happens.

For the ordinary case — one script on a fresh VPS — start at
[install.md](../install.md). The prerequisites listed there apply here too.

---

### 1. Download the binary

```bash
# Detect architecture (amd64 or arm64)
ARCH=$(dpkg --print-architecture)   # on Debian/Ubuntu
# ARCH=$(uname -m | sed 's/x86_64/amd64/;s/aarch64/arm64/')  # alternative

cd /tmp
BASE="https://github.com/chriscohnen/islandr/releases/latest/download"
curl -fL -O "$BASE/islandr-runner-linux-${ARCH}"
curl -fL -O "$BASE/islandr-runner-linux-${ARCH}.sha256"

# Verify checksum — this must print "OK" before you install anything
sha256sum -c "islandr-runner-linux-${ARCH}.sha256"
mv "islandr-runner-linux-${ARCH}" /tmp/islandr
```

### 2. Create a dedicated system user

```bash
sudo useradd -r -s /usr/sbin/nologin -d /var/lib/islandr -m islandr
```

The `islandr` user has no password, no login shell, and no sudo rights beyond what the next step grants.

### 3. Install the binary and data directory

```bash
sudo install -d -o islandr -g islandr -m 0750 /opt/islandr
sudo install -d -o islandr -g islandr -m 0700 /var/lib/islandr/data

sudo install -o islandr -g islandr -m 0755 /tmp/islandr /opt/islandr/islandr
```

### 4. Grant scoped sudo for nft and wg

Create `/etc/sudoers.d/islandr`:

```bash
sudo tee /etc/sudoers.d/islandr > /dev/null << 'EOF'
# Islandr: allow only the nft and wg commands the service needs.
# nft: validate (-c) and atomically apply a ruleset staged in /var/lib/islandr.
#      The name pattern is required — a fresh temp file is staged per apply.
# wg:  manage peers on wg0 only.
islandr ALL=(root) NOPASSWD: /usr/sbin/nft -c -f /var/lib/islandr/islandr-nft-*.nft
islandr ALL=(root) NOPASSWD: /usr/sbin/nft -f /var/lib/islandr/islandr-nft-*.nft
islandr ALL=(root) NOPASSWD: /usr/sbin/nft delete table inet islandr
# Hands over from the fail-closed boot ruleset to Islandr's own (step 6b).
# Islandr never creates that table, it only removes it once its own is live.
islandr ALL=(root) NOPASSWD: /usr/sbin/nft list table inet islandr-boot
islandr ALL=(root) NOPASSWD: /usr/sbin/nft delete table inet islandr-boot
islandr ALL=(root) NOPASSWD: /usr/bin/wg set wg0 *
islandr ALL=(root) NOPASSWD: /usr/bin/wg show wg0 dump
EOF

sudo chmod 0440 /etc/sudoers.d/islandr
sudo visudo -c -f /etc/sudoers.d/islandr
```

`visudo -c` must exit with `parsed OK` before you continue. If `nft` or `wg` live under a different path on your distro, adjust with `which nft` and `which wg`.

The 30s activity poller makes these `wg show` calls noisy in the journal — three lines per tick. Do **not** silence that with a scoped `Defaults!cmnd_alias !pam_session` rule; it breaks every sudo call, `nft` included. [install/hardening.md](hardening.md#do-not-silence-the-journal-noise-with-pam_session) explains why.

If your WireGuard interface isn't named `wg0`, replace `wg0` in **both** places it appears here — and set `ISLANDR_WG_INTERFACE` to match in step 5. The interface name is baked into the sudoers rules (`wg set wg0 *`, etc.); running the service against a different interface than what sudoers grants fails silently with permission errors in `journalctl`. ([setup-hub.sh](setup-hub.sh) takes this as `WG_INTERFACE=wg1 sudo ./setup-hub.sh` instead.)

### 5. Configure environment variables

```bash
# Generate a strong admin password
ADMIN_PW="$(openssl rand -base64 24)"

# Generate the private-key-retention encryption key (ADR-0007). Without this,
# only the "never"/"plaintext" retention modes are selectable in Settings —
# generating it now means "encrypted" is available from the first start.
ENCRYPTION_KEY="$(openssl rand -base64 32)"

sudo tee /etc/default/islandr > /dev/null << EOF
# Local recovery admin — leave ISLANDR_ADMIN_PASSWORD empty to disable local login.
ISLANDR_ADMIN_USER=admin
ISLANDR_ADMIN_PASSWORD=${ADMIN_PW}

# Private-key-retention encryption key (ADR-0007) — see step 9 to upgrade to a
# TPM2-bound key via systemd-creds instead.
ISLANDR_ENCRYPTION_KEY=${ENCRYPTION_KEY}

# WireGuard + firewall
ISLANDR_WG_INTERFACE=wg0
ISLANDR_WG_MODE=real
ISLANDR_NFT_MODE=real
ISLANDR_USE_SUDO=true
# Device discovery (ADR-0014) scans for real by default — no setting needed.
# Set ISLANDR_DISCOVERY_MODE=mock to get two fixed synthetic hosts instead
# (e.g. a staging box you don't want probing a real subnet).

# Database (SQLite — adequate for small teams; see ADR-0004 for PostgreSQL path)
QUARKUS_DATASOURCE_JDBC_URL=jdbc:sqlite:/var/lib/islandr/data/islandr.db

# HTTP — bind to loopback only; put a reverse proxy in front for TLS
QUARKUS_HTTP_HOST=127.0.0.1
QUARKUS_HTTP_PORT=8080

QUARKUS_LOG_LEVEL=INFO
EOF

sudo chown root:islandr /etc/default/islandr
sudo chmod 0640 /etc/default/islandr

echo "Admin password: ${ADMIN_PW}"
echo "Save this — it is only stored in /etc/default/islandr."
```

### 6. Install and start the systemd unit

> **Why `Before=wg-quick@…`:** nftables rules do not survive a reboot, and
> Islandr applies its table at startup. If the tunnel came up first, every peer
> written in `<iface>.conf` could forward unfiltered until Islandr was ready —
> the kernel's own FORWARD policy is `accept` when no other firewall is loaded.
> Starting Islandr first closes that window; its rules match on `iifname`, which
> is resolved per packet, so they are in place before the interface exists. The
> peers Islandr manages are applied once the interface is up, at startup or on
> the next activity-poller tick. **Existing installs** do not get this from an
> update — add it with `sudo systemctl edit islandr` (`[Unit]` /
> `Before=wg-quick@wg0.service`, with your interface name).
>
> Ordering alone does not help if Islandr fails to start at all — a failed
> start counts as finished, so `wg-quick` proceeds and no table is ever applied.
> That case is covered by the fail-closed boot ruleset below, which
> `setup-hub.sh` installs and which Islandr removes once its own table is live.


```bash
sudo tee /etc/systemd/system/islandr.service > /dev/null << 'EOF'
[Unit]
Description=Islandr — WireGuard access management
After=network-online.target
Wants=network-online.target

# Ordering only, no dependency: Islandr must not start or stop the tunnel.
# Replace wg0 if your interface is named differently.
Before=wg-quick@wg0.service

StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
User=islandr
Group=islandr
WorkingDirectory=/var/lib/islandr
EnvironmentFile=/etc/default/islandr
ExecStart=/opt/islandr/islandr -Xmx192m

# Without -Xmx a native image takes up to 80% of the host's RAM as its maximum
# heap, and the collector has no reason to give any of it back: on a 1 GB VPS
# that showed as 253 MB resident, 29.8% of the machine, for an idle service.
# The cap makes the number a constant rather than a share of the hardware.
# Raise it if the journal reports heap pressure.

# Do not "harden" the next four lines without reading install/hardening.md —
# NoNewPrivileges=true and a CapabilityBoundingSet each break every sudo
# nft/wg call, silently, until the first one runs.
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
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now islandr
sudo systemctl status islandr
```

Several of these settings look wrong for a hardened unit and are load-bearing —
`NoNewPrivileges=false`, the missing `CapabilityBoundingSet`, `PrivateTmp`,
the restart timing. [install/hardening.md](hardening.md) explains each
one and the outage it prevents. Read it before you tighten anything.


### 6b. The fail-closed boot ruleset

Islandr's nftables table is built from the database and applied at startup, and
it does not survive a reboot. If Islandr then fails to start — OOM-killed on a
small hub, or refusing a migrated schema — systemd gives up after about a
minute, `wg-quick` brings the interface up regardless, and the hub forwards
whatever is written in `<iface>.conf` with no access control at all. The
dangerous part is the shape: the VPN appears to work, so nobody investigates.

`setup-hub.sh` installs a second, minimal table for exactly that window
([ADR-0031](../adr/0031-fail-closed-boot-ruleset.md)):

```
/etc/islandr/boot.nft                            table inet islandr-boot
/etc/systemd/system/islandr-boot-firewall.service oneshot, before wg-quick@<iface>
```

It drops forwarding into and out of the WireGuard interface and accepts
everything else, so the host's other forwarding — Docker, a second interface —
is untouched. Islandr deletes the table **after** applying its own, never
before, so there is no moment with neither.

`/etc/nftables.conf` is deliberately not touched. Your own ruleset there stays
yours; Islandr ships its own unit and its own file, for the same one-writer
reason [ADR-0030](../adr/0030-wireguard-config-file-ownership.md) gives for the
WireGuard config.

**What this trades.** A hub whose Islandr does not start forwards nothing,
where before it would at least have carried the peers from the config file. For
an access-control product that is the right direction, but it is an
availability decision: "the control plane is down, so traffic stops" rather
than "the control plane is down, so everyone may do anything".

**On a fresh install, firewall writes are paused** (dry-run is the default), so
Islandr applies nothing and keeps the boot table on purpose — opening the hub
on the strength of a setting whose point is that nothing is enforced yet would
defeat it. Until you activate enforcement in **Settings → Firewall**, the
tunnel comes up and forwards nothing. The Admin Console says so, in as many
words, rather than leaving you to diagnose a routing fault.

If the handover ever fails, both tables are live and a drop in either wins, so
granted traffic stays blocked. That is reported in the console and in the
journal; the manual fix is:

```bash
sudo nft delete table inet islandr-boot
```

**Existing installs** do not get any of this from an update, same as the
ordering change in 0.21.0 — re-run `setup-hub.sh`, or create the two files by
hand.

### 7. Verify

```bash
# Follow logs
sudo journalctl -u islandr -f

# Smoke test from your laptop via SSH tunnel
ssh -L 8080:127.0.0.1:8080 user@your-hub
# then open http://localhost:8080
```

### 8. First start: the firewall is not enforced yet

A fresh install comes up in **dry-run mode** (`firewall_dry_run = 1`). Islandr
builds the WireGuard peer set and the nftables ruleset, validates them with
`nft -c -f`, and logs what it *would* do — but writes nothing. `nft list table
inet islandr` stays empty and existing peers on the interface are untouched.

This is deliberate: installing on a VPS you are currently reaching *through*
that same WireGuard tunnel must not cut your own session. Configure resources,
groups and the ACL matrix first, look at the generated ruleset, then activate.

Turn it off in **Settings → Firewall** ("Firewall-Schreiben pausieren"). The
Dashboard carries a banner for as long as dry-run is on, so a paused install
does not look like a working one.

Before you activate, over a WireGuard-only SSH session: confirm the ACL matrix
grants your own peer access to this host on port 22. If you do lock yourself
out, the fix needs console or rescue access from the provider:

```bash
sudo nft delete table inet islandr   # drop islandr's ruleset, tunnel comes back
```

### 9. TLS (required for production)

Islandr binds to `127.0.0.1:8080`. Put a reverse proxy in front for TLS.

**Caddy** (simplest — automatic Let's Encrypt):

```
islandr.yourdomain.com {
    reverse_proxy 127.0.0.1:8080
}
```

**nginx:**

```nginx
server {
    listen 443 ssl;
    server_name islandr.yourdomain.com;
    ssl_certificate     /etc/ssl/certs/islandr.crt;
    ssl_certificate_key /etc/ssl/private/islandr.key;
    location / { proxy_pass http://127.0.0.1:8080; }
}
```

### 10. Encrypted private key retention (optional, recommended for compliance)

By default, private keys are never stored (`retention=never`). If you enable `retention=plaintext`
you can switch to `retention=encrypted` so keys are AES-256-GCM encrypted at rest. A DB-only
breach cannot recover peer private keys without the separate master key.

Step 5 already generated `ISLANDR_ENCRYPTION_KEY` into `/etc/default/islandr`, so `encrypted` is
selectable in Settings right away. The steps below are only needed if you want the key upgraded
from a plain env var to a **TPM2-bound** credential (stronger — the key can't be read by copying
the env file off the disk):

```bash
# 1. Generate a 32-byte key and encrypt it, machine-bound via TPM2 (requires systemd ≥ 248):
openssl rand -base64 32 | sudo systemd-creds encrypt --tpm2=yes - /etc/islandr/kek.cred
sudo chown root:islandr /etc/islandr/kek.cred
sudo chmod 0440 /etc/islandr/kek.cred

# 2. Add to the [Service] section of /etc/systemd/system/islandr.service:
#    LoadCredentialEncrypted=ENCRYPTION_KEY:/etc/islandr/kek.cred
sudo systemctl edit islandr   # adds an override.conf with the line above

# 3. Tell Islandr where to find the decrypted key at runtime (add to /etc/default/islandr):
echo "ISLANDR_ENCRYPTION_KEY_PATH=/run/credentials/islandr.service/ENCRYPTION_KEY" | \
    sudo tee -a /etc/default/islandr

# 4. Reload and restart:
sudo systemctl daemon-reload
sudo systemctl restart islandr

# 5. In Admin Console: Settings → Private Key Retention → encrypted
#    Islandr auto-migrates any existing plaintext keys in the same transaction.
```

Without TPM2 (fallback — key is encrypted with the machine's host key, no hardware binding):
```bash
openssl rand -base64 32 | sudo systemd-creds encrypt - /etc/islandr/kek.cred
```

Docker has no `systemd-creds`, so it stays on the env-var key generated in the
[Docker Compose](../install.md#docker-compose) section's `.env` file — that's already sufficient to make
`encrypted` selectable in Settings.
