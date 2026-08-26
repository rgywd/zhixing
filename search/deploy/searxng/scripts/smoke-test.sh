#!/bin/sh
set -eu

cd "$(dirname "$0")/.."
# shellcheck disable=SC1091
. ./.env
# shellcheck disable=SC1091
. ./client-credentials.env

base_url="https://${SEARXNG_DOMAIN}"
resolve="${SEARXNG_DOMAIN}:443:127.0.0.1"
tmp_web="$(mktemp)"
tmp_images="$(mktemp)"
tmp_auth="$(mktemp)"
chmod 600 "$tmp_auth"
printf 'user = "%s:%s"\n' "$SEARXNG_USERNAME" "$SEARXNG_PASSWORD" > "$tmp_auth"
trap 'rm -f "$tmp_web" "$tmp_images" "$tmp_auth"' EXIT

health_status="$(curl --silent --show-error --resolve "$resolve" \
    --output /dev/null --write-out '%{http_code}' "$base_url/healthz")"
unauthorized_status="$(curl --silent --show-error --output /dev/null --write-out '%{http_code}' \
    --resolve "$resolve" \
    "$base_url/search?q=health&format=json")"
web_status="$(curl --silent --show-error --output "$tmp_web" --write-out '%{http_code}' \
    --resolve "$resolve" \
    --config "$tmp_auth" \
    "$base_url/search?q=SearXNG&format=json")"
image_status="$(curl --silent --show-error --output "$tmp_images" --write-out '%{http_code}' \
    --resolve "$resolve" \
    --config "$tmp_auth" \
    "$base_url/search?q=James+Webb+Space+Telescope&categories=images&format=json")"

[ "$health_status" = 200 ]
[ "$unauthorized_status" = 401 ]
[ "$web_status" = 200 ]
[ "$image_status" = 200 ]

python3 - "$tmp_web" "$tmp_images" <<'PY'
import json
import sys

with open(sys.argv[1], encoding="utf-8") as stream:
    web = json.load(stream)
with open(sys.argv[2], encoding="utf-8") as stream:
    images = json.load(stream)

web_count = len(web.get("results", []))
image_count = sum(
    1 for item in images.get("results", [])
    if item.get("img_src") or item.get("thumbnail_src")
)
if web_count < 1 or image_count < 1:
    raise SystemExit(f"insufficient results: web={web_count}, images={image_count}")
print(f"smoke ok: health=200 auth=401/200 web_results={web_count} image_results={image_count}")
PY
