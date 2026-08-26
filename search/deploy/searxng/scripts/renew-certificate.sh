#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
docker compose --profile maintenance run --rm certbot renew \
    --webroot \
    --webroot-path /var/www/certbot \
    --quiet

if docker compose ps --status running --services | grep -qx gateway; then
    docker compose exec -T gateway nginx -s reload
fi

if docker ps --format '{{.Names}}' | grep -qx agent-memory-edge; then
    if ! docker inspect agent-memory-edge \
        --format '{{range .Mounts}}{{println .Destination}}{{end}}' \
        | grep -qx /etc/nginx/searxng/letsencrypt; then
        # The current edge container predates the persistent mounts. Keep it current without restarting it.
        # shellcheck disable=SC1091
        . ./.env
        live_dir="/etc/nginx/searxng/letsencrypt/live/$SEARXNG_DOMAIN"
        docker exec agent-memory-edge mkdir -p "$live_dir"
        docker cp -L "letsencrypt/live/$SEARXNG_DOMAIN/fullchain.pem" \
            "agent-memory-edge:$live_dir/fullchain.pem"
        docker cp -L "letsencrypt/live/$SEARXNG_DOMAIN/privkey.pem" \
            "agent-memory-edge:$live_dir/privkey.pem"
    fi
    docker exec agent-memory-edge nginx -t
    docker exec agent-memory-edge nginx -s reload
fi
