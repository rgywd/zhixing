import assert from "node:assert/strict"
import test from "node:test"

import {
  hasVerifiedPullRequestChecks,
  isReleaseMetadataOnly,
  isVersionOnlyBuildDiff,
  planPullRequest,
  planPush,
} from "./ci-plan.mjs"

function successfulCheck(name, id = 1) {
  return {
    id,
    name,
    status: "completed",
    conclusion: "success",
  }
}

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

test("accepts a fully verified normal pull request", () => {
  const checks = [
    successfulCheck("Plan CI"),
    successfulCheck("Branch policy"),
    successfulCheck("Work and JS tests"),
    successfulCheck("Android unit tests"),
    successfulCheck("Android lint"),
    successfulCheck("Android build smoke"),
    {
      id: 1,
      name: "Release metadata",
      status: "completed",
      conclusion: "skipped",
    },
  ]

  assert.equal(hasVerifiedPullRequestChecks(checks), true)
})

test("accepts only the checks required by a Work-only plan", () => {
  const checks = [
    successfulCheck("Plan CI"),
    successfulCheck("Branch policy"),
    successfulCheck("Work and JS tests"),
  ]

  assert.equal(
    hasVerifiedPullRequestChecks(
      checks,
      planPullRequest(["work/runner/runner.js"]),
    ),
    true,
  )
  assert.equal(
    hasVerifiedPullRequestChecks(
      checks.slice(0, 2),
      planPullRequest(["work/runner/runner.js"]),
    ),
    false,
  )
})

test("accepts only the checks required by an Android-only plan", () => {
  const checks = [
    successfulCheck("Plan CI"),
    successfulCheck("Branch policy"),
    successfulCheck("Android unit tests"),
    successfulCheck("Android lint"),
    successfulCheck("Android build smoke"),
  ]

  assert.equal(
    hasVerifiedPullRequestChecks(
      checks,
      planPullRequest(["app/src/main/java/example/App.kt"]),
    ),
    true,
  )
})

test("accepts documentation-only pull requests after planning and policy", () => {
  const checks = [
    successfulCheck("Plan CI"),
    successfulCheck("Branch policy"),
  ]

  assert.equal(
    hasVerifiedPullRequestChecks(
      checks,
      planPullRequest(["docs/zhixing/CI_PIPELINE.md"]),
    ),
    true,
  )
})

test("rejects a normal pull request without Android lint", () => {
  const checks = [
    successfulCheck("Plan CI"),
    successfulCheck("Branch policy"),
    successfulCheck("Work and JS tests"),
    successfulCheck("Android unit tests"),
    successfulCheck("Android build smoke"),
  ]

  assert.equal(hasVerifiedPullRequestChecks(checks), false)
})

test("accepts a verified release metadata pull request", () => {
  const checks = [
    successfulCheck("Plan CI"),
    successfulCheck("Branch policy"),
    successfulCheck("Release metadata"),
  ]

  assert.equal(
    hasVerifiedPullRequestChecks(checks, {
      run_work: "false",
      run_android: "false",
      metadata_only: "true",
    }),
    true,
  )
})

test("rejects missing or failed checks and uses the latest attempt", () => {
  const checks = [
    successfulCheck("Plan CI"),
    successfulCheck("Branch policy"),
    successfulCheck("Work and JS tests"),
    successfulCheck("Android unit tests"),
    successfulCheck("Android lint"),
    successfulCheck("Android build smoke", 1),
    {
      id: 2,
      name: "Android build smoke",
      status: "completed",
      conclusion: "failure",
    },
  ]

  assert.equal(hasVerifiedPullRequestChecks(checks), false)
})

test("falls back to full CI when GitHub verification is unavailable", async () => {
  const plan = await planPush(
    { repository: "example/repo", commitSha: "abc", token: "token" },
    async () => {
      throw new Error("temporary API failure")
    },
  )

  assert.deepEqual(plan, {
    run_full: "true",
    run_work: "true",
    run_android: "true",
    metadata_only: "false",
    reason: "verification-unavailable",
  })
})

test("skips duplicate main checks after a verified pull request", async () => {
  const plan = await planPush(
    { repository: "example/repo", commitSha: "abc", token: "token" },
    async () => true,
  )

  assert.deepEqual(plan, {
    run_full: "false",
    run_work: "false",
    run_android: "false",
    metadata_only: "false",
    reason: "verified-pull-request-merge",
  })
})
