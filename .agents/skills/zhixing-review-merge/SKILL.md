---
name: zhixing-review-merge
description: "Review and, only when authorized, merge a zhixing-rikkahub pull request using the exact head SHA, complete base-to-head diff, required CI, owning documentation, and data-safety evidence; then read the merge back from origin/main and safely clean an isolated worktree. Use for PR review, merge readiness, merging, or post-merge cleanup. A review request alone does not authorize merge, pending or missing CI never counts as green, and merge does not authorize release."
---

# Review and Merge a Zhixing Change

## Fix the review target

1. Read repository `AGENTS.md`, `docs/zhixing/README.md`, `docs/zhixing/RELEASE_FLOW.md`, and
   `docs/zhixing/CI_PIPELINE.md`.
2. Identify the repository, PR number, base branch, head branch, and exact head SHA. Confirm the base is `main` for ordinary
   delivery and the branch name follows repository policy.
3. Fetch current remote metadata and inspect the complete base-to-head diff. Do not review only the latest commit or a
   stale local branch.
4. Record whether the user requested review only or explicitly authorized merge. Do not infer merge authority from “look
   at,” “review,” or “check.” Never infer release authority from a merge request.

## Review the evidence

1. Read every changed file plus enough surrounding source and tests to understand behavior, not just patch shape.
2. Trace affected entry points through state, persistence, network, and runtime boundaries. Check the relevant owning
   documents from `docs/zhixing/README.md`.
3. Check for correctness, regressions, concurrency and lifecycle hazards, security and credential exposure, compatibility,
   unreachable code, missing error handling, and claims unsupported by tests or runtime evidence.
4. For Room, DataStore, serialized persisted types, Provider retirement, import/export, backup, or restore changes, apply
   `zhixing-data-safe-change` and require its migration and upgrade evidence.
5. Check that contract changes update the owning document rather than adding a conflicting status note. Keep archive
   material historical.
6. Verify the intended commits are modular and the proposed merge title/body can satisfy the repository commit format.

## Gate on CI and SHA

1. Read the PR's required checks for the exact head SHA. Require all checks selected by the current CI plan, including
   branch policy and any diff-specific Android, Work, JavaScript, lint, build, or release-metadata gate.
2. Treat pending, queued, skipped unexpectedly, cancelled, missing, stale, or failed required checks as not mergeable.
   Wait for completion or report the blocker; never merge while CI is merely running.
3. Confirm the branch is based on current `main` and is not blocked by unresolved review threads or merge conflicts.
4. Refresh the PR immediately before mutation. If the head SHA, base, diff, approvals, or checks changed, stop and review
   the new state before merging.

## Merge only with authority

1. Resolve every blocking finding and re-run the affected gates.
2. Preserve an independent rollback boundary with rebase merge when required; otherwise use the repository-approved
   squash strategy. Do not create an unreviewed merge commit or bypass required checks.
3. Merge the exact reviewed head SHA into `main`. Never push directly or force-push `main`.
4. Capture the PR URL or number, reviewed head SHA, merge strategy, merge commit SHA, and final check results.

## Read back and clean safely

1. Fetch `origin/main` after the merge and prove the reported merge commit or rebased commits are present in its history.
2. Read back the changed files from `origin/main` or a clean main checkout and confirm the intended result survived the
   selected merge strategy.
3. Remove an isolated worktree only after the change is durably present on `origin/main`, the exact worktree is clean, and
   no continuing task uses it. Resolve its absolute path before cleanup.
4. Use normal `git worktree remove` and non-forced branch deletion. If untracked or unmerged work exists, preserve it and
   report the blocker; never use force deletion, reset, or stash merely to clean up.
5. Report review findings, exact CI evidence, merge/readback result, and cleanup result separately. State explicitly that
   no formal release occurred unless a separately authorized release was completed.

Use the repository documents as the detailed source of truth:

- [`docs/zhixing/RELEASE_FLOW.md`](../../../docs/zhixing/RELEASE_FLOW.md)
- [`docs/zhixing/CI_PIPELINE.md`](../../../docs/zhixing/CI_PIPELINE.md)
- [`docs/zhixing/DATA_SAFETY_AND_BACKUP.md`](../../../docs/zhixing/DATA_SAFETY_AND_BACKUP.md)
