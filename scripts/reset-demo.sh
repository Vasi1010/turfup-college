#!/usr/bin/env bash
# Wipes the local demo database and reseeds the demo data for showing TurfUp to turf owners:
#   admin 9999999999 / admin-demo-123, owner 8888888888 / owner-demo-123, approved venue "Kick Arena",
#   and today's and tomorrow's bookings there (see backend DemoShowcase). Bookings are placed relative to now,
#   so run it shortly before a demo. --empty seeds the venue without bookings.
# Only touches the docker-compose `postgres` service (port 5432). Stop the backend first.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"

showcase=true
if [ "${1:-}" = "--empty" ]; then
  showcase=false
fi

if curl -s -o /dev/null --max-time 2 http://localhost:8080/health; then
  echo "The backend is running on :8080. Stop it first, then run this again." >&2
  exit 1
fi

echo "Starting Postgres…"
docker compose up -d --wait postgres

echo "Wiping the demo database…"
docker compose exec -T postgres psql -U "${POSTGRES_USER:-turfup}" -d "${POSTGRES_DB:-turfup}" -v ON_ERROR_STOP=1 -q \
  -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"

echo "Migrating and seeding demo data…"
cd backend
JWT_SECRET="seed-only-$(date +%s)-not-used-for-serving-requests" SPRING_PROFILES_ACTIVE=demo TURFUP_DEMO_SHOWCASE="$showcase" \
  ./mvnw -q spring-boot:run -Dspring-boot.run.arguments=--turfup.demo.seed-only

echo
echo "Demo data is fresh:"
echo "  admin  9999999999 / admin-demo-123"
echo "  owner  8888888888 / owner-demo-123  (Kick Arena, Cidco: ₹800/hr before 5 PM, ₹1,000/hr after, ₹200 advance)"
if [ "$showcase" = true ]; then
  echo "  players 7000000001-7000000008 / player-demo-123  (Rahul Patil: regular, advance waived; Sameer Khan: regular)"
else
  echo "  players register in the app"
fi
