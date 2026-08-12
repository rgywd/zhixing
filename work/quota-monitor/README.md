# CPA Quota Monitor

This service discovers the current credential inventory from CPA, collects provider quota windows, and exposes the
normalized `quota-monitor/v1` contract consumed by Work Core. It is the source-owned copy of the service deployed at
`/opt/cpa-quota-monitor/app`.

Current quota state and history are deliberately separate:

- `snapshots` is append-only history retained for auditing.
- `current_credentials` is atomically replaced after every successful CPA inventory refresh.
- `/v1/quotas` and `/v1/summary` only join snapshots that remain in `current_credentials`.

This means adding, removing, disabling, or re-enabling a CPA credential is reflected after the next poll without
deleting historical samples or requiring an Android update.

Run the deterministic tests from this directory:

```bash
python -m unittest discover -s tests -p "test_*.py" -v
```

Production configuration is supplied through environment variables. Never commit the monitor API token, CPA
management key, SQLite database, or provider credentials.
