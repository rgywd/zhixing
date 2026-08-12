from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from typing import Any


class CPAError(RuntimeError):
    def __init__(self, code: str, message: str, *, status: int | None = None, retriable: bool = False):
        super().__init__(message)
        self.code = code
        self.status = status
        self.retriable = retriable


class CPAClient:
    def __init__(self, base_url: str, management_key: str, timeout: float = 70.0):
        self.base_url = base_url.rstrip("/")
        self.management_key = management_key
        self.timeout = timeout

    def _management_request(self, method: str, path: str, payload: dict[str, Any] | None = None) -> Any:
        url = f"{self.base_url}/v0/management/{path.lstrip('/')}"
        data = None if payload is None else json.dumps(payload, separators=(",", ":")).encode("utf-8")
        request = urllib.request.Request(
            url,
            data=data,
            method=method,
            headers={
                "Authorization": f"Bearer {self.management_key}",
                "Content-Type": "application/json",
                "Accept": "application/json",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read()
        except urllib.error.HTTPError as exc:
            status = exc.code
            raise CPAError(
                "cpa_http_error",
                f"CPA management API returned HTTP {status}",
                status=status,
                retriable=status >= 500,
            ) from exc
        except (urllib.error.URLError, TimeoutError, OSError) as exc:
            raise CPAError("cpa_unreachable", "CPA management API is unreachable", retriable=True) from exc
        try:
            return json.loads(raw)
        except json.JSONDecodeError as exc:
            raise CPAError("cpa_invalid_json", "CPA management API returned invalid JSON") from exc

    def list_credentials(self) -> list[dict[str, Any]]:
        payload = self._management_request("GET", "auth-files")
        files = payload.get("files") if isinstance(payload, dict) else None
        if not isinstance(files, list):
            raise CPAError("cpa_invalid_inventory", "CPA credential inventory is malformed")
        return [item for item in files if isinstance(item, dict)]

    def api_call(
        self,
        *,
        auth_index: str,
        method: str,
        url: str,
        headers: dict[str, str],
        data: str = "",
    ) -> dict[str, Any]:
        payload = self._management_request(
            "POST",
            "api-call",
            {
                "auth_index": auth_index,
                "method": method,
                "url": url,
                "header": headers,
                "data": data,
            },
        )
        if not isinstance(payload, dict):
            raise CPAError("cpa_invalid_api_call", "CPA api-call response is malformed")
        return payload

    def api_call_json(
        self,
        *,
        auth_index: str,
        method: str,
        url: str,
        headers: dict[str, str],
        data: str = "",
    ) -> dict[str, Any]:
        payload = self.api_call(
            auth_index=auth_index,
            method=method,
            url=url,
            headers=headers,
            data=data,
        )
        status = payload.get("status_code")
        if not isinstance(status, int):
            raise CPAError("upstream_missing_status", "provider response did not include a status")
        if status < 200 or status >= 300:
            raise CPAError(
                "upstream_http_error",
                f"provider quota endpoint returned HTTP {status}",
                status=status,
                retriable=status == 429 or status >= 500,
            )
        raw = payload.get("body")
        if not isinstance(raw, str):
            raise CPAError("upstream_invalid_body", "provider quota endpoint returned an invalid body")
        try:
            decoded = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise CPAError("upstream_invalid_json", "provider quota endpoint returned invalid JSON") from exc
        if not isinstance(decoded, dict):
            raise CPAError("upstream_invalid_payload", "provider quota payload is not an object")
        return decoded
