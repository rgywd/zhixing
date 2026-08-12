from __future__ import annotations

import argparse
import json
import logging
import signal
import threading

from .config import load_config
from .cpa import CPAClient
from .http_api import create_server
from .service import MonitorService
from .storage import SnapshotStore


def build_service() -> tuple[MonitorService, object]:
    config = load_config()
    client = CPAClient(config.cpa_base_url, config.cpa_management_key, config.cpa_timeout_seconds)
    store = SnapshotStore(config.db_path)
    service = MonitorService(
        client,
        store,
        poll_seconds=config.poll_seconds,
        retention_days=config.retention_days,
        stale_after_seconds=config.stale_after_seconds,
    )
    return service, config


def main() -> int:
    parser = argparse.ArgumentParser(description="Read-only quota monitor for CPA credentials")
    parser.add_argument("--once", action="store_true", help="refresh once, print normalized JSON, and exit")
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    service, config = build_service()
    if args.once:
        print(json.dumps(service.refresh_all(wait=True), ensure_ascii=False, indent=2))
        return 0

    server = create_server(config.listen_host, config.listen_port, service, config.api_token, config.cors_origins)
    service.start_background()
    stopping = threading.Event()

    def stop(_signum: int, _frame: object) -> None:
        if stopping.is_set():
            return
        stopping.set()
        threading.Thread(target=server.shutdown, daemon=True).start()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    logging.info("quota monitor listening on %s:%s", config.listen_host, config.listen_port)
    try:
        server.serve_forever(poll_interval=0.5)
    finally:
        service.stop_background()
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
