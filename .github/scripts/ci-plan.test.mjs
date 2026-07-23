import assert from "node:assert/strict"
import test from "node:test"

import {
  hasVerifiedPullRequestChecks,
  isReleaseMetadataOnly,
  isVersionOnlyBuildDiff,
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

test("accepts a fully verified normal pull request", () => {
  const checks = [
    successfulCheck("Plan CI"),
    successfulCheck("Branch policy"),
    successfulCheck("Work and JS tests"),
    successfulCheck("Android unit tests"),
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

test("accepts a verified release metadata pull request", () => {
  const checks = [
    successfulCheck("Plan CI"),
    successfulCheck("Branch policy"),
    successfulCheck("Release metadata"),
  ]

  assert.equal(hasVerifiedPullRequestChecks(checks), true)
})

test("rejects missing or failed checks and uses the latest attempt", () => {
  const checks = [
    successfulCheck("Plan CI"),
    successfulCheck("Branch policy"),
    successfulCheck("Work and JS tests"),
    successfulCheck("Android unit tests"),
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
    metadata_only: "false",
    reason: "verification-unavailable",
  })
})
