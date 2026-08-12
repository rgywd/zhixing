from __future__ import annotations

import json
import re
from typing import Any, Callable

from .cpa import CPAClient, CPAError
from .models import credential_label, number, opaque_credential_id, percent, utc_now, window


Adapter = Callable[[CPAClient, dict[str, Any]], tuple[str | None, list[dict[str, Any]], dict[str, Any]]]


def _auth_index(item: dict[str, Any]) -> str:
    value = item.get("auth_index") or item.get("authIndex")
    if not isinstance(value, str) or not value.strip():
        raise CPAError("missing_auth_index", "credential is missing auth_index")
    return value.strip()


def _slug(value: str) -> str:
    slug = re.sub(r"[^a-z0-9]+", "-", value.strip().lower()).strip("-")
    return slug or "quota"


def _duration_label(seconds: int | None, fallback: str) -> str:
    if seconds == 18_000:
        return "5-hour limit"
    if seconds == 86_400:
        return "Daily limit"
    if seconds == 604_800:
        return "Weekly limit"
    if seconds is not None and 2_419_200 <= seconds <= 2_678_400:
        return "Monthly limit"
    return fallback


def _time_unit_seconds(duration: Any, unit: Any) -> int | None:
    value = number(duration)
    if value is None:
        return None
    normalized = str(unit or "").upper()
    multiplier = {
        "TIME_UNIT_SECOND": 1,
        "TIME_UNIT_MINUTE": 60,
        "TIME_UNIT_HOUR": 3600,
        "TIME_UNIT_DAY": 86400,
    }.get(normalized)
    return int(value * multiplier) if multiplier else None


def _kimi(client: CPAClient, item: dict[str, Any]) -> tuple[str | None, list[dict[str, Any]], dict[str, Any]]:
    body = client.api_call_json(
        auth_index=_auth_index(item),
        method="GET",
        url="https://api.kimi.com/coding/v1/usages",
        headers={"Authorization": "Bearer $TOKEN$"},
    )
    windows: list[dict[str, Any]] = []
    usage = body.get("usage")
    if isinstance(usage, dict):
        windows.append(
            window(
                key="weekly",
                label="Weekly limit",
                used=usage.get("used"),
                limit=usage.get("limit"),
                remaining=usage.get("remaining"),
                reset_at=usage.get("resetTime") or usage.get("reset_time"),
            )
        )
    limits = body.get("limits")
    if isinstance(limits, list):
        for index, entry in enumerate(limits, 1):
            if not isinstance(entry, dict):
                continue
            detail = entry.get("detail") if isinstance(entry.get("detail"), dict) else entry
            spec = entry.get("window") if isinstance(entry.get("window"), dict) else {}
            seconds = _time_unit_seconds(spec.get("duration"), spec.get("timeUnit") or spec.get("time_unit"))
            windows.append(
                window(
                    key=f"window-{seconds or index}",
                    label=_duration_label(seconds, f"Limit #{index}"),
                    used=detail.get("used"),
                    limit=detail.get("limit"),
                    remaining=detail.get("remaining"),
                    reset_at=detail.get("resetTime") or detail.get("reset_time"),
                    window_seconds=seconds,
                )
            )
    parallel = body.get("parallel") if isinstance(body.get("parallel"), dict) else {}
    metadata = {"parallel_limit": number(parallel.get("limit"))}
    return str(body.get("subType") or body.get("sub_type") or "") or None, windows, metadata


