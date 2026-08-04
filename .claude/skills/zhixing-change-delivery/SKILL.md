---
name: zhixing-change-delivery
description: "Drive a zhixing-rikkahub change from task intake through a safe short branch or isolated worktree, source and owning-contract discovery, implementation, diff-selected verification, modular commit, and optional push. Use for feature, fix, refactor, configuration, or repository-maintenance work in this repository. Stop at the user-authorized delivery stage: implementation, test APK, commit, push, or merge does not authorize a formal release."
---

# Deliver a Zhixing Change

## Establish the contract

1. Read repository `AGENTS.md` and `docs/zhixing/README.md`.
2. Read `docs/zhixing/RELEASE_FLOW.md` before creating a branch, commit, PR, or push.
3. Select the owning product or architecture document from the documentation index. Treat code, tests, Git, configuration,
   and live behavior as current facts; do not use archived plans as current requirements.
4. Restate the requested outcome and the terminal delivery stage. Treat formal release as unauthorized unless the user
   explicitly asks for a release; use `zhixing-release-android` only after that gate is met.

## Protect the workspace

1. Inspect `git status --short --branch`, `git worktree list`, the current HEAD, and `origin/main` before editing.
2. Use a short branch from an up-to-date clean `main` for single-track work. Follow the naming rules in
   `docs/zhixing/RELEASE_FLOW.md`.
3. Create an independent worktree from clean `origin/main` when another branch, dirty changes, or parallel work is present.
   Preserve existing changes; do not stash, reset, overwrite, or silently copy them into the new worktree.
4. Confirm every destructive or cleanup target by exact resolved path. Never initialize a Git repository at the outer
   `zhixing` workspace root.

## Locate the implementation

1. Use `rg --files`, `rg`, and source symbols to trace the current call path from the user-facing entry point to its state,
   persistence, network, or runtime boundary.
2. Read neighboring tests and build configuration before changing behavior. Verify current routes, types, schemas, and
   flags in source instead of maintaining a static code map in this skill.
3. Reconcile the requested behavior with the owning document. If code and documentation disagree, gather evidence and
   update the owning contract only when the task changes that contract.
4. Invoke `zhixing-data-safe-change` before editing Room, DataStore, serialized persisted types, Provider retirement,
   import/export, backup, or restore behavior.

## Implement narrowly

1. Make the smallest coherent change that satisfies the contract across every affected boundary.
2. Reuse established repository patterns and shared paths. Add or update tests next to the behavior they protect.
3. Preserve secrets, credentials, user data, unrelated dirty files, public interfaces, and compatibility unless the task
   explicitly changes their contract.
4. Inspect the working diff repeatedly so generated files, local configuration, build outputs, and unrelated edits do not
   enter the change.

## Select verification from the diff

1. Review `git diff --stat`, `git diff --name-status`, and the complete patch.
2. Run the narrowest relevant tests first, then the repository-required gate for every affected surface:
   - run targeted JVM or module tests for Kotlin logic;
   - compile the affected Android variant and run relevant lint for Compose, resources, manifest, or Gradle changes;
   - run the owning JavaScript or Work tests for `web-ui`, Work Core, Runner, or protocol changes;
   - run migration, old-fixture, import/export, backup, and upgrade checks required by `zhixing-data-safe-change` for
     persisted data changes;
   - run documentation validation when owning docs change;
   - inspect the actual workflows and `docs/zhixing/CI_PIPELINE.md` for CI or build-pipeline changes.
3. Add a broader build or integration check when the diff crosses modules, packaging, runtime, or public contracts.
4. Record exact commands, outcomes, skipped checks, and remaining device, credential, network, or production gaps. Never
   report an unrun gate as passed.

## Commit and hand off

1. Run `git diff --check`, re-read `git status`, and review the staged patch before committing.
2. Create modular commits using the repository format: English Conventional Commit type and optional scope, Chinese
   result summary, and a numbered Chinese body covering the change and verification.
3. Push only the intended short branch when push is within the requested delivery stage. Never push directly or force-push
   `main`.
4. Report the branch, commit SHA, changed surfaces, validation evidence, and exact current state separately: implemented,
   committed, pushed, PR opened, merged, or released. Do not collapse those states into “done.”

Use the repository documents as the detailed source of truth:

- [`docs/zhixing/README.md`](../../../docs/zhixing/README.md)
- [`docs/zhixing/RELEASE_FLOW.md`](../../../docs/zhixing/RELEASE_FLOW.md)
- [`docs/zhixing/CI_PIPELINE.md`](../../../docs/zhixing/CI_PIPELINE.md)
