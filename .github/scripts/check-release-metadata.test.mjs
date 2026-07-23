import assert from "node:assert/strict"
import test from "node:test"

import {
  isGreaterSemver,
  parseVersion,
  validateVersionAdvance,
} from "./check-release-metadata.mjs"

const base = `
android {
  defaultConfig {
    versionCode = 27
    versionName = "0.3.9"
  }
}`

test("parses Android version metadata", () => {
  assert.deepEqual(parseVersion(base), { code: 27, name: "0.3.9" })
})

test("accepts an increasing release version", () => {
  const current = base
    .replace("versionCode = 27", "versionCode = 28")
    .replace('versionName = "0.3.9"', 'versionName = "0.3.10"')

  assert.deepEqual(validateVersionAdvance(base, current), {
    code: 28,
    name: "0.3.10",
  })
  assert.equal(isGreaterSemver("0.4.0", "0.3.10"), true)
})

test("rejects non-increasing version metadata", () => {
  assert.throws(() => validateVersionAdvance(base, base), /versionCode/)

  const lowerName = base
    .replace("versionCode = 27", "versionCode = 28")
    .replace('versionName = "0.3.9"', 'versionName = "0.3.8"')
  assert.throws(
    () => validateVersionAdvance(base, lowerName),
    /versionName/,
  )
})

test("rejects non-semver release names", () => {
  assert.throws(() => isGreaterSemver("0.3.10-beta", "0.3.9"), /X.Y.Z/)
})
