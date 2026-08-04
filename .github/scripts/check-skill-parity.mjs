#!/usr/bin/env node

import { readFile, readdir } from "node:fs/promises"
import path from "node:path"
import process from "node:process"
import { pathToFileURL } from "node:url"

const DEFAULT_AGENT_ROOT = ".agents/skills"
const DEFAULT_CLAUDE_ROOT = ".claude/skills"

function toPortablePath(filePath) {
  return filePath.split(path.sep).join("/")
}

async function listFiles(root, current = root) {
  const entries = await readdir(current, { withFileTypes: true })
  entries.sort((left, right) =>
    left.name < right.name ? -1 : left.name > right.name ? 1 : 0,
  )

  const files = []
  for (const entry of entries) {
    const entryPath = path.join(current, entry.name)
    if (entry.isDirectory()) {
      files.push(...(await listFiles(root, entryPath)))
    } else if (entry.isFile()) {
      files.push(toPortablePath(path.relative(root, entryPath)))
    } else {
      throw new Error(`Unsupported entry in skill tree: ${entryPath}`)
    }
  }
  return files
}

export async function compareSkillTrees(agentRoot, claudeRoot) {
  const [agentFiles, claudeFiles] = await Promise.all([
    listFiles(agentRoot),
    listFiles(claudeRoot),
  ])
  const agentSet = new Set(agentFiles)
  const claudeSet = new Set(claudeFiles)
  const onlyInAgents = agentFiles.filter((file) => !claudeSet.has(file))
  const onlyInClaude = claudeFiles.filter((file) => !agentSet.has(file))
  const sharedFiles = agentFiles.filter((file) => claudeSet.has(file))

  const contentMatches = await Promise.all(
    sharedFiles.map(async (file) => {
      const [agentContent, claudeContent] = await Promise.all([
        readFile(path.join(agentRoot, file)),
        readFile(path.join(claudeRoot, file)),
      ])
      return [file, agentContent.equals(claudeContent)]
    }),
  )
  const differentContent = contentMatches
    .filter(([, matches]) => !matches)
    .map(([file]) => file)

  return { onlyInAgents, onlyInClaude, differentContent }
}

export function formatDifferences(differences) {
  const lines = [
    "Repository skills must match byte-for-byte in .agents/skills and .claude/skills.",
  ]
  const sections = [
    ["Only in .agents/skills", differences.onlyInAgents],
    ["Only in .claude/skills", differences.onlyInClaude],
    ["Different content", differences.differentContent],
  ]
  for (const [label, files] of sections) {
    if (files.length > 0) {
      lines.push(`${label}:`)
      lines.push(...files.map((file) => `  - ${file}`))
    }
  }
  return lines.join("\n")
}

export function hasDifferences(differences) {
  return Object.values(differences).some((files) => files.length > 0)
}

async function main() {
  const agentRoot = process.argv[2] ?? DEFAULT_AGENT_ROOT
  const claudeRoot = process.argv[3] ?? DEFAULT_CLAUDE_ROOT
  const differences = await compareSkillTrees(agentRoot, claudeRoot)
  if (hasDifferences(differences)) {
    console.error(formatDifferences(differences))
    process.exitCode = 1
    return
  }
  console.log(
    `Skill mirrors match: ${agentRoot} and ${claudeRoot}`,
  )
}

const isEntryPoint =
  process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href

if (isEntryPoint) {
  main().catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
}
