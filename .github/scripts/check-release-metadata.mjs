#!/usr/bin/env node

import { existsSync, readFileSync } from "node:fs"
import { execFileSync } from "node:child_process"
import { pathToFileURL } from "node:url"

function runGit(args) {
  return execFileSync("git", args, { encoding: "utf8" }).trim()
}

export function parseVersion(content) {
  const versionCode = content.match(/\bversionCode\s*=\s*(\d+)/)?.[1]
  const versionName = content.match(/\bversionName\s*=\s*"([^"]+)"/)?.[1]
  if (!versionCode || !versionName) {
    throw new Error("Could not parse versionCode/versionName")
  }
  return {
    code: Number(versionCode),
    name: versionName,
  }
}

function parseSemver(value) {
  const match = value.match(/^(\d+)\.(\d+)\.(\d+)$/)
  if (!match) {
    throw new Error(`Version must use strict X.Y.Z format: ${value}`)
  }
  return match.slice(1).map(Number)
}

export function isGreaterSemver(candidate, base) {
  const candidateParts = parseSemver(candidate)
  const baseParts = parseSemver(base)
  for (let index = 0; index < candidateParts.length; index += 1) {
    if (candidateParts[index] !== baseParts[index]) {
      return candidateParts[index] > baseParts[index]
    }
  }
  return false
}

export function validateVersionAdvance(baseContent, currentContent) {
  const base = parseVersion(baseContent)
  const current = parseVersion(currentContent)
  if (current.code <= base.code) {
    throw new Error(
      `versionCode must increase: ${base.code} -> ${current.code}`,
    )
  }
  if (!isGreaterSemver(current.name, base.name)) {
    throw new Error(
      `versionName must increase: ${base.name} -> ${current.name}`,
    )
  }
  return current
}

function main() {
  const [baseSha, headSha] = process.argv.slice(2)
  if (!baseSha || !headSha) {
    throw new Error(
      "Usage: check-release-metadata.mjs <base-sha> <head-sha>",
    )
  }

  const baseContent = runGit([
    "show",
    `${baseSha}:app/build.gradle.kts`,
  ])
  const currentContent = readFileSync("app/build.gradle.kts", "utf8")
  const current = validateVersionAdvance(baseContent, currentContent)
  const notePath = `release-notes/${current.name}.md`
  if (!existsSync(notePath) || readFileSync(notePath, "utf8").trim() === "") {
    throw new Error(`Missing non-empty release notes: ${notePath}`)
  }

  const actualHead = runGit(["rev-parse", "HEAD"])
  if (actualHead !== headSha) {
    throw new Error(`Checked out ${actualHead}, expected ${headSha}`)
  }

  console.log(
    `Release metadata accepted: ${current.name} (${current.code}), notes ${notePath}`,
  )
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
