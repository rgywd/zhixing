import assert from "node:assert/strict";
import { mkdtempSync, readFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { buildCodexArgs, parseCodexSessionId, resolveCodexCommand } from "./codex-process.js";
import { WorkRunner } from "./runner.js";
import { RunnerState } from "./state.js";

test("Codex args isolate user config and fix model, effort, access and phone-line MCP", () => {
  const args = buildCodexArgs({
    kind: "START",
    repoPath: "C:/repo",
    model: "gpt-5.6-sol",
    reasoningEffort: "high",
    mcp: {
      nodePath: "C:/node.exe",
      mcpServerPath: "C:/mcp-server.js",
      coreUrl: "https://core.example.com",
      sessionId: "work-1",
      sessionToken: "secret",
      cursorFile: "C:/cursor.json",
    },
  });
  assert.deepEqual(args.slice(0, 4), ["exec", "-C", "C:/repo", "--json"]);
  assert.ok(args.includes("--ignore-user-config"));
  assert.ok(args.includes("--dangerously-bypass-approvals-and-sandbox"));
  assert.ok(args.includes("model_reasoning_effort=\"high\""));
  assert.ok(args.some((arg) => arg.startsWith("mcp_servers.zhixing_phone.command=")));
  assert.equal(args.at(-1), "-");
});

test("resume targets the persisted Codex session", () => {
  const args = buildCodexArgs({
    kind: "RESUME",
    repoPath: "C:/repo",
    model: "gpt-5.6-sol",
    reasoningEffort: "xhigh",
    codexSessionId: "019f-session",
    mcp: {
      nodePath: "node",
      mcpServerPath: "mcp.js",
      coreUrl: "https://core",
      sessionId: "work-1",
      sessionToken: "token",
      cursorFile: "cursor.json",
    },
  });
  assert.deepEqual(args.slice(0, 3), ["exec", "resume", "--json"]);
  assert.deepEqual(args.slice(-2), ["019f-session", "-"]);
});

test("runner persists discovered session id and completes one turn", async () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-runner-"));
  const state = new RunnerState(join(directory, "state.json"));
  const calls = [];
  const client = {
    ack: async (...args) => calls.push(["ack", ...args]),
    updateState: async (...args) => calls.push(["state", ...args]),
  };
  let resolveProcess;
  const spawnCodex = ({ onEvent }) => {
    onEvent({ type: "thread.started", thread_id: "019f-codex" });
    return {
      child: { kill() {} },
      completed: new Promise((resolve) => { resolveProcess = resolve; }),
    };
  };
  const runner = new WorkRunner({
    config: {
      id: "runner",
      coreUrl: "https://core",
      stateFile: join(directory, "state.json"),
      repos: [{
        id: "repo",
        name: "repo",
        path: directory,
        models: ["gpt-5.6-sol"],
        reasoningEfforts: ["high"],
      }],
    },
    state,
    client,
    spawnCodex,
  });
  await runner.startCommand({
    id: "cmd-1",
    sessionId: "work-1",
    kind: "START",
    payload: {
      repoId: "repo",
      model: "gpt-5.6-sol",
      reasoningEffort: "high",
      sessionToken: "session-token",
      message: "hello",
    },
  });
  resolveProcess({ code: 0, signal: null, stderr: "" });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(state.get("work-1").codexSessionId, "019f-codex");
  assert.ok(calls.some((call) => call[0] === "ack" && call[2] === "COMPLETED"));
  const completed = calls.find((call) => call[0] === "ack" && call[2] === "COMPLETED");
  assert.equal(completed[3].status, "IDLE");
  assert.equal(completed[3].codexSessionId, "019f-codex");
  assert.ok(!calls.some((call) => call[0] === "state" && call[2] === "IDLE"));
  const persisted = JSON.parse(readFileSync(join(directory, "state.json"), "utf8"));
  assert.equal(persisted.sessions["work-1"].sessionToken, "session-token");
  assert.deepEqual(persisted.outbox, {});
});

