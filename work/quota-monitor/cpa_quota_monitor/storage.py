from __future__ import annotations

import json
import sqlite3
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Iterator


class SnapshotStore:
    def __init__(self, path: str):
        self.path = Path(path).expanduser()
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self._initialize()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.path, timeout=15)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA busy_timeout=15000")
        return connection

    @contextmanager
    def _connection(self) -> Iterator[sqlite3.Connection]:
        connection = self._connect()
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    def _initialize(self) -> None:
        with self._connection() as connection:
            connection.execute("PRAGMA journal_mode=WAL")
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS snapshots (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    checked_at TEXT NOT NULL,
                    credential_id TEXT NOT NULL,
                    provider TEXT NOT NULL,
                    state TEXT NOT NULL,
                    payload_json TEXT NOT NULL
                )
                """
            )
            connection.execute(
                "CREATE INDEX IF NOT EXISTS idx_snapshots_credential_id ON snapshots(credential_id, id DESC)"
            )
            connection.execute(
                "CREATE INDEX IF NOT EXISTS idx_snapshots_provider_time ON snapshots(provider, checked_at DESC)"
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS current_credentials (
                    credential_id TEXT PRIMARY KEY,
                    provider TEXT NOT NULL,
                    checked_at TEXT NOT NULL
                )
                """
            )
            connection.execute(
                """
                CREATE TABLE IF NOT EXISTS metadata (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
                """
            )

    def save_many(self, snapshots: list[dict[str, Any]]) -> None:
        rows = [
            (
                str(item["checked_at"]),
                str(item["credential_id"]),
                str(item["provider"]),
                str(item["state"]),
                json.dumps(item, ensure_ascii=False, separators=(",", ":"), sort_keys=True),
            )
            for item in snapshots
        ]
        with self._connection() as connection:
            connection.executemany(
                "INSERT INTO snapshots(checked_at, credential_id, provider, state, payload_json) VALUES (?, ?, ?, ?, ?)",
                rows,
            )
            # History is append-only, but the overview must reflect the latest CPA inventory.
            # Replacing this small membership table atomically keeps deleted credentials out
            # of /v1/quotas without discarding their audit history.
            connection.execute("DELETE FROM current_credentials")
            connection.executemany(
                "INSERT INTO current_credentials(credential_id, provider, checked_at) VALUES (?, ?, ?)",
                [
                    (str(item["credential_id"]), str(item["provider"]), str(item["checked_at"]))
                    for item in snapshots
                ],
            )

    def latest(self, provider: str | None = None, state: str | None = None) -> list[dict[str, Any]]:
        query = """
            SELECT s.payload_json
            FROM current_credentials current
            JOIN (
                SELECT credential_id, MAX(id) AS latest_id
                FROM snapshots
                GROUP BY credential_id
            ) latest ON latest.credential_id = current.credential_id
            JOIN snapshots s ON s.id = latest.latest_id
            ORDER BY s.provider, s.credential_id
        """
        with self._connection() as connection:
            items = [json.loads(row["payload_json"]) for row in connection.execute(query)]
        if provider:
            items = [item for item in items if item.get("provider") == provider]
        if state:
            items = [item for item in items if item.get("state") == state]
        return items

    def history(
        self,
        *,
        credential_id: str | None = None,
        provider: str | None = None,
        since: str | None = None,
        until: str | None = None,
        limit: int = 500,
    ) -> list[dict[str, Any]]:
        clauses: list[str] = []
        params: list[Any] = []
        if credential_id:
            clauses.append("credential_id = ?")
            params.append(credential_id)
        if provider:
            clauses.append("provider = ?")
            params.append(provider)
        if since:
            clauses.append("checked_at >= ?")
            params.append(since)
        if until:
            clauses.append("checked_at <= ?")
            params.append(until)
        where = f"WHERE {' AND '.join(clauses)}" if clauses else ""
        params.append(limit)
        query = f"SELECT payload_json FROM snapshots {where} ORDER BY id DESC LIMIT ?"
        with self._connection() as connection:
            return [json.loads(row["payload_json"]) for row in connection.execute(query, params)]

    def set_metadata(self, key: str, value: str) -> None:
        with self._connection() as connection:
            connection.execute(
                "INSERT INTO metadata(key, value) VALUES (?, ?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
                (key, value),
            )

    def get_metadata(self, key: str) -> str | None:
        with self._connection() as connection:
            row = connection.execute("SELECT value FROM metadata WHERE key = ?", (key,)).fetchone()
        return str(row["value"]) if row else None

    def cleanup(self, retention_days: int) -> int:
        cutoff = (datetime.now(timezone.utc) - timedelta(days=retention_days)).isoformat().replace("+00:00", "Z")
        with self._connection() as connection:
            cursor = connection.execute("DELETE FROM snapshots WHERE checked_at < ?", (cutoff,))
            return cursor.rowcount

    def ping(self) -> bool:
        try:
            with self._connection() as connection:
                return connection.execute("SELECT 1").fetchone()[0] == 1
        except sqlite3.Error:
            return False
