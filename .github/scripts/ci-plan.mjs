#!/usr/bin/env node

import { appendFileSync } from "node:fs"
import { execFileSync } from "node:child_process"
import { pathToFileURL } from "node:url"

const ANDROID_PR_CHECKS = [
  "Android unit tests",
  "Android lint",
  "Android build smoke",
]

const WORK_PATH_PREFIXES = ["work/", "staging-driver/"]
const ANDROID_PATH_PREFIXES = [
  "app/",
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

function latestChecksByName(checkRuns) {
  const latest = new Map()
  for (const check of checkRuns) {
    const previous = latest.get(check.name)
    if (!previous || Number(check.id ?? 0) > Number(previous.id ?? 0)) {
      latest.set(check.name, check)
    }
  }
  return latest
}

function checkSucceeded(check) {
  return check?.status === "completed" && check?.conclusion === "success"
}

export function hasVerifiedPullRequestChecks(
  checkRuns,
  plan = {
    run_work: "true",
    run_android: "true",
    metadata_only: "false",
  },
) {
  const checks = latestChecksByName(checkRuns)
  if (
    !checkSucceeded(checks.get("Plan CI")) ||
    !checkSucceeded(checks.get("Branch policy"))
  ) {
    return false
  }

  if (plan.metadata_only === "true") {
    return checkSucceeded(checks.get("Release metadata"))
  }
  if (
    plan.run_work === "true" &&
    !checkSucceeded(checks.get("Work and JS tests"))
  ) {
    return false
  }
  if (
    plan.run_android === "true" &&
    !ANDROID_PR_CHECKS.every((name) => checkSucceeded(checks.get(name)))
  ) {
    return false
  }
  return true
}

async function githubApi(path, token) {
  const response = await fetch(`https://api.github.com${path}`, {
    headers: {
      Accept: "application/vnd.github+json",
      Authorization: `Bearer ${token}`,
      "X-GitHub-Api-Version": "2022-11-28",
      "User-Agent": "zhixing-ci-plan",
    },
  })
  if (!response.ok) {
    throw new Error(`GitHub API ${path} returned ${response.status}`)
  }
  return response.json()
}

async function githubApiPages(path, token) {
  const items = []
  for (let page = 1; ; page += 1) {
    const separator = path.includes("?") ? "&" : "?"
    const chunk = await githubApi(
      `${path}${separator}per_page=100&page=${page}`,
      token,
    )
    if (!Array.isArray(chunk)) {
      throw new Error(`GitHub API ${path} did not return a list`)
    }
    items.push(...chunk)
    if (chunk.length < 100) {
      return items
    }
  }
}

export async function mergedCommitHasVerifiedPullRequest({
  repository,
  commitSha,
  token,
}) {
  const pulls = await githubApi(
    `/repos/${repository}/commits/${commitSha}/pulls`,
    token,
  )
  const pull = pulls.find(
    (candidate) =>
      candidate.merged_at &&
      (candidate.merge_commit_sha === commitSha ||
        candidate.merge_commit_sha == null),
  )
  if (!pull) {
    return false
  }

  const response = await githubApi(
    `/repos/${repository}/commits/${pull.head.sha}/check-runs?per_page=100`,
    token,
  )
  const checkRuns = response.check_runs ?? []
  const releaseMetadataPassed = checkSucceeded(
    latestChecksByName(checkRuns).get("Release metadata"),
  )
  const plan = releaseMetadataPassed
    ? {
        run_work: "false",
        run_android: "false",
        metadata_only: "true",
      }
    : planPullRequest(
        (
          await githubApiPages(
            `/repos/${repository}/pulls/${pull.number}/files`,
            token,
          )
        ).map((file) => file.filename),
      )
  return hasVerifiedPullRequestChecks(checkRuns, plan)
}

export async function planPush(params, verify = mergedCommitHasVerifiedPullRequest) {
  try {
    const verified = await verify(params)
    return {
      run_full: String(!verified),
      run_work: String(!verified),
      run_android: String(!verified),
      metadata_only: "false",
      reason: verified
        ? "verified-pull-request-merge"
        : "direct-or-unverified-push",
    }
  } catch (error) {
    console.warn(
      `Could not verify pull request checks; falling back to full CI: ${error}`,
    )
    return {
      run_full: "true",
      run_work: "true",
      run_android: "true",
      metadata_only: "false",
      reason: "verification-unavailable",
    }
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

async function main() {
  const eventName = process.env.GITHUB_EVENT_NAME

  if (eventName === "pull_request") {
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
    return
  }

  if (eventName === "push") {
    writeOutputs(
      await planPush({
        repository: process.env.GITHUB_REPOSITORY,
        commitSha: process.env.GITHUB_SHA,
        token: process.env.GITHUB_TOKEN,
      }),
    )
    return
  }

  throw new Error(`Unsupported event: ${eventName}`)
}

const isEntryPoint =
  process.argv[1] &&
  import.meta.url === pathToFileURL(process.argv[1]).href

if (isEntryPoint) {
  main().catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
}
