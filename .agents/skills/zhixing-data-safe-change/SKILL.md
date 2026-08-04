---
name: zhixing-data-safe-change
description: "Safely change zhixing-rikkahub persisted data across Room, DataStore, serialized sealed types or discriminators, Provider retirement, files, import/export, backup, and restore. Use whenever a change can affect existing installations or restored backups. Require both live-store and backup/import migration paths, forbid destructive migration, preserve recoverable originals, and require evidence from a prior public-version upgrade before claiming release safety."
---

# Change Zhixing Data Safely

## Establish the data contract

1. Read repository `AGENTS.md`, `docs/zhixing/README.md`, `docs/zhixing/DATA_SAFETY_AND_BACKUP.md`, and the owning product
   or runtime contract for the affected data.
2. Classify every affected value by its authoritative store: Room, DataStore, app files or Workspace/vault files,
   serialized messages or settings, remote backup, or rebuildable cache/RootFS.
3. State which existing public versions and backup formats must remain readable. Treat current source, migrations,
   fixtures, and a real prior-version artifact as evidence; do not repeat schema or version numbers in this skill.
4. Preserve stable upgrade identity: application ID, production signature, database logical name, and monotonically
   increasing `versionCode`.

## Trace every persisted path

1. Use `rg --files`, `rg`, and symbols to find all writers, readers, serializers, discriminators, defaults, migrations,
   converters, exports, imports, backup manifests, restores, and UI/runtime references for the changed value.
2. Inspect the live-store startup path and the backup/import path separately. Do not assume a migration in one path protects
   the other.
3. Find old JSON, database, settings, and archive fixtures from public versions. Add minimal representative fixtures when
   the repository lacks them, without embedding credentials or real user data.
4. Inventory user-authored files and semantic records separately from rebuildable caches, indexes, and RootFS content.

## Design a recoverable migration

1. Write the old-to-new mapping and failure behavior before deleting or renaming any type, field, table, discriminator,
   Provider, or file path.
2. Migrate persisted representations before removing the code that decodes them. For serialized sealed types or retired
   Providers, rewrite or explicitly handle legacy discriminators in both live DataStore and imported settings/backups;
   never let a deleted subtype make startup or restore crash.
3. Add explicit, continuous Room migrations and matching schema evidence. Never add destructive fallback, silent database
   recreation, or “catch and clear” recovery.
4. Stage file and restore changes as `source -> staging -> validate -> atomic replace`. Retain the original or a rollback
   snapshot until validation succeeds; reject absolute paths, `..`, null bytes, escaping links, corrupt manifests, and
   hash mismatches.
5. Keep database, WAL/SHM, Settings, and user files consistent across failure. Clear stale sidecars only as part of a
   validated replacement plan, not as generic error handling.
6. Keep secrets, API keys, sync passwords, signing materials, and recovery secrets outside normal backups, fixtures,
   logs, commands, and reports.

## Implement across all boundaries

1. Update production readers and writers, schema or settings migrations, serialization adapters, import/export, backup and
   restore, defaults, UI, runtime routing, and documentation wherever the old representation is reachable.
2. Preserve legacy backup names or formats through an explicit compatibility map when the contract requires it. Do not
   retain dead product behavior merely to keep old values decodable.
3. Generate versioned manifests and SHA-256 for backup content where applicable. Validate locally, after upload/readback,
   and before restore; never overwrite the last verified backup with a new failed attempt.
4. Document intentional deletion or rebuilding of user-semantic data in the owning contract and prove its exact scope with
   migration tests.

## Prove compatibility

1. Test fresh creation plus live-store upgrade from each supported old representation.
2. Test export/import and backup/restore round trips with legacy and current fixtures. Cover damaged archives, wrong keys,
   hash mismatch, path escape, interrupted replacement, and rollback without corrupting the active store.
3. For Room, verify schema, critical tables and fields, object counts, foreign keys/indexes, and representative values. For
   DataStore and serialized types, verify old discriminators, defaults, removed Providers, and unknown or malformed input.
4. Verify user-file inventory and hashes; verify rebuildable data can be recreated without being mistaken for user data.
5. Install the previous public APK with seeded representative data, then cover-install the candidate with the same
   production application ID and signing lineage. Verify launch, migrations, retained semantic data, settings, files,
   backup round trip, and critical paths.
6. Keep the first stable public-version seed path as a long-horizon check and require at least the immediately previous
   public version for the release gate.

## Report the gate accurately

1. Record old and new formats, migration entry points, fixtures, exact tests, public source version, candidate identity,
   object and file checks, backup/restore result, and failures exercised.
2. Distinguish implementation tests from public-version coverage-upgrade evidence. If the latter was not run, report the
   change as implemented but not yet proven safe for a stable release.
3. Preserve rollback artifacts after a failed validation and provide the next recoverable step. Never claim complete
   disaster recovery while the owning contract still lists unmet gaps.

Use the repository documents as the detailed source of truth:

- [`docs/zhixing/DATA_SAFETY_AND_BACKUP.md`](../../../docs/zhixing/DATA_SAFETY_AND_BACKUP.md)
- [`docs/zhixing/README.md`](../../../docs/zhixing/README.md)
- [`docs/zhixing/RELEASE_FLOW.md`](../../../docs/zhixing/RELEASE_FLOW.md)
