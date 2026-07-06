# Installing Islandr

Two deployment paths:

| Path | Best for |
|---|---|
| [Native binary + systemd](#native-binary--systemd) | Production — runs as an unprivileged user with scoped `sudo` for `nft` and `wg` ([ADR-0011](adr/0011-process-privilege-model.md)) |
| [Docker Compose](#docker-compose) | **Demo / dev only** — uses mock adapters, no real WireGuard or firewall management |

> **Docker in production** is targeted for 0.11.0 (v1 line) via a Unix socket proxy that keeps the container unprivileged ([ADR-0012](adr/0012-docker-socket-proxy.md)).

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

### 5. Configure environment variables

```bash
# Generate a strong admin password
ADMIN_PW="$(openssl rand -base64 24)"

sudo tee /etc/default/islandr > /dev/null << EOF
# Local recovery admin — leave ISLANDR_ADMIN_PASSWORD empty to disable local login.
ISLANDR_ADMIN_USER=admin
ISLANDR_ADMIN_PASSWORD=${ADMIN_PW}

# WireGuard + firewall
ISLANDR_WG_INTERFACE=wg0
ISLANDR_WG_MODE=real
ISLANDR_NFT_MODE=real
ISLANDR_USE_SUDO=true

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

For Docker (dev only — no systemd-creds), use the env-var fallback instead:
```bash
echo "ISLANDR_ENCRYPTION_KEY=$(openssl rand -base64 32)" >> .env
```

---

## Docker Compose

> **Demo and dev use only.** The Docker image uses mock WireGuard and nftables adapters — it does not manage real peers or firewall rules. For production, use the [native binary + systemd](#native-binary--systemd) path. Production Docker support is targeted for 0.11.0 (v1 line) ([ADR-0012](adr/0012-docker-socket-proxy.md)).

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
      QUARKUS_HTTP_HOST: 0.0.0.0
      QUARKUS_HTTP_PORT: "8080"
    restart: unless-stopped
```

Put the password in a `.env` file next to `docker-compose.yml` (never commit it):

```bash
echo "ISLANDR_ADMIN_PASSWORD=$(openssl rand -base64 24)" > .env
chmod 0600 .env
cat .env   # save this password
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

---

## Post-install checklist (native binary / production)

- [ ] Reverse proxy with TLS in front of port 8080
- [ ] Admin password saved securely, not committed to version control
- [ ] `wg0` interface up and WireGuard server keys configured in Islandr Settings
- [ ] OIDC provider configured (Settings → Identity) — local admin is for recovery only
- [ ] Firewall dry-run **disabled** once the generated ruleset looks correct (Settings → Firewall)
- [ ] Backup job for `/var/lib/islandr/data/islandr.db` — contains OIDC client secrets, treat accordingly
- [ ] `sudo journalctl -u islandr` shows no errors on first start

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
