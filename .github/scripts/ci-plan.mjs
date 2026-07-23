#!/usr/bin/env node

import { appendFileSync } from "node:fs"
import { execFileSync } from "node:child_process"
import { pathToFileURL } from "node:url"

const FULL_PR_CHECKS = [
  "Work and JS tests",
  "Android unit tests",
  "Android build smoke",
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

export function hasVerifiedPullRequestChecks(checkRuns) {
  const checks = latestChecksByName(checkRuns)
  if (
    !checkSucceeded(checks.get("Plan CI")) ||
    !checkSucceeded(checks.get("Branch policy"))
  ) {
    return false
  }

  const fullChecksPassed = FULL_PR_CHECKS.every((name) =>
    checkSucceeded(checks.get(name)),
  )
  const metadataCheckPassed = checkSucceeded(checks.get("Release metadata"))
  return fullChecksPassed || metadataCheckPassed
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
  return hasVerifiedPullRequestChecks(response.check_runs ?? [])
}

export async function planPush(params, verify = mergedCommitHasVerifiedPullRequest) {
  try {
    const verified = await verify(params)
    return {
      run_full: String(!verified),
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
    const metadataOnly = isReleaseMetadataOnly(paths, appBuildDiff)
    writeOutputs({
      run_full: String(!metadataOnly),
      metadata_only: String(metadataOnly),
      reason: metadataOnly ? "release-metadata-only" : "pull-request",
    })
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
