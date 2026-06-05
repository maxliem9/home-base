#!/usr/bin/env bash
# Back up everything that persists:
#   1. the Postgres database (logical dump, with DROPs so it restores cleanly)
#   2. the uploaded note images (the `uploads` volume — NOT in the DB dump!)
# Output goes to ./backups/ (override: scripts/backup.sh <dir>).
set -euo pipefail
cd "$(dirname "$0")/.."

command -v docker >/dev/null || { echo "✗ docker not found" >&2; exit 1; }
DB_USER="$(grep -E '^DB_USER=' .env 2>/dev/null | head -n1 | cut -d= -f2-)"; DB_USER="${DB_USER:-homebase}"

OUT="${1:-backups}"; mkdir -p "$OUT"
STAMP="$(date +%Y%m%d-%H%M%S)"

echo "↳ dumping database…"
docker compose exec -T db pg_dump --clean --if-exists -U "$DB_USER" homebase > "$OUT/db-$STAMP.sql"

echo "↳ archiving note images…"
PROJECT="${COMPOSE_PROJECT_NAME:-$(basename "$PWD")}"
VOL="$(docker volume ls -q \
  --filter "label=com.docker.compose.project=$PROJECT" \
  --filter "label=com.docker.compose.volume=uploads" | head -n1)"
VOL="${VOL:-${PROJECT}_uploads}"
docker run --rm -v "$VOL:/data:ro" -v "$PWD/$OUT:/backup" alpine \
  tar czf "/backup/uploads-$STAMP.tar.gz" -C /data .

echo "✓ backup complete:"
ls -lh "$OUT/db-$STAMP.sql" "$OUT/uploads-$STAMP.tar.gz"
