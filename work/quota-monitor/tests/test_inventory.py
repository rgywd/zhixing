from __future__ import annotations

import tempfile
import unittest
from pathlib import Path

from cpa_quota_monitor.storage import SnapshotStore


def snapshot(credential_id: str, provider: str, checked_at: str) -> dict[str, object]:
    return {
        "credential_id": credential_id,
        "provider": provider,
        "label": credential_id,
        "state": "ok",
        "source_status": "active",
        "plan": None,
        "windows": [],
        "metadata": {},
        "checked_at": checked_at,
        "error": None,
    }


class SnapshotStoreInventoryTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.db_path = str(Path(self.temporary.name) / "quota.sqlite3")
        self.store = SnapshotStore(self.db_path)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def test_latest_only_returns_credentials_in_current_inventory(self) -> None:
        self.store.save_many(
            [
                snapshot("codex-1", "codex", "2026-08-12T08:00:00Z"),
                snapshot("antigravity-1", "antigravity", "2026-08-12T08:00:00Z"),
            ]
        )

        self.store.save_many([snapshot("codex-1", "codex", "2026-08-12T08:10:00Z")])

        self.assertEqual(["codex-1"], [item["credential_id"] for item in self.store.latest()])
        self.assertEqual(
            ["antigravity-1"],
            [item["credential_id"] for item in self.store.history(credential_id="antigravity-1")],
        )

    def test_current_inventory_survives_store_restart(self) -> None:
        self.store.save_many([snapshot("kimi-1", "kimi", "2026-08-12T08:00:00Z")])

        reopened = SnapshotStore(self.db_path)

        self.assertEqual(["kimi-1"], [item["credential_id"] for item in reopened.latest()])

    def test_empty_inventory_clears_overview_without_deleting_history(self) -> None:
        self.store.save_many([snapshot("xai-1", "xai", "2026-08-12T08:00:00Z")])

        self.store.save_many([])

        self.assertEqual([], self.store.latest())
        self.assertEqual(1, len(self.store.history(credential_id="xai-1")))


if __name__ == "__main__":
    unittest.main()
