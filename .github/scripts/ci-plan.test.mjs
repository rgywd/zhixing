import assert from "node:assert/strict"
import test from "node:test"

import {
  isReleaseMetadataOnly,
  isVersionOnlyBuildDiff,
  planPullRequest,
} from "./ci-plan.mjs"

test("accepts only versionCode and versionName changes", () => {
  const diff = `diff --git a/app/build.gradle.kts b/app/build.gradle.kts
--- a/app/build.gradle.kts
+++ b/app/build.gradle.kts
@@ -23,2 +23,2 @@
-        versionCode = 27
-        versionName = "0.3.9"
+        versionCode = 28
+        versionName = "0.3.10"`

  assert.equal(isVersionOnlyBuildDiff(diff), true)
  assert.equal(
    isReleaseMetadataOnly(
      ["app/build.gradle.kts", "release-notes/0.3.10.md"],
      diff,
    ),
    true,
  )
  assert.deepEqual(
    planPullRequest(
      ["app/build.gradle.kts", "release-notes/0.3.10.md"],
      diff,
    ),
    {
      run_full: "false",
      run_work: "false",
      run_android: "false",
      metadata_only: "true",
      reason: "release-metadata-only",
    },
  )
})
test("rejects any other Gradle build change", () => {
  const diff = `diff --git a/app/build.gradle.kts b/app/build.gradle.kts
--- a/app/build.gradle.kts
+++ b/app/build.gradle.kts
@@ -69,1 +69,1 @@
-            isMinifyEnabled = true
+            isMinifyEnabled = false`

  assert.equal(isVersionOnlyBuildDiff(diff), false)
  assert.equal(
    isReleaseMetadataOnly(
      ["app/build.gradle.kts", "release-notes/0.3.10.md"],
      diff,
    ),
    false,
  )
})

test("rejects extra code hidden on a version assignment line", () => {
  const diff = `diff --git a/app/build.gradle.kts b/app/build.gradle.kts
--- a/app/build.gradle.kts
+++ b/app/build.gradle.kts
@@ -23,1 +23,1 @@
-        versionCode = 27
+        versionCode = 28; println("unexpected")`

  assert.equal(isVersionOnlyBuildDiff(diff), false)
})

test("rejects source changes from the release metadata fast path", () => {
  assert.equal(
    isReleaseMetadataOnly([
      "release-notes/0.3.10.md",
      "app/src/main/java/example/App.kt",
    ]),
    false,
  )
})

test("plans Work-only changes without Android jobs", () => {
  assert.deepEqual(
    planPullRequest([
      "work/runner/runner.js",
      "docs/zhixing/CODEX_PHONE_LINE_CONTRACT.md",
    ]),
    {
      run_full: "false",
      run_work: "true",
      run_android: "false",
      metadata_only: "false",
      reason: "work-only",
    },
  )
})

test("plans Android-only changes without Work jobs", () => {
  assert.deepEqual(
    planPullRequest([
      "app/src/main/java/example/App.kt",
      "docs/zhixing/RUNTIME_CONTRACT.md",
    ]),
    {
      run_full: "false",
      run_work: "false",
      run_android: "true",
      metadata_only: "false",
      reason: "android-only",
    },
  )
})

test("plans cross-domain and CI changes conservatively", () => {
  const expected = {
    run_full: "true",
    run_work: "true",
    run_android: "true",
    metadata_only: "false",
    reason: "cross-domain-or-infrastructure",
  }

  assert.deepEqual(
    planPullRequest(["work/core/server.js", "app/src/main/java/example/App.kt"]),
    expected,
  )
  assert.deepEqual(planPullRequest([".github/workflows/ci.yml"]), expected)
  assert.deepEqual(planPullRequest(["unclassified/tooling.conf"]), expected)
})

test("plans documentation and agent instructions without domain jobs", () => {
  assert.deepEqual(
    planPullRequest([
      "docs/zhixing/CI_PIPELINE.md",
      "AGENTS.md",
      ".agents/skills/example/SKILL.md",
      ".claude/skills/example/SKILL.md",
    ]),
    {
      run_full: "false",
      run_work: "false",
      run_android: "false",
      metadata_only: "false",
      reason: "documentation-only",
    },
  )
})
test("plans an empty diff conservatively", () => {
  assert.deepEqual(planPullRequest([]), {
    run_full: "true",
    run_work: "true",
    run_android: "true",
    metadata_only: "false",
    reason: "cross-domain-or-infrastructure",
  })
})

test("standalone Work Android changes require Android verification", () => {
  assert.equal(planPullRequest(["work-app/src/main/java/WorkActivity.kt"]).run_android, "true")
  assert.equal(planPullRequest(["work-app/src/main/java/WorkActivity.kt"]).run_work, "false")
})
