#!/bin/sh
set -eu

if [ "$#" -ne 1 ]; then
  echo "usage: ./restore.sh backups/relay-*.sqlite3" >&2
  exit 2
fi

backup_dir="$(cd "$(dirname "$1")" && pwd)"
backup_name="$(basename "$1")"
docker compose stop relay
docker compose run --rm --no-deps \
  --user root \
  --cap-add CHOWN \
  --cap-add DAC_OVERRIDE \
  -v "${backup_dir}:/restore:ro" \
  relay sh -eu -c '
    node dist/scripts/restore.js "/restore/$1" --confirm-stopped
    chown node:node /data/relay.sqlite3
    find /data -maxdepth 1 -name "relay.sqlite3.rollback-*" -exec chown node:node {} +
  ' sh "${backup_name}"
docker compose start relay
container_id="$(docker compose ps -q relay)"
attempt=0
while :; do
  status="$(docker inspect --format '{{.State.Health.Status}}' "${container_id}")"
  [ "${status}" = "healthy" ] && break
  if [ "${status}" = "unhealthy" ] || [ "${attempt}" -ge 30 ]; then
    echo "relay health check failed: ${status}" >&2
    exit 1
  fi
  attempt=$((attempt + 1))
  sleep 1
done
docker compose exec -T relay node -e \
  "fetch('http://127.0.0.1:3100/healthz').then(async r=>{console.log(await r.text());if(!r.ok)process.exit(1)})"
