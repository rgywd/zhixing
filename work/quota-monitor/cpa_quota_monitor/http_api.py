from __future__ import annotations

import hmac
import json
import logging
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import parse_qs, urlparse

from . import SCHEMA_VERSION
from .service import MonitorService, RefreshBusy


def _error(code: str, message: str) -> dict[str, Any]:
    return {"schema_version": SCHEMA_VERSION, "error": {"code": code, "message": message}}


def make_handler(service: MonitorService, api_token: str, cors_origins: tuple[str, ...] = ()) -> type[BaseHTTPRequestHandler]:
    allowed_origins = set(cors_origins)

    class Handler(BaseHTTPRequestHandler):
        server_version = "CPAQuotaMonitor/1"

        def _origin(self) -> str | None:
            origin = self.headers.get("Origin", "").strip()
            return origin if origin and origin in allowed_origins else None

        def _send(self, status: int, payload: dict[str, Any]) -> None:
            body = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            origin = self._origin()
            if origin:
                self.send_header("Access-Control-Allow-Origin", origin)
                self.send_header("Vary", "Origin")
            self.end_headers()
            self.wfile.write(body)

        def _authorized(self) -> bool:
            header = self.headers.get("Authorization", "")
            if not header.startswith("Bearer "):
                return False
            return hmac.compare_digest(header[7:].strip(), api_token)

        def _require_auth(self) -> bool:
            if self._authorized():
                return True
            self._send(HTTPStatus.UNAUTHORIZED, _error("unauthorized", "valid bearer token required"))
            return False

        def do_OPTIONS(self) -> None:  # noqa: N802
            origin = self._origin()
            if not origin:
                self._send(HTTPStatus.FORBIDDEN, _error("cors_forbidden", "origin is not allowed"))
                return
            self.send_response(HTTPStatus.NO_CONTENT)
            self.send_header("Access-Control-Allow-Origin", origin)
            self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
            self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
            self.send_header("Access-Control-Max-Age", "600")
            self.send_header("Vary", "Origin")
            self.end_headers()

        def do_GET(self) -> None:  # noqa: N802
            parsed = urlparse(self.path)
            if parsed.path == "/healthz":
                self._send(HTTPStatus.OK, service.health())
                return
            if not self._require_auth():
                return
            query = parse_qs(parsed.query)
            if parsed.path == "/v1/quotas":
                self._send(
                    HTTPStatus.OK,
                    service.quota_envelope(
                        provider=(query.get("provider") or [None])[0],
                        state=(query.get("state") or [None])[0],
                    ),
                )
                return
            if parsed.path == "/v1/summary":
                self._send(HTTPStatus.OK, service.summary())
                return
            if parsed.path == "/v1/history":
                try:
                    limit = min(5000, max(1, int((query.get("limit") or ["500"])[0])))
                except ValueError:
                    self._send(HTTPStatus.BAD_REQUEST, _error("invalid_limit", "limit must be an integer"))
                    return
                self._send(
                    HTTPStatus.OK,
                    service.history_envelope(
                        credential_id=(query.get("credential_id") or [None])[0],
                        provider=(query.get("provider") or [None])[0],
                        since=(query.get("since") or [None])[0],
                        until=(query.get("until") or [None])[0],
                        limit=limit,
                    ),
                )
                return
            self._send(HTTPStatus.NOT_FOUND, _error("not_found", "endpoint not found"))

        def do_POST(self) -> None:  # noqa: N802
            parsed = urlparse(self.path)
            if not self._require_auth():
                return
            if parsed.path != "/v1/refresh":
                self._send(HTTPStatus.NOT_FOUND, _error("not_found", "endpoint not found"))
                return
            try:
                payload = service.refresh_all(wait=False)
            except RefreshBusy:
                self._send(HTTPStatus.CONFLICT, _error("refresh_busy", "another refresh is already running"))
                return
            except Exception:
                logging.exception("manual quota refresh failed")
                self._send(HTTPStatus.BAD_GATEWAY, _error("refresh_failed", "quota refresh failed"))
                return
            self._send(HTTPStatus.OK, payload)

        def log_message(self, fmt: str, *args: Any) -> None:
            logging.info("http client=%s message=%s", self.client_address[0], fmt % args)

    return Handler


def create_server(
    host: str,
    port: int,
    service: MonitorService,
    api_token: str,
    cors_origins: tuple[str, ...] = (),
) -> ThreadingHTTPServer:
    return ThreadingHTTPServer((host, port), make_handler(service, api_token, cors_origins))
