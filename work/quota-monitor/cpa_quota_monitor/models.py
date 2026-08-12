from __future__ import annotations

import hashlib
from datetime import datetime, timezone
from typing import Any


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def iso_timestamp(value: Any) -> str | None:
    if value is None or value == "":
        return None
    if isinstance(value, (int, float)):
        try:
            return datetime.fromtimestamp(float(value), timezone.utc).isoformat().replace("+00:00", "Z")
        except (OverflowError, OSError, ValueError):
            return None
    if not isinstance(value, str):
        return None
    raw = value.strip()
    if not raw:
        return None
    if raw.isdigit():
        return iso_timestamp(int(raw))
    try:
        parsed = datetime.fromisoformat(raw.replace("Z", "+00:00"))
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc).isoformat().replace("+00:00", "Z")


def number(value: Any) -> float | None:
    if value is None or isinstance(value, bool):
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def percent(value: Any) -> float | None:
    parsed = number(value)
    if parsed is None:
        return None
    return round(min(100.0, max(0.0, parsed)), 4)


def ratio_percent(numerator: Any, denominator: Any) -> float | None:
    left = number(numerator)
    right = number(denominator)
    if left is None or right is None or right <= 0:
        return None
    return percent(left / right * 100.0)


def opaque_credential_id(item: dict[str, Any]) -> str:
    provider = str(item.get("provider") or item.get("type") or "unknown").strip().lower()
    identity = item.get("id") or item.get("name") or item.get("auth_index") or item.get("label") or "unknown"
    digest = hashlib.sha256(f"{provider}:{identity}".encode("utf-8")).hexdigest()
    return digest[:20]


def credential_label(item: dict[str, Any]) -> str:
    for key in ("label", "email", "account", "name"):
        value = item.get(key)
        if isinstance(value, str) and value.strip():
            return value.strip()
    return str(item.get("provider") or item.get("type") or "credential")


def window(
    *,
    key: str,
    label: str,
    used: Any = None,
    limit: Any = None,
    remaining: Any = None,
    unit: str = "quota",
    used_percent: Any = None,
    remaining_percent: Any = None,
    reset_at: Any = None,
    window_seconds: Any = None,
) -> dict[str, Any]:
    used_num = number(used)
    limit_num = number(limit)
    remaining_num = number(remaining)
    used_pct = percent(used_percent)
    remaining_pct = percent(remaining_percent)
    if used_pct is None:
        used_pct = ratio_percent(used_num, limit_num)
    if remaining_pct is None:
        remaining_pct = ratio_percent(remaining_num, limit_num)
    if remaining_pct is None and used_pct is not None:
        remaining_pct = round(100.0 - used_pct, 4)
    if used_pct is None and remaining_pct is not None:
        used_pct = round(100.0 - remaining_pct, 4)
    duration = number(window_seconds)
    return {
        "key": key,
        "label": label,
        "used": used_num,
        "limit": limit_num,
        "remaining": remaining_num,
        "unit": unit,
        "used_percent": used_pct,
        "remaining_percent": remaining_pct,
        "reset_at": iso_timestamp(reset_at),
        "window_seconds": int(duration) if duration is not None and duration >= 0 else None,
    }
