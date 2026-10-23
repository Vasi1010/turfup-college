#!/usr/bin/env bash
# Restores a production database backup (made by deploy/backup.sh) over the current data, on the server:
#   scripts/restore-backup.sh backups/turfup-20261021-0230.dump
# Stops the backend so nothing writes during the restore, replaces every table, function and constraint with the
# backup's, then starts the backend again. Run from the repository root, where .env.prod lives.
set -euo pipefail

FILE="${1:?usage: scripts/restore-backup.sh backups/turfup-YYYYMMDD-HHMM.dump}"
[ -s "$FILE" ] || { echo "No such backup file: $FILE" >&2; exit 1; }
COMPOSE=(docker compose -f docker-compose.prod.yml --env-file .env.prod)

echo "This replaces ALL production data with $FILE."
read -r -p "Type RESTORE to continue: " answer
[ "$answer" = "RESTORE" ] || { echo "Cancelled."; exit 1; }

"${COMPOSE[@]}" stop backend
# --clean --if-exists drops each object before recreating it, so this works over a database that already has data.
"${COMPOSE[@]}" exec -T postgres sh -c \
  'pg_restore --clean --if-exists --no-owner --exit-on-error -U "$POSTGRES_USER" -d "$POSTGRES_DB"' < "$FILE"
"${COMPOSE[@]}" start backend
echo "Restored $FILE. The backend is starting; check https://\$DOMAIN/health in a minute."
