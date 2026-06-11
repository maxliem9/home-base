#!/usr/bin/env bash
# Pull the latest images from GHCR and (re)start the stack. Run from the project
# folder on the NAS. May need sudo there:  sudo ./scripts/deploy.sh
set -euo pipefail
cd "$(dirname "$0")/.."

command -v docker >/dev/null || { echo "✗ docker not found" >&2; exit 1; }
[[ -f .env ]] || { echo "✗ no .env — run scripts/setup-env.sh first." >&2; exit 1; }

# Back up the DB (+ uploads) while the OLD stack is still up, so a fresh dump
# exists if this deploy goes wrong. backup.sh needs the db container running and
# uses `set -e`, so on the very first deploy (nothing up yet) it fails — that's
# expected: warn loudly and continue rather than blocking the initial bring-up.
if docker compose ps --status running db 2>/dev/null | grep -q db; then
  echo "↳ backing up database before deploy…"
  if ! scripts/backup.sh; then
    echo "⚠ backup FAILED — continuing with deploy anyway. Check ./backups/ !" >&2
  fi
else
  echo "⚠ db container not running — skipping pre-deploy backup (first deploy?)." >&2
fi

echo "↳ pulling images from GHCR…"
docker compose pull

# The backend now runs as non-root (uid 10001, see backend/Dockerfile). A volume
# created by an older root-running image still owns its files as root, so fix the
# uploads ownership before start. Idempotent and quick — safe to run every deploy.
echo "↳ ensuring uploads volume is writable by the non-root backend…"
docker compose run --rm --no-deps --user root --entrypoint chown backend -R 10001:10001 /data/uploads

echo "↳ (re)starting services…"
docker compose up -d
echo
docker compose ps
echo

# Verify the deploy via the public health endpoint. DOMAIN lives in .env (set by
# scripts/setup-env.sh). Empty/unset → skip with a warning so the script still
# works for users who haven't configured it.
# `|| true`: on an existing .env with no DOMAIN= line, grep exits 1 and pipefail +
# set -e would abort the script *after* `up -d` already ran. We want the empty →
# skip branch below, so swallow the no-match.
DOMAIN="$(grep -E '^DOMAIN=' .env 2>/dev/null | head -n1 | cut -d= -f2- || true)"
DOMAIN="${DOMAIN%/}"   # tolerate a trailing slash
if [[ -z "$DOMAIN" ]]; then
  echo "⚠ DOMAIN not set in .env — skipping health check."
  echo "✓ done. Verify manually:  curl -sk https://<your-domain>/api/v1/health"
  exit 0
fi

HEALTH_URL="https://$DOMAIN/api/v1/health"
echo "↳ checking health at $HEALTH_URL …"
# Retry: a cold backend needs time to boot — JVM start + Flyway migrations against
# the freshly-healthy Postgres, which on a first run can take the better part of a
# minute. -fsS makes curl fail on HTTP 4xx/5xx; -k tolerates hairpin/SNI quirks
# (matches the manual hint's -k).
for i in $(seq 1 20); do
  if curl -fsS -k --max-time 5 "$HEALTH_URL" >/dev/null 2>&1; then
    echo "✓ done. Health check passed ($HEALTH_URL)."
    exit 0
  fi
  echo "  ↳ attempt $i/20 not ready yet, retrying in 3s…"
  sleep 3
done

echo "✗ health check FAILED after 20 attempts (~60s): $HEALTH_URL" >&2
echo "  Inspect with:  docker compose ps  &&  docker compose logs --tail=50 backend" >&2
exit 1