def _xai(client: CPAClient, item: dict[str, Any]) -> tuple[str | None, list[dict[str, Any]], dict[str, Any]]:
    headers = {
        "Authorization": "Bearer $TOKEN$",
        "x-xai-token-auth": "xai-grok-cli",
        "x-grok-client-version": "0.2.91",
        "accept": "*/*",
        "user-agent": "grok-pager/0.2.91 grok-shell/0.2.91 (macos; aarch64)",
    }
    billing = client.api_call_json(
        auth_index=_auth_index(item),
        method="GET",
        url="https://cli-chat-proxy.grok.com/v1/billing",
        headers=headers,
    ).get("config", {})
    credits = client.api_call_json(
        auth_index=_auth_index(item),
        method="GET",
        url="https://cli-chat-proxy.grok.com/v1/billing?format=credits",
        headers=headers,
    ).get("config", {})
    if not isinstance(billing, dict):
        billing = {}
    if not isinstance(credits, dict):
        credits = {}
    limit_cents = number((billing.get("monthlyLimit") or {}).get("val") if isinstance(billing.get("monthlyLimit"), dict) else None)
    used_cents = number((billing.get("used") or {}).get("val") if isinstance(billing.get("used"), dict) else None)
    windows: list[dict[str, Any]] = []
    if limit_cents is not None or used_cents is not None:
        limit = limit_cents / 100.0 if limit_cents is not None else None
        used = used_cents / 100.0 if used_cents is not None else None
        remaining = max(0.0, limit - used) if limit is not None and used is not None else None
        windows.append(
            window(
                key="monthly-credits",
                label="Monthly credits",
                used=used,
                limit=limit,
                remaining=remaining,
                unit="USD",
                reset_at=billing.get("billingPeriodEnd"),
            )
        )
    prepaid = credits.get("prepaidBalance") if isinstance(credits.get("prepaidBalance"), dict) else {}
    on_demand_cap = credits.get("onDemandCap") if isinstance(credits.get("onDemandCap"), dict) else {}
    on_demand_used = credits.get("onDemandUsed") if isinstance(credits.get("onDemandUsed"), dict) else {}
    current_period = credits.get("currentPeriod") if isinstance(credits.get("currentPeriod"), dict) else {}
    metadata = {
        "period_type": current_period.get("type"),
        "period_start": current_period.get("start") or credits.get("billingPeriodStart"),
        "period_end": current_period.get("end") or credits.get("billingPeriodEnd"),
        "prepaid_balance": (number(prepaid.get("val")) / 100.0) if number(prepaid.get("val")) is not None else None,
        "on_demand_cap": (number(on_demand_cap.get("val")) / 100.0) if number(on_demand_cap.get("val")) is not None else None,
        "on_demand_used": (number(on_demand_used.get("val")) / 100.0) if number(on_demand_used.get("val")) is not None else None,
        "currency": "USD",
    }
    plan = item.get("account_type")
    return str(plan).strip() if plan else None, windows, metadata


def _codex_window(key: str, label: str, payload: Any) -> dict[str, Any] | None:
    if not isinstance(payload, dict):
        return None
    used = percent(payload.get("used_percent") if "used_percent" in payload else payload.get("usedPercent"))
    seconds_raw = payload.get("limit_window_seconds") if "limit_window_seconds" in payload else payload.get("limitWindowSeconds")
    seconds_num = number(seconds_raw)
    seconds = int(seconds_num) if seconds_num is not None else None
    reset = payload.get("reset_at") if "reset_at" in payload else payload.get("resetAt")
    return window(
        key=key,
        label=_duration_label(seconds, label),
        used=used,
        limit=100,
        remaining=(100.0 - used) if used is not None else None,
        unit="percent",
        used_percent=used,
        reset_at=reset,
        window_seconds=seconds,
    )


def _codex_group(prefix: str, label: str, payload: Any) -> list[dict[str, Any]]:
    if not isinstance(payload, dict):
        return []
    result: list[dict[str, Any]] = []
    for suffix, fallback in (("primary_window", "Primary limit"), ("secondary_window", "Secondary limit")):
        entry = payload.get(suffix) or payload.get("".join([suffix.split("_")[0], suffix.split("_")[1].title()]))
        parsed = _codex_window(f"{prefix}-{suffix.replace('_window', '')}", f"{label} {fallback}", entry)
        if parsed:
            result.append(parsed)
    return result


