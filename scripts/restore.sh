#!/usr/bin/env bash
# Restore a backup produced by backup.sh.  DESTRUCTIVE: overwrites the live
# database and note images. Usage:
#   scripts/restore.sh backups/db-<stamp>.sql backups/uploads-<stamp>.tar.gz
set -euo pipefail
cd "$(dirname "$0")/.."

DB_DUMP="${1:-}"; UP_TAR="${2:-}"
[[ -f "$DB_DUMP" && -f "$UP_TAR" ]] || {
  echo "usage: $0 <db-dump.sql> <uploads.tar.gz>" >&2; exit 1; }

command -v docker >/dev/null || { echo "✗ docker not found" >&2; exit 1; }
DB_USER="$(grep -E '^DB_USER=' .env 2>/dev/null | head -n1 | cut -d= -f2-)"; DB_USER="${DB_USER:-homebase}"

read -rp "This OVERWRITES the live DB and images. Type 'yes' to continue: " C
[[ "$C" == "yes" ]] || { echo "aborted."; exit 1; }

echo "↳ restoring database…"
docker compose exec -T db psql -U "$DB_USER" -d homebase < "$DB_DUMP"

echo "↳ restoring note images…"
PROJECT="${COMPOSE_PROJECT_NAME:-$(basename "$PWD")}"
VOL="$(docker volume ls -q \
  --filter "label=com.docker.compose.project=$PROJECT" \
  --filter "label=com.docker.compose.volume=uploads" | head -n1)"
VOL="${VOL:-${PROJECT}_uploads}"
docker run --rm -v "$VOL:/data" -v "$PWD:/backup" alpine \
  sh -c 'rm -rf /data/* && tar xzf "/backup/'"$UP_TAR"'" -C /data'

echo "✓ restore complete. Apply with:  docker compose restart backend"
