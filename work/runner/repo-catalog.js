import { createHash } from "node:crypto";
import { existsSync, readdirSync, realpathSync, statSync } from "node:fs";
import { homedir } from "node:os";
import { basename, isAbsolute, relative, resolve, sep } from "node:path";

const DEFAULT_MARKERS = [
  ".git",
  "AGENTS.md",
  "package.json",
  "pyproject.toml",
  "settings.gradle",
  "settings.gradle.kts",
  "Cargo.toml",
  "go.mod",
];

const DEFAULT_EXCLUDES = new Set([
  ".git",
  ".gradle",
  ".idea",
  ".venv",
  "build",
  "dist",
  "node_modules",
  "out",
  "target",
  "venv",
]);

export function buildRepositoryCatalog(config, env = process.env) {
  const repositories = [];
  const claimedPaths = new Set();

  for (const repo of config.repos ?? []) {
    const path = expandPath(repo.path, env);
    const canonicalPath = canonicalDirectory(path);
    if (canonicalPath) claimedPaths.add(pathKey(canonicalPath));
    repositories.push({
      ...repo,
      path,
      group: repo.group ?? null,
      available: Boolean(canonicalPath),
    });
  }

  for (const root of config.repoRoots ?? []) {
    const rootPath = canonicalDirectory(expandPath(root.path, env));
    if (!rootPath) continue;
    const models = root.models ?? config.defaultModels;
    const reasoningEfforts = root.reasoningEfforts ?? config.defaultReasoningEfforts;
    for (const candidate of discoverRoot(rootPath, root)) {
      const key = pathKey(candidate.path);
      if (claimedPaths.has(key)) continue;
      claimedPaths.add(key);
      repositories.push({
        id: discoveredRepoId(root.id, candidate.relativePath),
        name: candidate.relativePath.split(sep).join(" / "),
        group: root.name,
        path: candidate.path,
        models,
        reasoningEfforts,
        available: true,
      });
    }
  }

  return repositories.sort((left, right) =>
    String(left.group ?? "").localeCompare(String(right.group ?? ""))
      || left.name.localeCompare(right.name),
  );
}

export function validateRepositoryConfig(config) {
  const repos = config.repos ?? [];
  const roots = config.repoRoots ?? [];
  if (!Array.isArray(repos) || !Array.isArray(roots) || (!repos.length && !roots.length)) {
    throw new Error("Runner config needs at least one repository or repository root");
  }
  for (const repo of repos) {
    if (!repo.id || !repo.name || !repo.path || !repo.models?.length || !repo.reasoningEfforts?.length) {
      throw new Error(`Repository ${repo.id ?? "<unknown>"} is incomplete`);
    }
  }
  for (const root of roots) {
    const models = root.models ?? config.defaultModels;
    const efforts = root.reasoningEfforts ?? config.defaultReasoningEfforts;
    if (!root.id || !root.name || !root.path || !["children", "projects"].includes(root.strategy)) {
      throw new Error(`Repository root ${root.id ?? "<unknown>"} is incomplete`);
    }
    if (!models?.length || !efforts?.length) {
      throw new Error(`Repository root ${root.id} needs models and reasoning efforts`);
    }
    if (root.maxDepth != null && (!Number.isInteger(root.maxDepth) || root.maxDepth < 0 || root.maxDepth > 8)) {
      throw new Error(`Repository root ${root.id} maxDepth must be between 0 and 8`);
    }
  }
}

export function repositoryCatalogFingerprint(repositories) {
  return createHash("sha256").update(JSON.stringify(repositories.map((repo) => ({
    id: repo.id,
    name: repo.name,
    group: repo.group ?? null,
    models: repo.models,
    reasoningEfforts: repo.reasoningEfforts,
    available: repo.available !== false,
  })))).digest("hex");
}

export function expandPath(input, env = process.env) {
  let value = String(input ?? "").trim();
  if (value === "~" || value.startsWith(`~${sep}`) || value.startsWith("~/") || value.startsWith("~\\")) {
    value = resolve(homedir(), value.slice(2));
  }
  value = value.replace(/%([^%]+)%/g, (match, name) => env[name] ?? match);
  value = value.replace(/\$\{([^}]+)\}/g, (match, name) => env[name] ?? match);
  return resolve(value);
}

function discoverRoot(rootPath, root) {
  if (root.strategy === "children") {
    return childDirectories(rootPath, root)
      .map((path) => ({ path, relativePath: relative(rootPath, path) }))
      .filter((candidate) => candidate.relativePath);
  }

  const markers = root.markers?.length ? root.markers : DEFAULT_MARKERS;
  const maxDepth = root.maxDepth ?? 3;
  const discovered = [];
  const visit = (directory, depth) => {
    if (markers.some((marker) => existsSync(resolve(directory, marker)))) {
      discovered.push({ path: directory, relativePath: relative(rootPath, directory) || basename(directory) });
      return;
    }
    if (depth >= maxDepth) return;
    for (const child of childDirectories(directory, root)) visit(child, depth + 1);
  };
  visit(rootPath, 0);
  return discovered;
}

function childDirectories(directory, root) {
  const excludes = new Set([...DEFAULT_EXCLUDES, ...(root.exclude ?? [])].map((name) => name.toLowerCase()));
  let entries;
  try {
    entries = readdirSync(directory, { withFileTypes: true });
  } catch {
    return [];
  }
  return entries
    .filter((entry) =>
      entry.isDirectory()
      && !entry.isSymbolicLink()
      && (root.includeHidden === true || !entry.name.startsWith("."))
      && !excludes.has(entry.name.toLowerCase()),
    )
    .map((entry) => canonicalDirectory(resolve(directory, entry.name)))
    .filter((path) => path && isWithin(directory, path));
}

function canonicalDirectory(path) {
  try {
    if (!statSync(path).isDirectory()) return null;
    return realpathSync.native(path);
  } catch {
    return null;
  }
}

function isWithin(root, candidate) {
  const value = relative(root, candidate);
  return value === "" || (!value.startsWith(`..${sep}`) && value !== ".." && !isAbsolute(value));
}

function pathKey(path) {
  return process.platform === "win32" ? path.toLowerCase() : path;
}

function discoveredRepoId(rootId, relativePath) {
  const normalized = relativePath.split(sep).join("/").toLowerCase();
  const suffix = createHash("sha256").update(`${rootId}\0${normalized}`).digest("hex").slice(0, 20);
  return `discovered-${rootId}-${suffix}`;
}