test("runner durably replays a final transition after an extended Core outage", async () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-runner-outbox-"));
  const stateFile = join(directory, "state.json");
  const state = new RunnerState(stateFile);
  let resolveProcess;
  const failingClient = {
    ack: async (_commandId, status) => {
      if (status === "COMPLETED") throw new Error("Core unavailable");
    },
    updateState: async () => {},
  };
  const runner = new WorkRunner({
    config: {
      id: "runner",
      coreUrl: "https://core",
      stateFile,
      repos: [{
        id: "repo",
        name: "repo",
        path: directory,
        models: ["gpt-5.6-sol"],
        reasoningEfforts: ["high"],
      }],
    },
    state,
    client: failingClient,
    spawnCodex: ({ onEvent }) => {
      onEvent({ type: "thread.started", thread_id: "019f-outbox" });
      return {
        child: { kill() {} },
        completed: new Promise((resolve) => { resolveProcess = resolve; }),
      };
    },
  });
  await runner.startCommand({
    id: "cmd-outbox",
    sessionId: "work-outbox",
    kind: "START",
    payload: {
      repoId: "repo",
      model: "gpt-5.6-sol",
      reasoningEffort: "high",
      sessionToken: "session-token",
      message: "hello",
    },
  });
  resolveProcess({ code: 0, signal: null, stderr: "" });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(runner.active.size, 0);
  assert.equal(state.transitions().length, 1);
  assert.equal(state.transitions()[0].sessionState.status, "IDLE");

  const replayed = [];
  const recoveredState = new RunnerState(stateFile);
  const recoveredRunner = new WorkRunner({
    config: runner.config,
    state: recoveredState,
    client: { ack: async (...args) => replayed.push(args) },
  });
  await recoveredRunner.flushOutbox();
  assert.equal(replayed[0][0], "cmd-outbox");
  assert.equal(replayed[0][1], "COMPLETED");
  assert.equal(replayed[0][2].status, "IDLE");
  assert.equal(recoveredState.transitions().length, 0);
});

test("stop kills the local Codex process before attempting Core acknowledgement", async () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-runner-stop-"));
  const state = new RunnerState(join(directory, "state.json"));
  let killed = false;
  const runner = new WorkRunner({
    config: { id: "runner", stateFile: state.filename, repos: [] },
    state,
    client: { ack: async () => { throw new Error("Core unavailable"); } },
  });
  runner.active.set("work-stop", {
    startCommandId: "cmd-start",
    stoppedByUser: false,
    child: { kill: () => { killed = true; } },
  });

  await assert.rejects(() => runner.stopCommand({
    id: "cmd-stop",
    sessionId: "work-stop",
    kind: "STOP",
  }), /Core unavailable/);

  assert.equal(killed, true);
  assert.equal(runner.active.get("work-stop").stoppedByUser, true);
  assert.equal(runner.active.get("work-stop").startCommandAcknowledged, true);
  assert.deepEqual(state.transitions().map((item) => item.commandId).sort(), ["cmd-start", "cmd-stop"]);
  assert.equal(state.transitions().find((item) => item.commandId === "cmd-stop").sessionState.status, "IDLE");
});

test("session id parser accepts current Codex JSONL event", () => {
  assert.equal(parseCodexSessionId({ type: "thread.started", thread_id: "abc" }), "abc");
  assert.equal(parseCodexSessionId({ type: "item.completed" }), null);
});

test("Windows resolves the real Codex executable instead of an npm shell shim", () => {
  assert.equal(resolveCodexCommand("codex", "win32", () => "C:/Codex/codex.exe"), "C:/Codex/codex.exe");
  assert.throws(
    () => resolveCodexCommand("C:/Users/me/AppData/Roaming/npm/codex.cmd", "win32"),
    /codex\.exe/,
  );
  assert.equal(resolveCodexCommand("codex", "linux"), "codex");
});