def _codex(client: CPAClient, item: dict[str, Any]) -> tuple[str | None, list[dict[str, Any]], dict[str, Any]]:
    headers = {
        "Authorization": "Bearer $TOKEN$",
        "Content-Type": "application/json",
        "User-Agent": "codex_cli_rs/0.76.0",
    }
    account = item.get("account")
    if isinstance(account, str) and account.strip():
        headers["Chatgpt-Account-Id"] = account.strip()
    body = client.api_call_json(
        auth_index=_auth_index(item),
        method="GET",
        url="https://chatgpt.com/backend-api/wham/usage",
        headers=headers,
    )
    windows = _codex_group("code", "Code", body.get("rate_limit") or body.get("rateLimit"))
    windows += _codex_group(
        "code-review",
        "Code review",
        body.get("code_review_rate_limit") or body.get("codeReviewRateLimit"),
    )
    additional = body.get("additional_rate_limits") or body.get("additionalRateLimits")
    if isinstance(additional, list):
        for index, entry in enumerate(additional, 1):
            if not isinstance(entry, dict):
                continue
            name = str(entry.get("limit_name") or entry.get("limitName") or f"Additional {index}")
            windows += _codex_group(f"additional-{_slug(name)}", name, entry.get("rate_limit") or entry.get("rateLimit"))
    metadata = {"subscription_active_until": body.get("subscription_active_until") or body.get("subscriptionActiveUntil")}
    plan = body.get("plan_type") or body.get("planType") or item.get("account_type")
    return str(plan).strip() if plan else None, windows, metadata


def _claude(client: CPAClient, item: dict[str, Any]) -> tuple[str | None, list[dict[str, Any]], dict[str, Any]]:
    body = client.api_call_json(
        auth_index=_auth_index(item),
        method="GET",
        url="https://api.anthropic.com/api/oauth/usage",
        headers={
            "Authorization": "Bearer $TOKEN$",
            "Content-Type": "application/json",
            "anthropic-beta": "oauth-2025-04-20",
        },
    )
    known = {
        "five_hour": ("five-hour", "5-hour limit", 18_000),
        "seven_day": ("seven-day", "7-day limit", 604_800),
        "seven_day_oauth_apps": ("seven-day-oauth-apps", "7-day OAuth apps", 604_800),
        "seven_day_opus": ("seven-day-opus", "7-day Opus", 604_800),
        "seven_day_sonnet": ("seven-day-sonnet", "7-day Sonnet", 604_800),
        "seven_day_cowork": ("seven-day-cowork", "7-day Cowork", 604_800),
        "iguana_necktie": ("iguana-necktie", "Additional limit", None),
    }
    windows: list[dict[str, Any]] = []
    for field, (key, label, seconds) in known.items():
        entry = body.get(field)
        if not isinstance(entry, dict):
            continue
        used = percent(entry.get("utilization"))
        windows.append(
            window(
                key=key,
                label=label,
                used=used,
                limit=100,
                remaining=(100.0 - used) if used is not None else None,
                unit="percent",
                used_percent=used,
                reset_at=entry.get("resets_at") or entry.get("resetsAt"),
                window_seconds=seconds,
            )
        )
    plan = body.get("plan_type") or body.get("planType") or item.get("account_type")
    return str(plan).strip() if plan else None, windows, {}


