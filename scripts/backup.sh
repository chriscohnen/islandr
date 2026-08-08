#!/usr/bin/env bash
# backup.sh — consistent, compressed backup of the Islandr SQLite database.
#
# Usage:
#   sudo bash backup.sh [destination-dir]
#
# Env vars:
#   DB_PATH         path to the live database (default: /var/lib/islandr/data/islandr.db)
#   RETENTION_DAYS  delete local backups older than this many days (default: 14, 0 = keep all)
#
# Requires: sqlite3
# Assumes the standard install layout from docs/install.md.
#
# The DB is copied via `sqlite3 .backup`, not `cp` — that's SQLite's own safe
# hot-backup mechanism (uses the backup API, so a concurrent write from the
# running service can't produce a torn/corrupt copy the way a raw file copy
# could).
#
# The DB contains OIDC client secrets (encrypted at rest only if
# ISLANDR_ENCRYPTION_KEY is configured — see docs/install.md) — backups
# inherit that same sensitivity and are written 0600, owned by the same user
# as the source file.
#
# Cron example (daily at 03:00, keep 14 days locally):
#   0 3 * * * root DB_PATH=/var/lib/islandr/data/islandr.db /opt/islandr/backup.sh
#
# This script only produces local, rotated backups. For off-host retention,
# add a step after it runs — e.g. `rsync`/`scp` the resulting .gz to another
# host, or point `restic backup` at the destination directory instead of
# rolling your own retention logic.

set -euo pipefail

DB_PATH="${DB_PATH:-/var/lib/islandr/data/islandr.db}"
DEST_DIR="${1:-/var/backups/islandr}"
RETENTION_DAYS="${RETENTION_DAYS:-14}"

die()  { printf '\nERROR: %s\n' "$*" >&2; exit 1; }
info() { printf '  %s\n' "$*"; }

command -v sqlite3 &>/dev/null || die "sqlite3 not found — install it first"
[[ -f "$DB_PATH" ]] || die "database not found at ${DB_PATH} (set DB_PATH to override)"

mkdir -p "$DEST_DIR"
chmod 0700 "$DEST_DIR"

OWNER=$(stat -c '%U:%G' "$DB_PATH" 2>/dev/null || stat -f '%Su:%Sg' "$DB_PATH")
TIMESTAMP=$(date +%Y%m%d-%H%M%S)
TMP_FILE="${DEST_DIR}/.islandr-${TIMESTAMP}.db.tmp"
OUT_FILE="${DEST_DIR}/islandr-${TIMESTAMP}.db.gz"

info "Backing up ${DB_PATH} ..."
sqlite3 "$DB_PATH" ".backup '${TMP_FILE}'" \
  || die "sqlite3 .backup failed"

gzip -c "$TMP_FILE" > "$OUT_FILE"
rm -f "$TMP_FILE"
chmod 0600 "$OUT_FILE"
chown "$OWNER" "$OUT_FILE" 2>/dev/null || true

info "Wrote ${OUT_FILE} ($(du -h "$OUT_FILE" | cut -f1))"

if [[ "$RETENTION_DAYS" -gt 0 ]]; then
  DELETED=$(find "$DEST_DIR" -maxdepth 1 -name 'islandr-*.db.gz' -mtime "+${RETENTION_DAYS}" -print -delete | wc -l)
  [[ "$DELETED" -gt 0 ]] && info "Removed ${DELETED} backup(s) older than ${RETENTION_DAYS} days."
fi

info "Done."
