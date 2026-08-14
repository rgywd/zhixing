import assert from "node:assert/strict";
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  buildRepositoryCatalog,
  expandPath,
  repositoryCatalogFingerprint,
  validateRepositoryConfig,
} from "./repo-catalog.js";
import { WorkRunner } from "./runner.js";
import { RunnerState } from "./state.js";

const MODELS = ["gpt-5.6-sol"];
const EFFORTS = ["high"];

test("children roots discover immediate directories and ignore generated folders", () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-catalog-children-"));
  try {
    mkdirSync(join(directory, "project-one"));
    mkdirSync(join(directory, "project-two"));
    mkdirSync(join(directory, ".private"));
    mkdirSync(join(directory, "node_modules"));
    const repos = buildRepositoryCatalog({
      defaultModels: MODELS,
      defaultReasoningEfforts: EFFORTS,
      repoRoots: [{ id: "workspace", name: "Workspace", path: directory, strategy: "children" }],
    });

    assert.deepEqual(repos.map((repo) => [repo.group, repo.name]), [
      ["Workspace", "project-one"],
      ["Workspace", "project-two"],
    ]);
    assert.ok(repos.every((repo) => repo.available));
    assert.ok(repos.every((repo) => repo.models === MODELS));
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("project roots stop at the first project marker and respect max depth", () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-catalog-projects-"));
  try {
    mkdirSync(join(directory, "company", "service", ".git"), { recursive: true });
    writeFileSync(join(directory, "company", "service", "package.json"), "{}");
    mkdirSync(join(directory, "company", "service", "nested"));
    writeFileSync(join(directory, "company", "service", "nested", "package.json"), "{}");
    mkdirSync(join(directory, "too", "deep", "project"), { recursive: true });
    writeFileSync(join(directory, "too", "deep", "project", "go.mod"), "module example");

    const repos = buildRepositoryCatalog({
      defaultModels: MODELS,
      defaultReasoningEfforts: EFFORTS,
      repoRoots: [{ id: "documents", name: "Documents", path: directory, strategy: "projects", maxDepth: 2 }],
    });

    assert.deepEqual(repos.map((repo) => repo.name), ["company / service"]);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("explicit repositories remain compatible and missing paths become unavailable", () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-catalog-explicit-"));
  try {
    const missing = join(directory, "deleted");
    const repos = buildRepositoryCatalog({
      repos: [
        { id: "existing", name: "Existing", path: directory, models: MODELS, reasoningEfforts: EFFORTS },
        { id: "missing", name: "Missing", path: missing, models: MODELS, reasoningEfforts: EFFORTS },
      ],
    });

    assert.equal(repos.find((repo) => repo.id === "existing").available, true);
    assert.equal(repos.find((repo) => repo.id === "missing").available, false);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("catalog paths expand environment variables without exposing them in ids", () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-catalog-env-"));
  try {
    mkdirSync(join(directory, "repo"));
    const repos = buildRepositoryCatalog({
      defaultModels: MODELS,
      defaultReasoningEfforts: EFFORTS,
      repoRoots: [{ id: "work", name: "Work", path: "%TEST_WORK_ROOT%", strategy: "children" }],
    }, { TEST_WORK_ROOT: directory });

    assert.equal(expandPath("%TEST_WORK_ROOT%", { TEST_WORK_ROOT: directory }), directory);
    assert.equal(repos.length, 1);
    assert.doesNotMatch(repos[0].id, /repo|zhixing-catalog/i);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("catalog validation accepts roots and fingerprints availability changes", () => {
  assert.doesNotThrow(() => validateRepositoryConfig({
    defaultModels: MODELS,
    defaultReasoningEfforts: EFFORTS,
    repoRoots: [{ id: "workspace", name: "Workspace", path: "D:/workspace", strategy: "children" }],
  }));
  assert.throws(() => validateRepositoryConfig({ repoRoots: [] }), /at least one/);
  assert.notEqual(
    repositoryCatalogFingerprint([{ id: "one", name: "One", models: MODELS, reasoningEfforts: EFFORTS, available: true }]),
    repositoryCatalogFingerprint([{ id: "one", name: "One", models: MODELS, reasoningEfforts: EFFORTS, available: false }]),
  );
});

test("catalog preserves model-specific reasoning efforts and rejects invalid overrides", () => {
  const runtime = {
    id: "codex",
    name: "Codex",
    models: ["gpt-5.6-sol", "gpt-5.3-codex-spark"],
    reasoningEfforts: ["low", "medium", "high", "xhigh", "max"],
    reasoningEffortsByModel: {
      "gpt-5.3-codex-spark": ["low", "medium", "high", "xhigh"],
    },
  };
  const config = {
    defaultRuntimes: [runtime],
    repos: [{ id: "repo", name: "Repo", path: process.cwd() }],
  };

  assert.doesNotThrow(() => validateRepositoryConfig(config));
  assert.deepEqual(
    buildRepositoryCatalog(config)[0].runtimes[0].reasoningEffortsByModel,
    runtime.reasoningEffortsByModel,
  );
  assert.deepEqual(buildRepositoryCatalog(config)[0].runtimes[0].fastModels, ["gpt-5.6-sol"]);
  assert.throws(() => validateRepositoryConfig({
    ...config,
    defaultRuntimes: [{
      ...runtime,
      reasoningEffortsByModel: { "unknown-model": ["high"] },
    }],
  }), /invalid runtime catalog/);
  assert.throws(() => validateRepositoryConfig({
    ...config,
    defaultRuntimes: [{
      ...runtime,
      reasoningEffortsByModel: { "gpt-5.3-codex-spark": ["ultra"] },
    }],
  }), /invalid runtime catalog/);
  assert.throws(() => validateRepositoryConfig({
    ...config,
    defaultRuntimes: [{ ...runtime, fastModels: ["gpt-5.3-codex-spark", "unknown-model"] }],
  }), /invalid runtime catalog/);
});

test("runner republishes the catalog when a discovered directory changes", async () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-catalog-refresh-"));
  try {
    mkdirSync(join(directory, "first"));
    const registrations = [];
    const config = {
      id: "runner",
      name: "Runner",
      version: "test",
      coreUrl: "https://core",
      token: "token",
      stateFile: join(directory, "state.json"),
      codexHome: join(directory, "codex-home"),
      defaultModels: MODELS,
      defaultReasoningEfforts: EFFORTS,
      repoRoots: [{ id: "workspace", name: "Workspace", path: directory, strategy: "children", exclude: ["codex-home"] }],
    };
    const runner = new WorkRunner({
      config,
      state: new RunnerState(config.stateFile),
      client: { register: async (value) => registrations.push(value.repos.map((repo) => repo.name)) },
    });

    assert.equal(await runner.refreshCatalog(true), true);
    mkdirSync(join(directory, "second"));
    assert.equal(await runner.refreshCatalog(true), true);
    assert.deepEqual(registrations, [["first"], ["first", "second"]]);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
