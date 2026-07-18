#!/bin/sh
set -eu

stamp="$(date -u +%Y%m%dT%H%M%SZ)"
name="relay-${stamp}.sqlite3"
mkdir -p backups
docker compose exec -T relay node dist/scripts/backup.js "/data/backups/${name}"
docker compose cp "relay:/data/backups/${name}" "backups/${name}"
docker compose cp "relay:/data/backups/${name}.manifest.json" "backups/${name}.manifest.json"
echo "backup ready: backups/${name}"
