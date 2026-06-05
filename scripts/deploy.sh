#!/usr/bin/env bash
# Pull the latest images from GHCR and (re)start the stack. Run from the project
# folder on the NAS. May need sudo there:  sudo ./scripts/deploy.sh
set -euo pipefail
cd "$(dirname "$0")/.."

command -v docker >/dev/null || { echo "✗ docker not found" >&2; exit 1; }
[[ -f .env ]] || { echo "✗ no .env — run scripts/setup-env.sh first." >&2; exit 1; }

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
echo "✓ done. Verify:  curl -sk https://<your-domain>/api/v1/health"
