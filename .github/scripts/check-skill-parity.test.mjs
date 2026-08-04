import assert from "node:assert/strict"
import { mkdtemp, mkdir, rm, writeFile } from "node:fs/promises"
import { tmpdir } from "node:os"
import path from "node:path"
import test from "node:test"

import {
  compareSkillTrees,
  formatDifferences,
  hasDifferences,
} from "./check-skill-parity.mjs"

async function withSkillTrees(callback) {
  const root = await mkdtemp(path.join(tmpdir(), "zhixing-skill-parity-"))
  const agentRoot = path.join(root, ".agents", "skills")
  const claudeRoot = path.join(root, ".claude", "skills")
  await Promise.all([
    mkdir(agentRoot, { recursive: true }),
    mkdir(claudeRoot, { recursive: true }),
  ])
  try {
    await callback({ agentRoot, claudeRoot })
  } finally {
    await rm(root, { recursive: true, force: true })
  }
}

async function writeTreeFile(root, relativePath, content) {
  const filePath = path.join(root, relativePath)
  await mkdir(path.dirname(filePath), { recursive: true })
  await writeFile(filePath, content)
}

test("accepts identical nested skill trees", async () => {
  await withSkillTrees(async ({ agentRoot, claudeRoot }) => {
    const relativePath = path.join("example", "references", "contract.md")
    await Promise.all([
      writeTreeFile(agentRoot, relativePath, "same content\n"),
      writeTreeFile(claudeRoot, relativePath, "same content\n"),
    ])

    const differences = await compareSkillTrees(agentRoot, claudeRoot)

    assert.equal(hasDifferences(differences), false)
    assert.deepEqual(differences, {
      onlyInAgents: [],
      onlyInClaude: [],
      differentContent: [],
    })
  })
})

test("reports files missing from either mirror", async () => {
  await withSkillTrees(async ({ agentRoot, claudeRoot }) => {
    await Promise.all([
      writeTreeFile(agentRoot, "agent-only/SKILL.md", "agent\n"),
      writeTreeFile(claudeRoot, "claude-only/SKILL.md", "claude\n"),
    ])

    const differences = await compareSkillTrees(agentRoot, claudeRoot)

    assert.deepEqual(differences.onlyInAgents, ["agent-only/SKILL.md"])
    assert.deepEqual(differences.onlyInClaude, ["claude-only/SKILL.md"])
    assert.match(formatDifferences(differences), /Only in \.agents\/skills/)
    assert.match(formatDifferences(differences), /Only in \.claude\/skills/)
  })
})

test("compares file contents byte-for-byte", async () => {
  await withSkillTrees(async ({ agentRoot, claudeRoot }) => {
    await Promise.all([
      writeTreeFile(agentRoot, "example/SKILL.md", "content\n"),
      writeTreeFile(claudeRoot, "example/SKILL.md", "content\r\n"),
    ])

    const differences = await compareSkillTrees(agentRoot, claudeRoot)

    assert.deepEqual(differences.differentContent, ["example/SKILL.md"])
    assert.equal(hasDifferences(differences), true)
  })
})
