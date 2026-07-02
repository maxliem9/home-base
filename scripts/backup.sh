#!/usr/bin/env bash
# Back up everything that persists:
#   1. the Postgres database (logical dump, with DROPs so it restores cleanly)
#   2. the uploaded note images (the `uploads` volume — NOT in the DB dump!)
# Output goes to ./backups/ (override dir: scripts/backup.sh <dir>).
# The DB dump and the images archive share ONE timestamp so a pair always
# belongs together. Override that stamp to line up with an external run:
#   scripts/backup.sh [dir] [stamp]      # positional
#   STAMP=20260701-020000 scripts/backup.sh   # or via env
# After a successful run, old backup sets are pruned so only the newest
# KEEP (default 5) remain. Disable pruning with KEEP=0.
set -euo pipefail
cd "$(dirname "$0")/.."

command -v docker >/dev/null || { echo "✗ docker not found" >&2; exit 1; }
DB_USER="$(grep -E '^DB_USER=' .env 2>/dev/null | head -n1 | cut -d= -f2-)"; DB_USER="${DB_USER:-homebase}"

OUT="${1:-backups}"; mkdir -p "$OUT"
STAMP="${2:-${STAMP:-$(date +%Y%m%d-%H%M%S)}}"
[[ "$STAMP" =~ ^[A-Za-z0-9._-]+$ ]] || { echo "✗ invalid stamp: '$STAMP'" >&2; exit 1; }

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

# Retention: keep only the newest KEEP backup sets (a set = the db dump +
# its matching uploads archive, paired by stamp). Prune runs last, so a
# failure above never deletes an existing backup. KEEP=0 disables it.
KEEP="${KEEP:-5}"
if [[ "$KEEP" =~ ^[0-9]+$ && "$KEEP" -gt 0 ]]; then
  ( ls -t "$OUT"/db-*.sql 2>/dev/null || true ) | tail -n +"$((KEEP + 1))" \
  | while IFS= read -r f; do
      s="${f#"$OUT"/db-}"; s="${s%.sql}"   # recover the stamp from the filename
      rm -f -- "$f" "$OUT/uploads-$s.tar.gz"
      echo "  ✗ pruned old backup $s"
    done
fi
