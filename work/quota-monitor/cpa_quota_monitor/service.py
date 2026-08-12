from __future__ import annotations

import collections
import logging
import threading
from typing import Any

from . import SCHEMA_VERSION
from .cpa import CPAClient
from .models import utc_now
from .providers import normalize_credential
from .storage import SnapshotStore


class RefreshBusy(RuntimeError):
    pass


class MonitorService:
    def __init__(
        self,
        client: CPAClient,
        store: SnapshotStore,
        *,
        poll_seconds: int = 600,
        retention_days: int = 90,
        stale_after_seconds: int = 1200,
    ):
        self.client = client
        self.store = store
        self.poll_seconds = poll_seconds
        self.retention_days = retention_days
        self.stale_after_seconds = stale_after_seconds
        self._refresh_lock = threading.Lock()
        self._stop_event = threading.Event()
        self._thread: threading.Thread | None = None
        self.last_error: str | None = None

    @property
    def refresh_running(self) -> bool:
        acquired = self._refresh_lock.acquire(blocking=False)
        if acquired:
            self._refresh_lock.release()
            return False
        return True

    def refresh_all(self, *, wait: bool = False) -> dict[str, Any]:
        if not self._refresh_lock.acquire(blocking=wait):
            raise RefreshBusy("refresh already running")
        try:
            checked_at = utc_now()
            credentials = self.client.list_credentials()
            snapshots = [normalize_credential(self.client, item, checked_at) for item in credentials]
            self.store.save_many(snapshots)
            self.store.set_metadata("last_refresh_at", checked_at)
            self.store.cleanup(self.retention_days)
            self.last_error = None
            return self.quota_envelope(items=snapshots)
        except Exception as exc:
            self.last_error = type(exc).__name__
            raise
        finally:
            self._refresh_lock.release()

    def quota_envelope(
        self,
        *,
        provider: str | None = None,
        state: str | None = None,
        items: list[dict[str, Any]] | None = None,
    ) -> dict[str, Any]:
        return {
            "schema_version": SCHEMA_VERSION,
            "generated_at": utc_now(),
            "stale_after_seconds": self.stale_after_seconds,
            "items": items if items is not None else self.store.latest(provider=provider, state=state),
        }

    def history_envelope(self, **filters: Any) -> dict[str, Any]:
        return {
            "schema_version": SCHEMA_VERSION,
            "generated_at": utc_now(),
            "items": self.store.history(**filters),
        }

    def summary(self) -> dict[str, Any]:
        items = self.store.latest()
        states = collections.Counter(str(item.get("state") or "unknown") for item in items)
        providers = collections.Counter(str(item.get("provider") or "unknown") for item in items)
        remaining_values: list[float] = []
        low_credentials: set[str] = set()
        for item in items:
            for quota_window in item.get("windows") or []:
                if not isinstance(quota_window, dict):
                    continue
                value = quota_window.get("remaining_percent")
                if isinstance(value, (int, float)):
                    remaining_values.append(float(value))
                    if value <= 20:
                        low_credentials.add(str(item.get("credential_id")))
        return {
            "schema_version": SCHEMA_VERSION,
            "generated_at": utc_now(),
            "credentials_total": len(items),
            "states": dict(sorted(states.items())),
            "providers": dict(sorted(providers.items())),
            "low_quota_count": len(low_credentials),
            "minimum_remaining_percent": min(remaining_values) if remaining_values else None,
        }

    def health(self) -> dict[str, Any]:
        return {
            "status": "ok" if self.store.ping() else "degraded",
            "schema_version": SCHEMA_VERSION,
            "last_refresh_at": self.store.get_metadata("last_refresh_at"),
            "refresh_running": self.refresh_running,
        }

    def start_background(self) -> None:
        if self._thread and self._thread.is_alive():
            return
        self._stop_event.clear()
        self._thread = threading.Thread(target=self._poll_loop, name="quota-poller", daemon=True)
        self._thread.start()

    def stop_background(self) -> None:
        self._stop_event.set()
        if self._thread:
            self._thread.join(timeout=5)

    def _poll_loop(self) -> None:
        while not self._stop_event.is_set():
            try:
                self.refresh_all(wait=False)
            except RefreshBusy:
                pass
            except Exception:
                logging.exception("quota refresh failed")
            if self._stop_event.wait(self.poll_seconds):
                break
