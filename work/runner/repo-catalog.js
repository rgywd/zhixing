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
    const runtimes = runtimeCatalog(repo, config);
    const legacy = legacyCodexCatalog(runtimes);
    repositories.push({
      ...repo,
      path,
      group: repo.group ?? null,
      runtimes,
      ...legacy,
      available: Boolean(canonicalPath),
    });
  }

  for (const root of config.repoRoots ?? []) {
    const rootPath = canonicalDirectory(expandPath(root.path, env));
    if (!rootPath) continue;
    const runtimes = runtimeCatalog(root, config);
    const legacy = legacyCodexCatalog(runtimes);
    for (const candidate of discoverRoot(rootPath, root)) {
      const key = pathKey(candidate.path);
      if (claimedPaths.has(key)) continue;
      claimedPaths.add(key);
      repositories.push({
        id: discoveredRepoId(root.id, candidate.relativePath),
        name: candidate.relativePath.split(sep).join(" / "),
        group: root.name,
        path: candidate.path,
        runtimes,
        ...legacy,
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
    if (!repo.id || !repo.name || !repo.path) {
      throw new Error(`Repository ${repo.id ?? "<unknown>"} is incomplete`);
    }
    validateRuntimeCatalog(runtimeCatalog(repo, config), `Repository ${repo.id}`);
  }
  for (const root of roots) {
    if (!root.id || !root.name || !root.path || !["children", "projects"].includes(root.strategy)) {
      throw new Error(`Repository root ${root.id ?? "<unknown>"} is incomplete`);
    }
    validateRuntimeCatalog(runtimeCatalog(root, config), `Repository root ${root.id}`);
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
    runtimes: (repo.runtimes ?? [{
      id: "codex",
      name: "Codex",
      models: repo.models ?? [],
      reasoningEfforts: repo.reasoningEfforts ?? [],
    }]).map(publicRuntime),
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

function runtimeCatalog(source, config) {
  const configured = source.runtimes ?? config.defaultRuntimes;
  if (Array.isArray(configured) && configured.length) {
    return configured.map((runtime) => ({
      id: String(runtime.id ?? "").trim(),
      name: String(runtime.name ?? runtime.id ?? "").trim(),
      command: String(runtime.command ?? defaultRuntimeCommand(runtime.id)).trim(),
      models: [...(runtime.models ?? [])],
      reasoningEfforts: [...(runtime.reasoningEfforts ?? [])],
      reasoningEffortsByModel: cloneReasoningEffortOverrides(runtime.reasoningEffortsByModel),
    }));
  }
  const models = source.models ?? config.defaultModels ?? [];
  const reasoningEfforts = source.reasoningEfforts ?? config.defaultReasoningEfforts ?? [];
  return [{
    id: "codex",
    name: "Codex",
    command: String(config.codexCommand ?? "codex"),
    models,
    reasoningEfforts,
    reasoningEffortsByModel: {},
  }];
}

function validateRuntimeCatalog(runtimes, owner) {
  if (!runtimes.length) throw new Error(`${owner} needs at least one runtime`);
  const seen = new Set();
  for (const runtime of runtimes) {
    if (
      !["codex", "claude-code"].includes(runtime.id)
      || !runtime.name
      || !runtime.command
      || !runtime.models.length
      || !runtime.reasoningEfforts.length
      || !validReasoningEffortOverrides(runtime)
      || seen.has(runtime.id)
    ) {
      throw new Error(`${owner} has an invalid runtime catalog`);
    }
    seen.add(runtime.id);
  }
}

function legacyCodexCatalog(runtimes) {
  const codex = runtimes.find((runtime) => runtime.id === "codex");
  return {
    models: codex?.models ?? [],
    reasoningEfforts: codex?.reasoningEfforts ?? [],
  };
}

function publicRuntime(runtime) {
  return {
    id: runtime.id,
    name: runtime.name,
    models: runtime.models,
    reasoningEfforts: runtime.reasoningEfforts,
    reasoningEffortsByModel: runtime.reasoningEffortsByModel ?? {},
  };
}

function cloneReasoningEffortOverrides(value) {
  if (value == null) return {};
  if (typeof value !== "object" || Array.isArray(value)) return value;
  return Object.fromEntries(Object.entries(value).map(([model, efforts]) => [
    model,
    Array.isArray(efforts) ? [...efforts] : efforts,
  ]));
}

function validReasoningEffortOverrides(runtime) {
  const overrides = runtime.reasoningEffortsByModel ?? {};
  return (
    typeof overrides === "object"
    && !Array.isArray(overrides)
    && Object.entries(overrides).every(([model, efforts]) =>
      runtime.models.includes(model)
      && Array.isArray(efforts)
      && efforts.length > 0
      && efforts.every((effort) => runtime.reasoningEfforts.includes(effort)),
    )
  );
}

function defaultRuntimeCommand(runtimeId) {
  return runtimeId === "claude-code" ? "claude" : "codex";
}
