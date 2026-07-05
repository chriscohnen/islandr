#!/usr/bin/env bash
#
# Islandr — Hub-Setup (Ubuntu 24.04, x86_64).
#
# Was es macht:
#   - Service-User `islandr` anlegen
#   - /opt/islandr (Binary) und /var/lib/islandr/data (SQLite-DB) anlegen
#   - sudoers-Eintrag fuer nft + wg (Option B aus docs/install.md)
#   - /etc/default/islandr mit Env-Variablen schreiben
#   - systemd-Unit installieren und aktivieren
#
# Voraussetzungen:
#   - Native x86_64-Binary liegt unter /tmp/islandr und ist mit chmod +x ausfuehrbar
#   - apt-Pakete `wireguard` und `nftables` sind installiert (auf bestehender wg0-VPS ohnehin der Fall)
#   - SSH-User hat sudo-Rechte
#
# Idempotenz: das Skript ist NICHT idempotent. Es geht davon aus, dass auf
# einer frischen VPS gelaufen wird. Bei Wiederholung manuell aufraeumen.
#
# Aufruf:
#   chmod +x setup-hub.sh
#   sudo ./setup-hub.sh

set -euo pipefail

# ---------------------------------------------------------------------------
# 1. User + Verzeichnisse
# ---------------------------------------------------------------------------
echo ">>> 1/5 User und Verzeichnisse"

if id islandr &>/dev/null; then
    echo "  User 'islandr' existiert bereits, ueberspringe."
else
    useradd -r -s /usr/sbin/nologin -d /var/lib/islandr -m islandr
fi

install -d -o islandr -g islandr /opt/islandr
install -d -o islandr -g islandr /var/lib/islandr/data

if [[ ! -f /tmp/islandr ]]; then
    echo "FEHLER: /tmp/islandr fehlt. Erst per scp hochladen:"
    echo "  scp build/islandr-0.1.0-SNAPSHOT-runner USER@HOST:/tmp/islandr"
    exit 1
fi
install -o islandr -g islandr -m 0755 /tmp/islandr /opt/islandr/islandr
rm /tmp/islandr

# ---------------------------------------------------------------------------
# 2. sudoers-Eintrag (Option B: sudo statt CAP_NET_ADMIN)
# ---------------------------------------------------------------------------
echo ">>> 2/5 sudoers-Eintrag"

cat > /etc/sudoers.d/islandr <<'SUDOERS'
# Islandr service user: scoped sudo for nft and wg only (ADR-0011).
# Fixed file path for nft — no wildcard on the path, only on wg arguments.
islandr ALL=(root) NOPASSWD: /usr/sbin/nft -c -f /var/lib/islandr/ruleset.nft
islandr ALL=(root) NOPASSWD: /usr/sbin/nft -f /var/lib/islandr/ruleset.nft
islandr ALL=(root) NOPASSWD: /usr/sbin/nft delete table inet islandr
islandr ALL=(root) NOPASSWD: /usr/bin/wg set wg0 *
islandr ALL=(root) NOPASSWD: /usr/bin/wg syncconf wg0 *
islandr ALL=(root) NOPASSWD: /usr/bin/wg show wg0
SUDOERS
chmod 0440 /etc/sudoers.d/islandr

if ! visudo -c -f /etc/sudoers.d/islandr >/dev/null; then
    echo "FEHLER: sudoers-Syntax kaputt — Datei wird entfernt."
    rm /etc/sudoers.d/islandr
    exit 1
fi

# ---------------------------------------------------------------------------
# 3. Env-Datei mit Konfigurationsvariablen
# ---------------------------------------------------------------------------
echo ">>> 3/5 /etc/default/islandr"

# Starkes Admin-PW generieren (kann manuell ueberschrieben werden vor dem Start).
ADMIN_PW="$(openssl rand -base64 24)"

cat > /etc/default/islandr <<ENV
# Local admin (recovery user) — leere Variable = Login deaktiviert, /api/v1/auth/login -> 503.
ISLANDR_ADMIN_USER=admin
ISLANDR_ADMIN_PASSWORD=$ADMIN_PW

# WireGuard / nftables
ISLANDR_WG_INTERFACE=wg0
ISLANDR_WG_MODE=real
ISLANDR_NFT_MODE=real
ISLANDR_USE_SUDO=true

# Datenbank
QUARKUS_DATASOURCE_JDBC_URL=jdbc:sqlite:/var/lib/islandr/data/islandr.db

# HTTP — bindet nur auf Loopback. Traefik / nginx kommt davor.
QUARKUS_HTTP_HOST=127.0.0.1
QUARKUS_HTTP_PORT=8080

QUARKUS_LOG_LEVEL=INFO
ENV
chown root:islandr /etc/default/islandr
chmod 0640 /etc/default/islandr

echo ""
echo "  ===================================================================="
echo "  ADMIN-PASSWORT (jetzt notieren! steht sonst nur in /etc/default/islandr):"
echo "  $ADMIN_PW"
echo "  ===================================================================="
echo ""

# ---------------------------------------------------------------------------
# 4. systemd-Unit
# ---------------------------------------------------------------------------
echo ">>> 4/5 systemd-Unit"

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
# Temp-Files fuer nft -c -f / nft -f liegen unter /tmp.
PrivateTmp=false

Restart=on-failure
RestartSec=3

[Install]
WantedBy=multi-user.target
UNIT

systemctl daemon-reload

# ---------------------------------------------------------------------------
# 5. Start
# ---------------------------------------------------------------------------
echo ">>> 5/5 Service starten"

systemctl enable islandr
systemctl start islandr

# Kurz warten bis Boot durch ist
sleep 4

systemctl --no-pager status islandr || true

echo ""
echo "Fertig. Logs verfolgen mit:"
echo "  sudo journalctl -u islandr -f"
echo ""
echo "Smoke-Test vom Mac aus:"
echo "  ssh -L 8080:127.0.0.1:8080 USER@HOST"
echo "  # dann auf dem Mac: http://localhost:8080"
