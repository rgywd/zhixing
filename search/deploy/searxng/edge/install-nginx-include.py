#!/usr/bin/env python3
"""Add the SearXNG include to an existing Nginx http block without printing its contents."""

from pathlib import Path
import os
import sys


INCLUDE = "    include /etc/nginx/searxng/*.conf;\n"


def main() -> None:
    if len(sys.argv) != 3:
        raise SystemExit("usage: install-nginx-include.py SOURCE CANDIDATE")

    source = Path(sys.argv[1])
    candidate = Path(sys.argv[2])
    text = source.read_text(encoding="utf-8")

    if INCLUDE in text:
        updated = text
    else:
        final_brace = text.rfind("}")
        if final_brace < 0 or "http {" not in text or text[final_brace + 1 :].strip():
            raise SystemExit("refusing to patch an unexpected Nginx configuration shape")
        updated = text[:final_brace] + INCLUDE + text[final_brace:]

    candidate.write_text(updated, encoding="utf-8")
    os.chmod(candidate, source.stat().st_mode)


if __name__ == "__main__":
    main()
