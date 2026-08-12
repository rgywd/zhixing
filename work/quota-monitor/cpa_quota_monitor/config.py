from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Config:
    cpa_base_url: str
    cpa_management_key: str
    api_token: str
    db_path: str
    listen_host: str = "127.0.0.1"
    listen_port: int = 8322
    poll_seconds: int = 600
    retention_days: int = 90
    stale_after_seconds: int = 1200
    cpa_timeout_seconds: float = 70.0
    cors_origins: tuple[str, ...] = ()


def _required(name: str) -> str:
    value = os.environ.get(name, "").strip()
    if not value:
        raise ValueError(f"{name} is required")
    return value


def _integer(name: str, default: int, minimum: int, maximum: int) -> int:
    raw = os.environ.get(name, str(default)).strip()
    try:
        value = int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be an integer") from exc
    if value < minimum or value > maximum:
        raise ValueError(f"{name} must be between {minimum} and {maximum}")
    return value


def load_config() -> Config:
    token = _required("MONITOR_API_TOKEN")
    if len(token) < 24:
        raise ValueError("MONITOR_API_TOKEN must contain at least 24 characters")
    management_key = _required("MONITOR_CPA_MANAGEMENT_KEY")
    db_default = str(Path.home() / ".local/share/cpa-quota-monitor/quota-monitor.sqlite3")
    origins = tuple(origin.strip() for origin in os.environ.get("MONITOR_CORS_ORIGINS", "").split(",") if origin.strip())
    return Config(
        cpa_base_url=os.environ.get("MONITOR_CPA_BASE_URL", "http://127.0.0.1:8317").strip().rstrip("/"),
        cpa_management_key=management_key,
        api_token=token,
        db_path=os.environ.get("MONITOR_DB_PATH", db_default).strip(),
        listen_host=os.environ.get("MONITOR_LISTEN_HOST", "127.0.0.1").strip(),
        listen_port=_integer("MONITOR_LISTEN_PORT", 8322, 1024, 65535),
        poll_seconds=_integer("MONITOR_POLL_SECONDS", 600, 60, 86400),
        retention_days=_integer("MONITOR_RETENTION_DAYS", 90, 1, 3650),
        stale_after_seconds=_integer("MONITOR_STALE_AFTER_SECONDS", 1200, 60, 604800),
        cpa_timeout_seconds=float(os.environ.get("MONITOR_CPA_TIMEOUT_SECONDS", "70")),
        cors_origins=origins,
    )
