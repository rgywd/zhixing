# Zhixing SearXNG deployment

This directory owns the private SearXNG deployment used by Zhixing. The production target is
`syyl1795`; runtime files live at `/opt/zhixing-searxng` and are not copied back into Git.

## Runtime contract

- Public endpoint: `https://searxng.47.85.179.5.sslip.io/search` on the existing TCP 443 edge.
- The gateway requires HTTP Basic Auth. Credentials are generated on the server and kept in
  `/opt/zhixing-searxng/client-credentials.env` with mode `0600`; scripts never print them.
- The SearXNG core and rate-limiting gateway have no host ports. The existing `agent-memory-edge` selects the SearXNG
  certificate and route by SNI, then forwards only this hostname to the private gateway.
- The gateway allows only `GET /search` and the unauthenticated `GET /healthz` probe. Other paths return 404.
- The authoritative global sustained rate is 20 requests per minute. Nginx uses a leaky bucket with a burst debt of at
  most five close-together requests, returns 429 when the bucket is full, and permits at most two in-flight searches.
  This is a bounded rate limit rather than a fixed calendar-minute quota. Changing an Android client cannot bypass
  these server-side limits.
- Access logs omit query strings. Neither Android nor the gateway logs search terms or upstream response bodies.
- Search uses moderate SafeSearch. JSON and HTML are enabled internally; the public gateway exposes only the JSON
  search endpoint.

## First deployment

1. Copy this directory to `/opt/zhixing-searxng` and run `scripts/bootstrap-secrets.sh` there.
2. Start `core` and `acme` with `docker compose up -d core acme`.
3. Add `acme/cpa-location.conf` inside the existing port-80 CPA Nginx `server` block. Back up the original file,
   validate with `nginx -t`, and reload it. Never restart or replace the CPA stack for this change.
4. Run `scripts/issue-certificate.sh`. It obtains a Let's Encrypt certificate through the narrow ACME route and then
   starts the private HTTPS gateway.
5. Connect `agent-memory-edge` to the `zhixing-searxng_search` network, mount `edge/searxng.conf` and the Let's Encrypt
   directory in its owning start script, and include `/etc/nginx/searxng/*.conf` from its `http` block. Validate and
   reload Nginx; do not restart the existing edge for the initial change.
6. Install the two files under `systemd/`, enable `zhixing-searxng-cert-renew.timer`, and run `scripts/smoke-test.sh`.

The official SearXNG container, Nginx, and Certbot images are pinned by tag and manifest digest. Review upstream release
notes and repeat smoke/rate-limit validation before changing a digest.

## Android configuration

Create or edit a SearXNG search service with:

- URL: `https://searxng.47.85.179.5.sslip.io`
- Engines: blank, so the server chooses engines appropriate to web or image categories
- Language: blank for automatic language selection, or `zh-CN`
- Username and password: values from the server-only `client-credentials.env`

SearXNG image search sends `categories=images`. AnySearch remains available for web search and extraction but is not an
image provider because its `resource.image` tag is a stock/photo-library corpus rather than general network imagery.

## Validation and rollback

Run:

```sh
docker compose config --quiet
docker compose ps
scripts/smoke-test.sh
scripts/rate-limit-test.sh
```

The rate probe makes eight sequential authenticated requests without a query. That makes SearXNG reject accepted
requests immediately while the gateway independently returns 429 once its leaky bucket is full. Run functional smoke
tests before this probe because the rejection test intentionally consumes the current rate budget.

Rollback is independent of the Android release: restore the timestamped edge and `/opt/cpa/nginx.conf` backups, validate
and reload both Nginx containers, disconnect the edge from `zhixing-searxng_search`, disable the renewal timer, then run
`docker compose down` in `/opt/zhixing-searxng`. Certificates, credentials, and cache remain on disk unless an operator
explicitly removes them.
