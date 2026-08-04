---
name: zhixing-release-android
description: "Execute the formal zhixing-rikkahub Android release flow only after the user explicitly requests a release: prepare version metadata through main, freeze release/x.y.z, verify R8 and signing, create one immutable annotated tag, publish the six required assets to rgywd/zhixing-releases, and prove hashes, latest.json, anonymous downloads, and in-place upgrade. Do not trigger for implementation, test APK, commit, push, PR, merge, deployment discussion, staging distribution, or an implied desire to ship."
---

# Release Zhixing Android

## Enforce explicit authorization

1. Quote or restate the user's explicit request for a formal release. If it is absent, stop before creating `release/*`, a
   tag, a GitHub Release, or formal assets and report the highest authorized delivery state.
2. Read repository `AGENTS.md` and these documents in full before acting:
   - `docs/zhixing/RELEASE_FLOW.md`
   - `docs/zhixing/CI_PIPELINE.md`
   - `docs/zhixing/DATA_SAFETY_AND_BACKUP.md`
   - `docs/zhixing/PUBLIC_RELEASE_DISTRIBUTION.md`
3. Treat the workflows, Gradle configuration, Git remote, and current public release as live evidence. Do not copy commands
   or current version facts from old release notes or legacy commands.

## Prepare main

1. Resolve the exact `X.Y.Z`, next monotonic `versionCode`, release scope, and previous public version. Fetch current
   `origin/main` and tags; verify the target tag and public release do not already conflict with another commit.
2. Put all feature, fix, version metadata, and `release-notes/X.Y.Z.md` changes through reviewable short-branch PRs into
   `main`. Never edit or push `main` directly.
3. Require the PR gates selected by `docs/zhixing/CI_PIPELINE.md`, then require the merged `main` source-validation or
   fallback full CI to finish successfully.
4. Verify `versionName`, `versionCode`, release notes, target commit, and planned tag agree. Pause unrelated feature merges
   for the frozen release scope.

## Freeze and verify the candidate

1. Create `release/X.Y.Z` only from the latest verified `origin/main` and push that exact frozen snapshot.
2. Confirm the release head is in `origin/main` history and the release branch has not diverged. Accept updates only by
   `git merge --ff-only origin/main` after a fix first enters `main` through PR.
3. Use the repository's current release variant and workflow to verify tests, R8/minification, production configuration,
   and signing. Keep signing materials and production credentials out of commands, logs, documents, artifacts, and chat.
4. Inspect the candidate APK identity, version, package, architecture coverage, and signature using the repository-supported
   Android tools. Do not call an unsigned, debug, staging, split-only, or mismatched APK the formal candidate.
5. Apply `zhixing-data-safe-change` and perform an in-place upgrade from the prior public version with representative user
   data. Verify launch, schema and settings migration, retained data, files, and critical product paths; also verify clean
   install behavior.

## Tag once and run the release workflow

1. Recheck that every gate still applies to the exact release HEAD.
2. Create one annotated `vX.Y.Z` tag at that HEAD and push it once. Confirm the tagged commit remains in
   `origin/main` history.
3. Follow the tag-triggered private-repository workflow. Require release tests and the signed release APK job to succeed;
   do not treat parallel execution as permission to skip either side.
4. If asset generation, upload, or workflow execution fails, diagnose and rerun the same immutable tag through the
   supported workflow path. Never move, delete, recreate, or reuse the tag for a different commit.
5. Never create a formal Release page or upload formal assets in the private `rgywd/zhixing` repository.

## Prove public distribution

1. Verify exactly one formal APK and all six fixed assets exist on `rgywd/zhixing-releases` for `vX.Y.Z`:
   - `zhixing-X.Y.Z-universal.apk`
   - `zhixing-X.Y.Z-source.tar.gz`
   - `latest.json`
   - `SHA256SUMS.txt`
   - `LICENSE`
   - `THIRD_PARTY_NOTICES.md`
2. Verify the public asset names, sizes, and downloaded SHA-256 values against `SHA256SUMS.txt`. Confirm there are no extra
   split APKs presented as formal alternatives.
3. Parse `latest.json`; require its version, download URL, checksum, and `source.commit` to match the public release, APK,
   checksum file, and exact tag commit.
4. Download the release page, manifest, Universal APK, source archive, checksum, and license assets without private-repo
   authentication. Verify the source archive represents the same clean recursive tag checkout and excludes secrets and
   local build state.
5. Verify the app's production update flow discovers the public manifest and upgrades the prior public installation to the
   signed Universal APK without clearing data.

## Finish or stop honestly

1. Delete the release branch and merged short branches only after the public readback, checksum, clean-install, and
   coverage-upgrade gates pass and all exact worktrees are clean.
2. Report the main commit, release branch, immutable tag commit, workflow run, public Release URL, six assets, checksum
   evidence, signing/R8 evidence, and upgrade result.
3. If any gate remains incomplete, leave recoverable state intact and report the release as blocked or partial. A pushed
   tag, successful build, or uploaded APK alone is not a completed release.

Use the repository documents as the detailed source of truth:

- [`docs/zhixing/RELEASE_FLOW.md`](../../../docs/zhixing/RELEASE_FLOW.md)
- [`docs/zhixing/CI_PIPELINE.md`](../../../docs/zhixing/CI_PIPELINE.md)
- [`docs/zhixing/DATA_SAFETY_AND_BACKUP.md`](../../../docs/zhixing/DATA_SAFETY_AND_BACKUP.md)
- [`docs/zhixing/PUBLIC_RELEASE_DISTRIBUTION.md`](../../../docs/zhixing/PUBLIC_RELEASE_DISTRIBUTION.md)
