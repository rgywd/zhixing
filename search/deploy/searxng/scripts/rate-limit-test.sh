#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
. ./.env
# shellcheck disable=SC1091
. ./client-credentials.env

base_url="https://${SEARXNG_DOMAIN}"
resolve="${SEARXNG_DOMAIN}:443:127.0.0.1"
tmp_auth="$(mktemp)"
chmod 600 "$tmp_auth"
printf 'user = "%s:%s"\n' "$SEARXNG_USERNAME" "$SEARXNG_PASSWORD" > "$tmp_auth"
trap 'rm -f "$tmp_auth"' EXIT

codes=""
throttled=0
attempt=1
while [ "$attempt" -le 8 ]; do
    # Omitting q makes the upstream response immediate, so this sequential probe exercises
    # the request-rate bucket rather than the concurrent-search guard.
    code="$(curl --silent --show-error --max-time 5 \
        --output /dev/null --write-out '%{http_code}' \
        --resolve "$resolve" \
        --config "$tmp_auth" \
        "$base_url/search?format=json")"
    codes="${codes}${codes:+,}${code}"
    if [ "$code" = 429 ]; then
        throttled=$((throttled + 1))
    fi
    attempt=$((attempt + 1))
done

printf 'rate probe: statuses=%s throttled=%s\n' "$codes" "$throttled"
[ "$throttled" -ge 1 ]
