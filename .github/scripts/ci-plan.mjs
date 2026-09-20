#!/usr/bin/env node

import { appendFileSync } from "node:fs"
import { execFileSync } from "node:child_process"
import { pathToFileURL } from "node:url"

const WORK_PATH_PREFIXES = ["work/", "staging-driver/"]
const ANDROID_PATH_PREFIXES = [
  "app/",
  "work-app/",
  "ai/",
  "common/",
  "document/",
  "gradle/",
  "highlight/",
  "material3/",
  "search/",
  "speech/",
  "web/",
  "web-ui/",
  "workspace/",
]
const ANDROID_ROOT_FILES = new Set([
  ".gitmodules",
  "build.gradle.kts",
  "gradle.properties",
  "gradlew",
  "gradlew.bat",
  "settings.gradle.kts",
])
const DOCUMENTATION_PATH_PREFIXES = [
  ".agents/",
  ".claude/",
  "docs/",
  "release-notes/",
]

function runGit(args) {
  return execFileSync("git", args, { encoding: "utf8" }).trim()
}

export function isVersionOnlyBuildDiff(diff) {
  let changedLineCount = 0

  for (const line of diff.split(/\r?\n/)) {
    if (
      line === "" ||
      line.startsWith("diff --git ") ||
      line.startsWith("index ") ||
      line.startsWith("--- ") ||
      line.startsWith("+++ ") ||
      line.startsWith("@@")
    ) {
      continue
    }

    if (line.startsWith("+") || line.startsWith("-")) {
      changedLineCount += 1
      const isVersionCode = /^[+-]\s*versionCode\s*=\s*\d+\s*$/.test(line)
      const isVersionName =
        /^[+-]\s*versionName\s*=\s*"\d+\.\d+\.\d+"\s*$/.test(line)
      if (!isVersionCode && !isVersionName) {
        return false
      }
    }
  }

  return changedLineCount > 0
}

export function isReleaseMetadataOnly(paths, appBuildDiff = "") {
  if (paths.length === 0) {
    return false
  }

  const allowed = paths.every(
    (path) =>
      path.startsWith("release-notes/") || path === "app/build.gradle.kts",
  )
  if (!allowed) {
    return false
  }

  return (
    !paths.includes("app/build.gradle.kts") ||
    isVersionOnlyBuildDiff(appBuildDiff)
  )
}

function startsWithAny(path, prefixes) {
  return prefixes.some((prefix) => path.startsWith(prefix))
}

function isDocumentationPath(path) {
  return (
    startsWithAny(path, DOCUMENTATION_PATH_PREFIXES) ||
    path.endsWith(".md") ||
    path === "LICENSE"
  )
}

export function planPullRequest(paths, appBuildDiff = "") {
  if (isReleaseMetadataOnly(paths, appBuildDiff)) {
    return {
      run_full: "false",
      run_work: "false",
      run_android: "false",
      metadata_only: "true",
      reason: "release-metadata-only",
    }
  }

  if (paths.length === 0) {
    return {
      run_full: "true",
      run_work: "true",
      run_android: "true",
      metadata_only: "false",
      reason: "cross-domain-or-infrastructure",
    }
  }

  let runWork = false
  let runAndroid = false
  let runConservatively = false

  for (const path of paths) {
    if (startsWithAny(path, WORK_PATH_PREFIXES)) {
      runWork = true
    } else if (
      startsWithAny(path, ANDROID_PATH_PREFIXES) ||
      ANDROID_ROOT_FILES.has(path)
    ) {
      runAndroid = true
    } else if (!isDocumentationPath(path)) {
      runConservatively = true
    }
  }

  if (runConservatively) {
    runWork = true
    runAndroid = true
  }

  const runFull = runWork && runAndroid
  const reason = runFull
    ? "cross-domain-or-infrastructure"
    : runWork
      ? "work-only"
      : runAndroid
        ? "android-only"
        : "documentation-only"

  return {
    run_full: String(runFull),
    run_work: String(runWork),
    run_android: String(runAndroid),
    metadata_only: "false",
    reason,
  }
}

function writeOutputs(outputs) {
  const outputPath = process.env.GITHUB_OUTPUT
  const body = Object.entries(outputs)
    .map(([key, value]) => `${key}=${value}`)
    .join("\n")

  if (outputPath) {
    appendFileSync(outputPath, `${body}\n`)
  } else {
    console.log(body)
  }
}

function main() {
  const eventName = process.env.GITHUB_EVENT_NAME
  if (eventName !== "pull_request") {
    throw new Error(`Unsupported event: ${eventName}`)
  }

  const baseSha = process.env.BASE_SHA
  const headSha = process.env.HEAD_SHA
  if (!baseSha || !headSha) {
    throw new Error("BASE_SHA and HEAD_SHA are required for pull requests")
  }

  const paths = runGit(["diff", "--name-only", `${baseSha}...${headSha}`])
    .split(/\r?\n/)
    .filter(Boolean)
  const appBuildDiff = paths.includes("app/build.gradle.kts")
    ? runGit([
        "diff",
        "--unified=0",
        `${baseSha}...${headSha}`,
        "--",
        "app/build.gradle.kts",
      ])
    : ""
  writeOutputs(planPullRequest(paths, appBuildDiff))
}

const isEntryPoint =
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href

if (isEntryPoint) {
  try {
    main()
  } catch (error) {
    console.error(error)
    process.exitCode = 1
  }
}
