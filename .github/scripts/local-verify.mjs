#!/usr/bin/env node

import { createHash } from "node:crypto"
import { execFileSync, spawnSync } from "node:child_process"
import { existsSync, mkdirSync, writeFileSync } from "node:fs"
import { homedir } from "node:os"
import path from "node:path"
import process from "node:process"
import { pathToFileURL } from "node:url"

import { planPullRequest } from "./ci-plan.mjs"

const MODES = new Set(["auto", "work", "android", "full"])

function commandNames(platform) {
  if (platform === "win32") {
    return {
      npm: "npm.cmd",
      pnpm: "pnpm.cmd",
      python: "py",
      pythonPrefix: ["-3"],
      gradle: "gradlew.bat",
    }
  }
  return {
    npm: "npm",
    pnpm: "pnpm",
    python: "python3",
    pythonPrefix: [],
    gradle: "./gradlew",
  }
}

export function buildVerificationCommands({
  plan,
  mode = "auto",
  platform = process.platform,
  nodeExecutable = process.execPath,
  baseRef = "origin/main",
  headRef = "HEAD",
}) {
  if (!MODES.has(mode)) {
    throw new Error(`Unsupported verification mode: ${mode}`)
  }

  const names = commandNames(platform)
  const runWork =
    mode === "full" || mode === "work" ||
    (mode === "auto" && plan.run_work === "true")
  const runAndroid =
    mode === "full" || mode === "android" ||
    (mode === "auto" && plan.run_android === "true")
  const commands = [
    {
      label: "Repository policy tests",
      command: nodeExecutable,
      args: [
        "--test",
        ".github/scripts/check-skill-parity.test.mjs",
        ".github/scripts/ci-plan.test.mjs",
        ".github/scripts/check-release-metadata.test.mjs",
        ".github/scripts/local-verify.test.mjs",
      ],
    },
    {
      label: "Skill mirror parity",
      command: nodeExecutable,
      args: [".github/scripts/check-skill-parity.mjs"],
    },
  ]

  if (runWork) {
    commands.push(
      {
        label: "Install Work dependencies",
        command: names.npm,
        args: ["--prefix", "work", "ci"],
      },
      {
        label: "Work tests",
        command: names.npm,
        args: ["--prefix", "work", "test"],
      },
      {
        label: "Staging driver tests",
        command: names.npm,
        args: ["--prefix", "staging-driver", "test"],
      },
      {
        label: "Quota monitor tests",
        command: names.python,
        args: [
          ...names.pythonPrefix,
          "-m",
          "unittest",
          "discover",
          "-s",
          "tests",
          "-p",
          "test_*.py",
          "-v",
        ],
        cwd: "work/quota-monitor",
      },
    )
  }

  if (runAndroid) {
    commands.push(
      {
        label: "Initialize submodules",
        command: "git",
        args: ["submodule", "update", "--init", "--recursive"],
      },
      {
        label: "Install web dependencies",
        command: names.pnpm,
        args: ["--dir", "web-ui", "install", "--frozen-lockfile"],
      },
      {
        label: "Android unit tests",
        command: names.gradle,
        args: [
          ":ai:testDebugUnitTest",
          ":speech:testDebugUnitTest",
          ":workspace:testDebugUnitTest",
          ":app:testStagingUnitTest",
        ],
      },
      {
        label: "Android lint",
        command: names.gradle,
        args: [":app:lintStaging"],
      },
      {
        label: "Android build smoke",
        command: names.gradle,
        args: [
          ":app:assembleDebug",
          ":app:assembleStaging",
          ":app:assembleStagingAndroidTest",
        ],
      },
    )
  }

  if (plan.metadata_only === "true") {
    commands.push({
      label: "Release metadata",
      command: nodeExecutable,
      args: [
        ".github/scripts/check-release-metadata.mjs",
        baseRef,
        headRef,
      ],
    })
  }

  return commands
}

export function commandNeedsShell(command, platform = process.platform) {
  return platform === "win32" && /\.(?:cmd|bat)$/i.test(command)
}

export function buildCommandEnvironment({
  platform = process.platform,
  environment = process.env,
  homeDirectory = homedir(),
  pathExists = existsSync,
} = {}) {
  const result = { ...environment }
  if (result.ANDROID_HOME || result.ANDROID_SDK_ROOT) return result

  const platformPath = platform === "win32" ? path.win32 : path.posix
  const candidate =
    platform === "win32" && result.LOCALAPPDATA
      ? platformPath.join(result.LOCALAPPDATA, "Android", "Sdk")
      : platform === "darwin"
        ? platformPath.join(homeDirectory, "Library", "Android", "sdk")
        : platformPath.join(homeDirectory, "Android", "Sdk")
  if (pathExists(candidate)) {
    result.ANDROID_HOME = candidate
    result.ANDROID_SDK_ROOT = candidate
  }
  return result
}

function runGit(args, options = {}) {
  return execFileSync("git", args, {
    cwd: options.cwd,
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  }).trim()
}

