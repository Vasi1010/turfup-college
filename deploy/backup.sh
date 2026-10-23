#!/usr/bin/env bash
# Daily PostgreSQL backup for docker-compose.prod.yml (the `backup` service). Connection settings come from the
# PG* environment variables. Writes /backups/turfup-YYYYMMDD-HHMM.dump (pg_dump custom format, compressed), keeps
# BACKUP_KEEP_DAYS days of them, and runs at 02:30 in TZ (Asia/Kolkata).
#   bash backup.sh          run forever: one backup now if the newest is over a day old, then daily at 02:30
#   bash backup.sh --once   take one backup and exit (used by scripts/restore-backup.sh tests and by hand)
set -euo pipefail

DIR=/backups
KEEP_DAYS="${BACKUP_KEEP_DAYS:-14}"

backup() {
  local name="turfup-$(date +%Y%m%d-%H%M).dump"
  pg_dump --format=custom --compress=9 --no-owner --file "$DIR/$name.partial"
  mv "$DIR/$name.partial" "$DIR/$name"
  find "$DIR" -name 'turfup-*.dump' -mtime +"$KEEP_DAYS" -delete
  echo "$(date '+%F %T') backup written: $name ($(du -h "$DIR/$name" | cut -f1))"
}

mkdir -p "$DIR"
if [ "${1:-}" = "--once" ]; then
  backup
  exit 0
fi

if [ -z "$(find "$DIR" -name 'turfup-*.dump' -mmin -1440 | head -1)" ]; then
  backup || echo "$(date '+%F %T') backup FAILED" >&2
fi
while true; do
  now=$(date +%s)
  next=$(date -d 'today 02:30' +%s)
  [ "$next" -le "$now" ] && next=$(date -d 'tomorrow 02:30' +%s)
  sleep $((next - now))
  backup || echo "$(date '+%F %T') backup FAILED" >&2
done
