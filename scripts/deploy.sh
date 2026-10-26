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

# Verify the deploy via the health endpoint. Default: the web container's localhost
# port — the NAS can't reliably reach its own public/tailnet name (the Synology
# Tailscale package doesn't feed MagicDNS into DSM's resolver, and userspace
# networking can't hairpin to the tailnet IP). Override with HEALTH_URL in .env (or
# the environment) to probe the full path incl. TLS/reverse proxy instead.
# `|| true`: on an .env with no HEALTH_URL= line, grep exits 1 and pipefail + set -e
# would abort the script *after* `up -d` already ran; the default applies instead.
if [[ -z "${HEALTH_URL:-}" ]]; then
  HEALTH_URL="$(grep -E '^HEALTH_URL=' .env 2>/dev/null | head -n1 | cut -d= -f2- || true)"
fi
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:3000/api/v1/health}"

ATTEMPTS=40
INTERVAL=3
echo "↳ checking health at $HEALTH_URL …"
# Retry: a cold backend needs time to boot — JVM start + Flyway migrations against
# the freshly-healthy Postgres (the db healthcheck alone can take up to 30s to turn
# healthy); on the NAS that can take well over a minute, so allow ~120s.
# -fsS makes curl fail on HTTP 4xx/5xx; -k tolerates hairpin/SNI quirks if
# HEALTH_URL points at an https:// name.
for i in $(seq 1 "$ATTEMPTS"); do
  if curl -fsS -k --max-time 5 "$HEALTH_URL" >/dev/null 2>&1; then
    echo "✓ done. Health check passed ($HEALTH_URL)."
    exit 0
  fi
  echo "  ↳ attempt $i/$ATTEMPTS not ready yet, retrying in ${INTERVAL}s…"
  sleep "$INTERVAL"
done

echo "✗ health check FAILED after $ATTEMPTS attempts (~$((ATTEMPTS * INTERVAL))s): $HEALTH_URL" >&2
echo "  Inspect with:  docker compose ps  &&  docker compose logs --tail=50 backend" >&2
exit 1