function parseArguments(argv) {
  const options = {
    mode: "auto",
    baseRef: "origin/main",
    dryRun: false,
    allowDirty: false,
  }

  for (let index = 0; index < argv.length; index += 1) {
    const argument = argv[index]
    if (argument === "--dry-run") {
      options.dryRun = true
    } else if (argument === "--allow-dirty") {
      options.allowDirty = true
    } else if (argument === "--mode" || argument === "--base") {
      const value = argv[index + 1]
      if (!value) {
        throw new Error(`${argument} requires a value`)
      }
      index += 1
      if (argument === "--mode") options.mode = value
      if (argument === "--base") options.baseRef = value
    } else if (argument.startsWith("--mode=")) {
      options.mode = argument.slice("--mode=".length)
    } else if (argument.startsWith("--base=")) {
      options.baseRef = argument.slice("--base=".length)
    } else if (argument === "--help") {
      options.help = true
    } else {
      throw new Error(`Unknown argument: ${argument}`)
    }
  }

  if (!MODES.has(options.mode)) {
    throw new Error(`Unsupported verification mode: ${options.mode}`)
  }
  return options
}

function splitLines(value) {
  return value.split(/\r?\n/).filter(Boolean)
}

function changedPaths(root, baseRef, allowDirty) {
  const paths = new Set(
    splitLines(runGit(["diff", "--name-only", `${baseRef}...HEAD`], { cwd: root })),
  )
  if (allowDirty) {
    for (const args of [
      ["diff", "--name-only"],
      ["diff", "--cached", "--name-only"],
    ]) {
      for (const changed of splitLines(runGit(args, { cwd: root }))) {
        paths.add(changed)
      }
    }
    for (const line of splitLines(runGit(["status", "--porcelain"], { cwd: root }))) {
      const candidate = line.slice(3).split(" -> ").at(-1)
      if (candidate) paths.add(candidate)
    }
  }
  return [...paths].sort()
}

function appBuildDiff(root, baseRef, paths) {
  if (!paths.includes("app/build.gradle.kts")) return ""
  return runGit(
    [
      "diff",
      "--unified=0",
      `${baseRef}...HEAD`,
      "--",
      "app/build.gradle.kts",
    ],
    { cwd: root },
  )
}

function runCommand(root, command, platform, environment) {
  console.log(`\n==> ${command.label}`)
  console.log([command.command, ...command.args].join(" "))
  const result = spawnSync(command.command, command.args, {
    cwd: command.cwd ? path.resolve(root, command.cwd) : root,
    env: environment,
    stdio: "inherit",
    shell: commandNeedsShell(command.command, platform),
  })
  if (result.error) throw result.error
  if (result.status !== 0) {
    throw new Error(`${command.label} failed with exit code ${result.status}`)
  }
}

function writeReceipt(root, details) {
  const gitDirectory = runGit(["rev-parse", "--git-dir"], { cwd: root })
  const receiptPath = path.resolve(root, gitDirectory, "zhixing-local-verification.json")
  mkdirSync(path.dirname(receiptPath), { recursive: true })
  writeFileSync(receiptPath, `${JSON.stringify(details, null, 2)}\n`, "utf8")
  return receiptPath
}

function printHelp() {
  console.log(`Usage: node .github/scripts/local-verify.mjs [options]

Options:
  --mode auto|work|android|full  Override automatic path planning
  --base REF                     Diff base (default: origin/main)
  --dry-run                      Print the plan without running commands
  --allow-dirty                  Include working tree changes; no clean-tree guarantee
  --help                         Show this help`)
}

async function main() {
  const options = parseArguments(process.argv.slice(2))
  if (options.help) {
    printHelp()
    return
  }

  const root = runGit(["rev-parse", "--show-toplevel"])
  const dirty = runGit(["status", "--porcelain"], { cwd: root })
  if (dirty && !options.allowDirty && !options.dryRun) {
    throw new Error(
      "Local verification requires a clean worktree; commit first or use " +
        "--allow-dirty for exploratory runs.",
    )
  }

  runGit(["rev-parse", "--verify", options.baseRef], { cwd: root })
  const paths = changedPaths(root, options.baseRef, options.allowDirty)
  const plan = planPullRequest(
    paths,
    appBuildDiff(root, options.baseRef, paths),
  )
  const headSha = runGit(["rev-parse", "HEAD"], { cwd: root })
  const commands = buildVerificationCommands({
    plan,
    mode: options.mode,
    platform: process.platform,
    baseRef: options.baseRef,
    headRef: headSha,
  })

  console.log(`Local verification plan: ${plan.reason}`)
  console.log(`Mode: ${options.mode}`)
  console.log(`Changed paths: ${paths.length}`)
  for (const command of commands) console.log(`- ${command.label}`)
  if (options.dryRun) return

  const startedAt = new Date()
  const commandEnvironment = buildCommandEnvironment()
  for (const command of commands) {
    runCommand(root, command, process.platform, commandEnvironment)
  }
  const completedAt = new Date()
  const diff = runGit(["diff", "--binary", `${options.baseRef}...HEAD`], {
    cwd: root,
  })
  const receipt = {
    schemaVersion: 1,
    result: "passed",
    branch: runGit(["branch", "--show-current"], { cwd: root }),
    headSha,
    baseRef: options.baseRef,
    diffSha256: createHash("sha256").update(diff).digest("hex"),
    mode: options.mode,
    plan,
    changedPaths: paths,
    commands: commands.map((command) => command.label),
    startedAt: startedAt.toISOString(),
    completedAt: completedAt.toISOString(),
    durationSeconds: Math.round((completedAt - startedAt) / 1000),
  }
  const receiptPath = writeReceipt(root, receipt)
  console.log(`\nLocal verification passed. Receipt: ${receiptPath}`)
}

const isEntryPoint =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href

if (isEntryPoint) {
  main().catch((error) => {
    console.error(error.message)
    process.exitCode = 1
  })
}
