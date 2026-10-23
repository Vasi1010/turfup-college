#!/usr/bin/env bash
# Creates .env.prod on the server with freshly generated secrets:
#   scripts/new-env-prod.sh turfup.in 9876543210
# arguments: the domain (its DNS must point at this server) and your own phone (register it in the app as a player;
# it becomes the admin). Never overwrites an existing .env.prod; never commit the file.
set -euo pipefail

DOMAIN="${1:?usage: scripts/new-env-prod.sh <domain> <admin phone>}"
ADMIN_PHONE="${2:?usage: scripts/new-env-prod.sh <domain> <admin phone>}"
[ -e .env.prod ] && { echo ".env.prod already exists; edit it instead." >&2; exit 1; }
[[ "$ADMIN_PHONE" =~ ^[6-9][0-9]{9}$ ]] || { echo "Admin phone must be a 10-digit Indian mobile number." >&2; exit 1; }

secret() { openssl rand -base64 "$1" | tr -d '\n/+='; }

sed -e "s|^DOMAIN=.*|DOMAIN=$DOMAIN|" \
    -e "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$(secret 32)|" \
    -e "s|^JWT_SECRET=.*|JWT_SECRET=$(secret 48)|" \
    -e "s|^TURFUP_ADMIN_PHONE=.*|TURFUP_ADMIN_PHONE=$ADMIN_PHONE|" \
    .env.prod.example > .env.prod
chmod 600 .env.prod
echo "Wrote .env.prod for $DOMAIN (admin $ADMIN_PHONE). Payment settings are in it too; see docs/go-live.md."
