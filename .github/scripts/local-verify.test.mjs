import assert from "node:assert/strict"
import test from "node:test"

import {
  buildCommandEnvironment,
  buildVerificationCommands,
  commandNeedsShell,
} from "./local-verify.mjs"

const workPlan = {
  run_work: "true",
  run_android: "false",
  metadata_only: "false",
  reason: "work-only",
}

const androidPlan = {
  run_work: "false",
  run_android: "true",
  metadata_only: "false",
  reason: "android-only",
}

const documentationPlan = {
  run_work: "false",
  run_android: "false",
  metadata_only: "false",
  reason: "documentation-only",
}

function labels(commands) {
  return commands.map((command) => command.label)
}

test("Work-only verification excludes Android commands", () => {
  const commands = buildVerificationCommands({
    plan: workPlan,
    platform: "win32",
  })

  assert.deepEqual(labels(commands), [
    "Repository policy tests",
    "Skill mirror parity",
    "Install Work dependencies",
    "Work tests",
    "Staging driver tests",
  ])
  assert.equal(commands.some((command) => command.command === "gradlew.bat"), false)
})

test("Android-only verification runs tests, lint, and builds locally", () => {
  const commands = buildVerificationCommands({
    plan: androidPlan,
    platform: "linux",
  })

  assert.deepEqual(labels(commands), [
    "Repository policy tests",
    "Skill mirror parity",
    "Initialize submodules",
    "Install web dependencies",
    "Android unit tests",
    "Android lint",
    "Android build smoke",
  ])
  assert.equal(commands.filter((command) => command.command === "./gradlew").length, 3)
  assert.ok(commands.find((command) => command.label === "Android build smoke").args.includes(":work-app:assembleStaging"))
  assert.ok(commands.find((command) => command.label === "Android lint").args.includes(":work-app:lintStaging"))
})

test("full mode overrides a documentation-only path plan", () => {
  const commands = buildVerificationCommands({
    plan: documentationPlan,
    mode: "full",
    platform: "linux",
  })

  assert.equal(labels(commands).includes("Work tests"), true)
  assert.equal(labels(commands).includes("Android build smoke"), true)
})

test("documentation-only verification stays lightweight", () => {
  const commands = buildVerificationCommands({
    plan: documentationPlan,
    platform: "linux",
  })

  assert.deepEqual(labels(commands), [
    "Repository policy tests",
    "Skill mirror parity",
  ])
})

test("release metadata verification adds the metadata validator", () => {
  const commands = buildVerificationCommands({
    plan: {
      run_work: "false",
      run_android: "false",
      metadata_only: "true",
      reason: "release-metadata-only",
    },
    platform: "linux",
    baseRef: "base-sha",
    headRef: "head-sha",
  })

  assert.equal(labels(commands).at(-1), "Release metadata")
  assert.deepEqual(commands.at(-1).args.slice(-2), ["base-sha", "head-sha"])
})

test("Windows uses a shell only for command wrappers", () => {
  assert.equal(commandNeedsShell("npm.cmd", "win32"), true)
  assert.equal(commandNeedsShell("gradlew.bat", "win32"), true)
  assert.equal(commandNeedsShell("C:\\Program Files\\nodejs\\node.exe", "win32"), false)
  assert.equal(commandNeedsShell("./gradlew", "linux"), false)
})

test("uses the standard Android SDK directory without reading local.properties", () => {
  const environment = buildCommandEnvironment({
    platform: "win32",
    environment: { LOCALAPPDATA: "C:\\Users\\test\\AppData\\Local" },
    pathExists: (candidate) => candidate.endsWith("Android\\Sdk"),
  })

  assert.equal(
    environment.ANDROID_HOME,
    "C:\\Users\\test\\AppData\\Local\\Android\\Sdk",
  )
  assert.equal(environment.ANDROID_SDK_ROOT, environment.ANDROID_HOME)
})

test("preserves an explicitly configured Android SDK", () => {
  const environment = buildCommandEnvironment({
    platform: "linux",
    environment: { ANDROID_HOME: "/opt/android-sdk" },
    pathExists: () => false,
  })

  assert.equal(environment.ANDROID_HOME, "/opt/android-sdk")
  assert.equal(environment.ANDROID_SDK_ROOT, undefined)
})
