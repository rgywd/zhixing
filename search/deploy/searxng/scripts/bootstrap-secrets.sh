#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
umask 077

domain="${SEARXNG_DOMAIN:-searxng.47.85.179.5.sslip.io}"
rate_per_minute="${SEARXNG_RATE_PER_MINUTE:-20}"
burst="${SEARXNG_BURST:-5}"
max_concurrent="${SEARXNG_MAX_CONCURRENT:-2}"

mkdir -p auth letsencrypt acme/www
chmod 755 acme/www
printf 'ok\n' > acme/www/healthz
chmod 644 acme/www/healthz

if [ ! -f .env ]; then
    secret="$(openssl rand -hex 32)"
    {
        printf 'SEARXNG_DOMAIN=%s\n' "$domain"
        printf 'SEARXNG_RATE_PER_MINUTE=%s\n' "$rate_per_minute"
        printf 'SEARXNG_BURST=%s\n' "$burst"
        printf 'SEARXNG_MAX_CONCURRENT=%s\n' "$max_concurrent"
        printf 'SEARXNG_SECRET=%s\n' "$secret"
    } > .env
fi

if [ ! -f client-credentials.env ]; then
    username=zhixing
    password="$(openssl rand -hex 24)"
    {
        printf 'SEARXNG_USERNAME=%s\n' "$username"
        printf 'SEARXNG_PASSWORD=%s\n' "$password"
    } > client-credentials.env
fi

if [ ! -f auth/htpasswd ]; then
    # shellcheck disable=SC1091
    . ./client-credentials.env
    password_hash="$(printf '%s\n' "$SEARXNG_PASSWORD" | openssl passwd -apr1 -stdin)"
    printf '%s:%s\n' "$SEARXNG_USERNAME" "$password_hash" > auth/htpasswd
fi

chmod 600 .env client-credentials.env
chown 0:101 auth/htpasswd
chmod 640 auth/htpasswd
printf '%s\n' 'SearXNG runtime secrets are ready; no credential values were printed.'
