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
  -v "${backup_dir}:/restore:ro" \
  relay node dist/scripts/restore.js "/restore/${backup_name}" --confirm-stopped
docker compose start relay
docker compose exec -T relay node -e \
  "fetch('http://127.0.0.1:3100/healthz').then(async r=>{console.log(await r.text());if(!r.ok)process.exit(1)})"