def _antigravity(client: CPAClient, item: dict[str, Any]) -> tuple[str | None, list[dict[str, Any]], dict[str, Any]]:
    project = item.get("project_id") or item.get("projectId")
    if not isinstance(project, str) or not project.strip():
        raise CPAError("missing_project_id", "Antigravity credential is missing project_id")
    headers = {
        "Authorization": "Bearer $TOKEN$",
        "Content-Type": "application/json",
        "User-Agent": "antigravity/cli/1.0.13 (aidev_client; os_type=darwin; arch=arm64)",
    }
    endpoints = [
        "https://daily-cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary",
        "https://daily-cloudcode-pa.sandbox.googleapis.com/v1internal:retrieveUserQuotaSummary",
        "https://cloudcode-pa.googleapis.com/v1internal:retrieveUserQuotaSummary",
    ]
    body: dict[str, Any] | None = None
    last_error: CPAError | None = None
    for url in endpoints:
        try:
            body = client.api_call_json(
                auth_index=_auth_index(item),
                method="POST",
                url=url,
                headers=headers,
                data=json.dumps({"project": project}, separators=(",", ":")),
            )
            break
        except CPAError as exc:
            last_error = exc
    if body is None:
        raise last_error or CPAError("antigravity_quota_failed", "Antigravity quota request failed")
    groups = body.get("groups") if isinstance(body.get("groups"), list) else []
    if not groups and isinstance(body.get("quotaGroups"), list):
        groups = body["quotaGroups"]
    windows: list[dict[str, Any]] = []
    for group_index, group in enumerate(groups, 1):
        if not isinstance(group, dict):
            continue
        group_label = str(group.get("displayName") or group.get("display_name") or group.get("name") or f"Quota group {group_index}")
        buckets = group.get("buckets") if isinstance(group.get("buckets"), list) else []
        for bucket_index, bucket in enumerate(buckets, 1):
            if not isinstance(bucket, dict):
                continue
            fraction = number(bucket.get("remainingFraction") if "remainingFraction" in bucket else bucket.get("remaining_fraction"))
            remaining_pct = fraction * 100.0 if fraction is not None and fraction <= 1 else fraction
            label = str(bucket.get("displayName") or bucket.get("display_name") or group_label)
            windows.append(
                window(
                    key=f"{_slug(group_label)}-{bucket_index}",
                    label=label,
                    used=(100.0 - remaining_pct) if remaining_pct is not None else None,
                    limit=100,
                    remaining=remaining_pct,
                    unit="percent",
                    remaining_percent=remaining_pct,
                    reset_at=bucket.get("resetTime") or bucket.get("reset_time"),
                )
            )
    plan = body.get("plan") or body.get("subscription") or item.get("account_type")
    if isinstance(plan, dict):
        plan = plan.get("name") or plan.get("tier")
    return str(plan).strip() if plan else None, windows, {"group_count": len(groups)}


ADAPTERS: dict[str, Adapter] = {
    "antigravity": _antigravity,
    "claude": _claude,
    "anthropic": _claude,
    "codex": _codex,
    "kimi": _kimi,
    "xai": _xai,
}


def normalize_credential(client: CPAClient, item: dict[str, Any], checked_at: str | None = None) -> dict[str, Any]:
    checked_at = checked_at or utc_now()
    provider = str(item.get("provider") or item.get("type") or "unknown").strip().lower() or "unknown"
    base = {
        "credential_id": opaque_credential_id(item),
        "provider": provider,
        "label": credential_label(item),
        "state": "ok",
        "source_status": str(item.get("status") or "unknown"),
        "plan": None,
        "windows": [],
        "metadata": {},
        "checked_at": checked_at,
        "error": None,
    }
    if bool(item.get("disabled")):
        base["state"] = "disabled"
        return base
    if bool(item.get("unavailable")):
        base["state"] = "unavailable"
        return base
    adapter = ADAPTERS.get(provider)
    if adapter is None:
        base["state"] = "unsupported"
        base["error"] = {"code": "unsupported_provider", "message": f"provider {provider} has no quota adapter", "retriable": False}
        return base
    try:
        plan, windows, metadata = adapter(client, item)
        base["plan"] = plan
        base["windows"] = windows
        base["metadata"] = {key: value for key, value in metadata.items() if value is not None}
    except CPAError as exc:
        base["state"] = "error"
        base["error"] = {"code": exc.code, "message": str(exc), "retriable": exc.retriable}
    except Exception:
        base["state"] = "error"
        base["error"] = {"code": "adapter_error", "message": "provider quota adapter failed", "retriable": False}
    return base
