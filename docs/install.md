# Installing Islandr

Two deployment paths:

| Path | Best for |
|---|---|
| [Native binary + systemd](#native-binary--systemd) | Production — runs as an unprivileged user with scoped `sudo` for `nft` and `wg` ([ADR-0011](adr/0011-process-privilege-model.md)) |
| [Docker Compose](#docker-compose) | Evaluate from a bare `docker run`; **enforce in production** by attaching the host socket proxy ([ADR-0012](adr/0012-docker-socket-proxy.md)) |

> **Docker in production** is available in 0.11.0 (v1 line): a Unix socket proxy on the host performs the privileged `wg`/`nft` work while the container stays unprivileged ([ADR-0012](adr/0012-docker-socket-proxy.md)). See [Enforce in production](#5-enforce-in-production-unix-socket-proxy).

Both paths require a **Linux host (Ubuntu 22.04+ or Debian 12+)** with:
- WireGuard kernel module loaded (`modprobe wireguard`)
- `nftables` installed and the `nft` CLI available
- A configured `wg0` interface (a keypair + `[Interface]` section — Islandr manages peers, not the interface itself)

macOS and Windows are dev-only. Without `ISLANDR_WG_MODE=real` the binary defaults to a mock adapter — no real tunnel is configured.

---

## Native binary + systemd

### 1. Download the binary

```bash
# Detect architecture (amd64 or arm64)
ARCH=$(dpkg --print-architecture)   # on Debian/Ubuntu
# ARCH=$(uname -m | sed 's/x86_64/amd64/;s/aarch64/arm64/')  # alternative

curl -L "https://github.com/chriscohnen/islandr/releases/latest/download/islandr-runner-linux-${ARCH}" \
     -o /tmp/islandr
curl -L "https://github.com/chriscohnen/islandr/releases/latest/download/islandr-runner-linux-${ARCH}.sha256" \
     -o /tmp/islandr.sha256

# Verify checksum
cd /tmp && sha256sum -c islandr.sha256
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
# Islandr: allow only the exact nft and wg commands the service needs.
# nft: validate (-c) and atomically apply a fixed file path.
# wg:  manage peers on wg0 only.
islandr ALL=(root) NOPASSWD: /usr/sbin/nft -c -f /var/lib/islandr/islandr-nft-*.nft
islandr ALL=(root) NOPASSWD: /usr/sbin/nft -f /var/lib/islandr/islandr-nft-*.nft
islandr ALL=(root) NOPASSWD: /usr/sbin/nft delete table inet islandr
islandr ALL=(root) NOPASSWD: /usr/bin/wg set wg0 *
islandr ALL=(root) NOPASSWD: /usr/bin/wg syncconf wg0 *
islandr ALL=(root) NOPASSWD: /usr/bin/wg show wg0
islandr ALL=(root) NOPASSWD: /usr/bin/wg show wg0 dump
EOF

sudo chmod 0440 /etc/sudoers.d/islandr
sudo visudo -c -f /etc/sudoers.d/islandr
```

`visudo -c` must exit with `parsed OK` before you continue. If `nft` or `wg` live under a different path on your distro, adjust with `which nft` and `which wg`.

The 30s activity poller's `wg show wg0`/`wg show wg0 dump` calls do generate three journal lines per tick (sudo's own log line plus PAM session open/close) — this is noisy but **don't** try to silence it with a scoped `Defaults!cmnd_alias !pam_session` rule: `islandr.service` runs with `ProtectSystem=strict`, which makes `/run` read-only for the service and everything it spawns (including `sudo`). Sudo tolerates the resulting `/run/sudo/ts: Read-only file system` when it can still complete a PAM session, but disabling `pam_session` removes that tolerance and sudo falls back to demanding interactive auth — breaking **every** sudo call, including `nft`, not just the noisy one. Confirmed the hard way in production 2026-07-21.

If your WireGuard interface isn't named `wg0`, replace `wg0` in **both** places it appears here — and set `ISLANDR_WG_INTERFACE` to match in step 5. The interface name is baked into the sudoers rules (`wg set wg0 *`, etc.); running the service against a different interface than what sudoers grants fails silently with permission errors in `journalctl`. ([setup-hub.sh](install/setup-hub.sh) takes this as `WG_INTERFACE=wg1 sudo ./setup-hub.sh` instead.)

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

```bash
sudo tee /etc/systemd/system/islandr.service > /dev/null << 'EOF'
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
PrivateTmp=false

Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
EOF

sudo systemctl daemon-reload
sudo systemctl enable --now islandr
sudo systemctl status islandr
```

### 7. Verify

```bash
# Follow logs
sudo journalctl -u islandr -f

# Smoke test from your laptop via SSH tunnel
ssh -L 8080:127.0.0.1:8080 user@your-hub
# then open http://localhost:8080
```

### 8. TLS (required for production)

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

### 9. Encrypted private key retention (optional, recommended for compliance)

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
[Docker Compose](#docker-compose) section's `.env` file — that's already sufficient to make
`encrypted` selectable in Settings.

---

## Docker Compose

> A **bare** `docker run` / compose (below) boots the full GUI so you can evaluate islandr and build a complete configuration — but the container is unprivileged, so enforcement runs in a degraded *"enforcement unavailable"* state (changes are saved, not applied). To **enforce in production**, attach the host socket proxy in [step 5](#5-enforce-in-production-unix-socket-proxy) ([ADR-0012](adr/0012-docker-socket-proxy.md)). The [native binary + systemd](#native-binary--systemd) path remains the other production option.

### 1. Prerequisites

```bash
# Install Docker
curl -fsSL https://get.docker.com | sh
```

### 2. Create docker-compose.yml

```yaml
services:
  islandr:
    image: ghcr.io/chriscohnen/islandr:latest
    ports:
      - "8080:8080"
    environment:
      ISLANDR_ADMIN_USER: admin
      ISLANDR_ADMIN_PASSWORD: "${ISLANDR_ADMIN_PASSWORD}"
      ISLANDR_ENCRYPTION_KEY: "${ISLANDR_ENCRYPTION_KEY}"
      QUARKUS_HTTP_HOST: 0.0.0.0
      QUARKUS_HTTP_PORT: "8080"
    restart: unless-stopped
```

Put the password and the private-key-retention encryption key (ADR-0007) in a `.env` file next to
`docker-compose.yml` (never commit it). Generating the encryption key now means `encrypted`
retention is selectable in Settings from the first start, instead of an extra step later:

```bash
{
  echo "ISLANDR_ADMIN_PASSWORD=$(openssl rand -base64 24)"
  echo "ISLANDR_ENCRYPTION_KEY=$(openssl rand -base64 32)"
} > .env
chmod 0600 .env
cat .env   # save the admin password
```

### 3. Start

```bash
docker compose up -d
docker compose logs -f
```

### 4. Verify

```bash
# Open the admin console
curl http://localhost:8080
```

### 5. Enforce in production (Unix socket proxy)

The image above **boots and runs the full GUI**, but a container is unprivileged and cannot touch the host's WireGuard or nftables — so out of the box it runs in a degraded *"enforcement unavailable"* state: changes are saved but not applied. To enforce, add the **host-side socket proxy** ([ADR-0012](adr/0012-docker-socket-proxy.md)): a tiny systemd service that performs the privileged `wg`/`nft` operations while the container stays unprivileged and only talks to its socket.

> **Snap Docker is not compatible with this step.** If Docker was installed via `snap install docker` (common on Ubuntu, e.g. if it was already on the host for other containers before islandr), `dockerd` runs in a confined mount namespace that can only bind-mount paths under `$HOME`, `/mnt`, or `/media` — it cannot create or mount `/var/lib/islandr` or `/run/islandr/proxy.sock`, failing with `mkdir ...: read-only file system` even if that path already exists on the real host. This is independent of file permissions and isn't fixable from the compose side. Either install Docker via the official method above instead (the clean fix), or, if migrating an existing snap-Docker host with other containers isn't practical, relocate both shared paths under `$HOME`:
>
> - Point `/var/lib/islandr` at a real directory under `$HOME` in compose, then make `/var/lib/islandr` on the host a symlink to it (the proxy binary hardcodes that path, so it's a "make the path resolve there" workaround, not a config option).
> - Change `ListenStream=` in `/etc/systemd/system/islandr-proxy.socket` to a path under `$HOME` (this one *is* freely configurable — the proxy just takes whatever fd systemd hands it), and mount that real path to `/run/islandr/proxy.sock` in compose.
> - `islandr-proxy.service` hardens with `ProtectHome=read-only` (not `true`) specifically so these symlinked/relocated paths under `$HOME` stay readable — see [#36](https://github.com/chriscohnen/islandr/issues/36) if you're on an older install with `ProtectHome=true`.

**a. Install the socket proxy on the host** (needs `wg` and `nft` present):

```bash
curl -fsSL https://github.com/chriscohnen/islandr/releases/latest/download/install-proxy.sh | sudo bash
```

It installs the `islandr-proxy` binary (checksum-verified), a socket-activated systemd unit listening on `/run/islandr/proxy.sock` (owned `islandr:islandr`, mode `0600`), and scoped sudoers rules. Pin a version with `ISLANDR_PROXY_VERSION=v0.11.0`, or download and read it first before piping to `sudo bash`.

**b. Mount the socket and the shared state dir into the container.** Extend the compose:

```yaml
services:
  islandr:
    image: ghcr.io/chriscohnen/islandr:latest
    ports:
      - "8080:8080"
    environment:
      ISLANDR_ADMIN_USER: admin
      ISLANDR_ADMIN_PASSWORD: "${ISLANDR_ADMIN_PASSWORD}"
      QUARKUS_HTTP_HOST: 0.0.0.0
      # A container already defaults to socket mode; set it explicitly to be clear:
      ISLANDR_WG_MODE: socket
      ISLANDR_NFT_MODE: socket
    volumes:
      - /run/islandr/proxy.sock:/run/islandr/proxy.sock   # the privileged host helper
      - /var/lib/islandr:/var/lib/islandr                 # shared ruleset + SQLite DB
    restart: unless-stopped
```

The `/var/lib/islandr` bind mount is shared with the proxy: islandr writes the validated ruleset there and the proxy applies it (`nft -f /var/lib/islandr/ruleset.nft`). The managed WireGuard interface defaults to `wg0` — if you use another name, set `ISLANDR_WG_INTERFACE` on **both** the container and the `install-proxy.sh` step.

If you're extending an evaluation setup from [step 2](#2-create-dockercomposeyml) that had no explicit `volumes:` entry, your existing config lives in an **anonymous** volume (from the image's `VOLUME /var/lib/islandr`), not at this new bind-mount path — switching to the bind mount above starts the container against an empty `/var/lib/islandr` unless you back it up first.

Easiest: in the Admin Console, **Settings → Config export/import**, export a JSON snapshot before switching, then import it once the container is back up on the new mount. Alternatively, copy the raw volume contents across (also captures the SQLite DB itself, not just the config):

```bash
docker inspect <container> --format '{{ range .Mounts }}{{ .Name }} -> {{ .Destination }}{{ "\n" }}{{ end }}'
docker run --rm -v <volume-name-from-above>:/from -v /var/lib/islandr:/to alpine sh -c "cp -a /from/. /to/"
```

The generated `inet islandr` table hooks the host's `forward` chain, but only to police traffic actually entering or leaving via the WireGuard interface — anything else is accepted immediately and left to the rest of the host/other tables. So enforcement is safe to enable on a host that also runs other containers or services; it does not lock down forwarding for traffic unrelated to WireGuard.

**c. Verify.** In the admin console, **Settings → Enforcement** shows *Socket proxy — active* and the "enforcement unavailable" banner is gone. Any configuration built while degraded is reconciled and applied automatically once the proxy connects.

**Troubleshooting: recovering from a bad ruleset.** The `inet islandr` nftables table is applied fresh from the database on every container start (`ruleCount=0` in the logs means no ACL grants exist yet — the table still gets installed with its default-drop policy). If islandr itself, or something else on the host, becomes unreachable after enabling enforcement, you can remove the table immediately without touching the container:

```bash
sudo nft delete table inet islandr
```

This just hands `forward` traffic back to whatever else is on the host; islandr reapplies the table on its next restart (or the next ACL change), so treat this as a way to get back in, not a permanent fix. If the problem was islandr itself being unreachable on its own port, check `docker ps` / `docker inspect <container> --format '{{json .NetworkSettings.Ports}}'` for the actual published ports first — an empty `{}` there means a port-mapping/container problem, not a firewall one, and deleting the table won't help.

---

## Post-install checklist (native binary / production)

- [ ] Reverse proxy with TLS in front of port 8080
- [ ] Admin password saved securely, not committed to version control
- [ ] `wg0` interface up and WireGuard server keys configured in Islandr Settings
- [ ] OIDC provider configured (Settings → Identity) — local admin is for recovery only
- [ ] Firewall dry-run **disabled** once the generated ruleset looks correct (Settings → Firewall)
- [ ] Backup job for `/var/lib/islandr/data/islandr.db` — contains OIDC client secrets, treat
      accordingly (`scripts/backup.sh`, see below)
- [ ] `sudo journalctl -u islandr` shows no errors on first start

---

## Backups

`scripts/backup.sh` writes a consistent, gzip-compressed, rotated backup of the SQLite database —
via `sqlite3 .backup`, not a raw file copy, so a backup taken while the service is running can't
end up torn or corrupt:

```bash
sudo bash scripts/backup.sh                 # → /var/backups/islandr, 14-day local retention
sudo bash scripts/backup.sh /mnt/backups     # custom destination
```

Add it to cron for a daily run:

```
0 3 * * * root DB_PATH=/var/lib/islandr/data/islandr.db /opt/islandr/backup.sh
```

The database contains OIDC client secrets — encrypted at rest only if `ISLANDR_ENCRYPTION_KEY` is
set (step 5 above). Backups inherit that same sensitivity: written `0600`, owned by the DB file's
owner. The script only handles local rotation — for off-host retention, `rsync`/`scp` the `.gz`
output elsewhere, or point `restic backup` at the destination directory instead.

---

## Upgrading

**Native binary:**

```bash
# 1. Download the new binary
ARCH=$(dpkg --print-architecture)
curl -L "https://github.com/chriscohnen/islandr/releases/latest/download/islandr-runner-linux-${ARCH}" \
     -o /tmp/islandr-new
curl -L "https://github.com/chriscohnen/islandr/releases/latest/download/islandr-runner-linux-${ARCH}.sha256" \
     -o /tmp/islandr-new.sha256

# 2. Verify checksum
cd /tmp && sha256sum -c islandr-new.sha256

# 3. Swap the binary
sudo systemctl stop islandr
sudo install -o islandr -g islandr -m 0755 /tmp/islandr-new /opt/islandr/islandr
sudo systemctl start islandr

# 4. Confirm startup
sudo journalctl -u islandr -n 30
```

**Docker:**

```bash
docker compose pull
docker compose up -d
```

Flyway applies any pending database migrations automatically on startup.

---

## Uninstalling

**Native binary:**

```bash
# Stop and disable the service
sudo systemctl stop islandr
sudo systemctl disable islandr

# Remove service file and env file
sudo rm /etc/systemd/system/islandr.service
sudo rm /etc/default/islandr
sudo systemctl daemon-reload

# Remove sudoers rule
sudo rm /etc/sudoers.d/islandr

# Remove binary
sudo rm -rf /opt/islandr

# Remove data directory — contains the database and WireGuard private keys.
# Skip this if you want to keep your data for a reinstall.
sudo rm -rf /var/lib/islandr

# Remove system user
sudo userdel islandr
```

**Docker:**

```bash
docker compose down
docker rmi ghcr.io/chriscohnen/islandr:latest
```
