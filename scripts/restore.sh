#!/usr/bin/env bash
# Restore a backup produced by backup.sh.  DESTRUCTIVE: overwrites the live
# database and note images. The db dump and images archive of one run share a
# stamp, so you can name the pair by that stamp instead of both paths:
#   scripts/restore.sh <stamp>              # from ./backups/
#   scripts/restore.sh <stamp> <dir>        # from another dir
#   scripts/restore.sh <db-dump.sql> <uploads.tar.gz>   # explicit paths
set -euo pipefail
cd "$(dirname "$0")/.."

USAGE="usage: $0 <stamp> [dir]   |   $0 <db-dump.sql> <uploads.tar.gz>"
if [[ $# -eq 2 && -f "${1:-}" && -f "${2:-}" ]]; then
  DB_DUMP="$1"; UP_TAR="$2"                       # explicit paths
else
  STAMP="${1:-}"; DIR="${2:-backups}"
  [[ -n "$STAMP" ]] || { echo "$USAGE" >&2; exit 1; }   # no stamp → nothing to resolve
  DB_DUMP="$DIR/db-$STAMP.sql"; UP_TAR="$DIR/uploads-$STAMP.tar.gz"
fi
[[ -f "$DB_DUMP" && -f "$UP_TAR" ]] || {
  echo "$USAGE" >&2
  echo "  looked for: $DB_DUMP + $UP_TAR" >&2
  exit 1; }

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
