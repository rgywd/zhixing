#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
. ./.env

docker compose up -d core acme
docker compose --profile maintenance run --rm certbot certonly \
    --webroot \
    --webroot-path /var/www/certbot \
    --domain "$SEARXNG_DOMAIN" \
    --preferred-challenges http \
    --non-interactive \
    --agree-tos \
    --register-unsafely-without-email \
    --keep-until-expiring
docker compose up -d gateway
