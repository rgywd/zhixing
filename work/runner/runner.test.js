import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import test from "node:test";
import {
  buildCodexArgs,
  parseCodexAssistantMessage,
  parseCodexSessionId,
  resolveCodexCommand,
} from "./codex-process.js";
import { ensurePhoneHookProfile } from "./phone-hook-profile.js";
import { PHONE_DEVELOPER_INSTRUCTIONS, WorkRunner } from "./runner.js";
import { RunnerState } from "./state.js";

const testDirectory = dirname(fileURLToPath(import.meta.url));

test("Codex args isolate user config and fix model, effort, access and phone-line MCP", () => {
  const args = buildCodexArgs({
    kind: "START",
    repoPath: "C:/repo",
    model: "gpt-5.6-sol",
    reasoningEffort: "high",
    profileName: "zhixing-phone",
    developerInstructions: PHONE_DEVELOPER_INSTRUCTIONS,
    imagePaths: ["C:/temp/screen.png"],
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
  assert.deepEqual(args.slice(args.indexOf("--profile"), args.indexOf("--profile") + 2), ["--profile", "zhixing-phone"]);
  assert.ok(args.includes("--dangerously-bypass-hook-trust"));
  assert.ok(args.includes("--dangerously-bypass-approvals-and-sandbox"));
  const instructionOverride = args.find((arg) => arg.startsWith("developer_instructions="));
  assert.match(instructionOverride, /report\(text\)/);
  assert.match(instructionOverride, /ask\(questions\)/);
  assert.match(instructionOverride, /report_html\(html, title\)/);
  assert.match(instructionOverride, /final result before ending/);
  assert.ok(args.includes("model_reasoning_effort=\"high\""));
  assert.ok(args.some((arg) => arg.startsWith("mcp_servers.zhixing_phone.command=")));
  assert.deepEqual(args.slice(args.indexOf("--image"), args.indexOf("--image") + 2), ["--image", "C:/temp/screen.png"]);
  assert.equal(args.at(-1), "-");
});

test("Codex JSONL mapper only exposes completed assistant-visible messages", () => {
  assert.deepEqual(parseCodexAssistantMessage({
    type: "item.completed",
    item: { id: "item-1", type: "agent_message", text: "正在跑测试。" },
  }), { itemId: "item-1", text: "正在跑测试。" });
  assert.equal(parseCodexAssistantMessage({
    type: "item.started",
    item: { id: "item-1", type: "agent_message", text: "partial" },
  }), null);
  assert.equal(parseCodexAssistantMessage({
    type: "item.completed",
    item: { id: "item-2", type: "reasoning", text: "private" },
  }), null);
  assert.equal(parseCodexAssistantMessage({
    type: "item.completed",
    item: { id: "item-3", type: "command_execution", command: "secret" },
  }), null);
});

test("phone hook profile contains only a one-second fail-open Stop hook", () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-phone-profile-"));
  const result = ensurePhoneHookProfile({
    codexHome: directory,
    nodePath: process.execPath,
    hookScriptPath: join(testDirectory, "phone-stop-hook.js"),
  });
  const profile = readFileSync(result.profileFile, "utf8");
  assert.equal(result.profileName, "zhixing-phone");
  assert.match(profile, /\[\[hooks\.Stop\]\]/);
  assert.match(profile, /timeout = 1/);
  assert.doesNotMatch(profile, /PreToolUse|PostToolUse|PermissionRequest/);
});

test("phone Stop hook writes one small local marker and never emits output", () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-phone-hook-"));
  const hookScript = join(testDirectory, "phone-stop-hook.js");
  const result = spawnSync(process.execPath, [hookScript], {
    input: JSON.stringify({
      hook_event_name: "Stop",
      session_id: "codex-session",
      turn_id: "turn-1",
      transcript_path: "must-not-be-copied",
    }),
    encoding: "utf8",
    env: {
      ...process.env,
      ZHIXING_WORK_HOOK_OUTBOX: directory,
      ZHIXING_WORK_SESSION_ID: "work-session",
    },
  });
  assert.equal(result.status, 0);
  assert.equal(result.stdout, "");
  assert.equal(result.stderr, "");
  const files = readdirSync(directory);
  assert.equal(files.length, 1);
  const marker = JSON.parse(readFileSync(join(directory, files[0]), "utf8"));
  assert.deepEqual(marker, {
    version: 1,
    event: "Stop",
    workSessionId: "work-session",
    codexSessionId: "codex-session",
    turnId: "turn-1",
  });
  assert.ok(readFileSync(join(directory, files[0])).length < 1024);
});

test("runner downloads images for one Codex turn and removes the temporary files afterwards", async () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-runner-image-"));
  const state = new RunnerState(join(directory, "state.json"));
  let resolveProcess;
  let imagePath;
  const runner = new WorkRunner({
    config: {
      id: "runner",
      coreUrl: "https://core",
      stateFile: state.filename,
      repos: [{
        id: "repo",
        name: "repo",
        path: directory,
        models: ["gpt-5.6-sol"],
        reasoningEfforts: ["high"],
      }],
    },
    state,
    client: {
      ack: async () => {},
      updateState: async () => {},
      downloadAttachment: async () => ({ data: Buffer.from("image"), mimeType: "image/png" }),
    },
    spawnCodex: ({ args, onEvent }) => {
      imagePath = args[args.indexOf("--image") + 1];
      assert.equal(existsSync(imagePath), true);
      onEvent({ type: "thread.started", thread_id: "image-session" });
      return {
        child: { kill() {} },
        completed: new Promise((resolve) => { resolveProcess = resolve; }),
      };
    },
  });
  await runner.startCommand({
    id: "cmd-image",
    sessionId: "work-image",
    kind: "START",
    payload: {
      repoId: "repo",
      model: "gpt-5.6-sol",
      reasoningEffort: "high",
      sessionToken: "session-token",
      message: "inspect this",
      attachments: [{ id: "att-image", mimeType: "image/png" }],
    },
  });
  resolveProcess({ code: 0, signal: null, stderr: "" });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(existsSync(imagePath), false);
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
    publishEvent: async (...args) => calls.push(["event", ...args]),
  };
  let resolveProcess;
  const spawnCodex = ({ onEvent }) => {
    onEvent({ type: "thread.started", thread_id: "019f-codex" });
    onEvent({ type: "item.completed", item: { id: "agent-1", type: "agent_message", text: "阶段结果" } });
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
  const eventIndex = calls.findIndex((call) => call[0] === "event");
  const completedIndex = calls.findIndex((call) => call[0] === "ack" && call[2] === "COMPLETED");
  assert.ok(eventIndex >= 0 && eventIndex < completedIndex);
  assert.deepEqual(calls[eventIndex].slice(1), ["work-1", {
    clientEventId: "cmd-1:agent-1",
    type: "ASSISTANT_MESSAGE",
    payload: { text: "阶段结果" },
  }]);
  const completed = calls.find((call) => call[0] === "ack" && call[2] === "COMPLETED");
  assert.equal(completed[3].status, "IDLE");
  assert.equal(completed[3].codexSessionId, "019f-codex");
  assert.ok(!calls.some((call) => call[0] === "state" && call[2] === "IDLE"));
  const persisted = JSON.parse(readFileSync(join(directory, "state.json"), "utf8"));
  assert.equal(persisted.sessions["work-1"].sessionToken, "session-token");
  assert.deepEqual(persisted.outbox, {});
  assert.deepEqual(persisted.eventOutbox, {});
});

test("runner replays assistant messages before the final transition after Core recovers", async () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-runner-event-outbox-"));
  const stateFile = join(directory, "state.json");
  const state = new RunnerState(stateFile);
  state.enqueueEvent("work-event", {
    clientEventId: "cmd-event:item-1",
    type: "ASSISTANT_MESSAGE",
    payload: { text: "已完成修改" },
  });
  state.enqueueTransition("cmd-event", "COMPLETED", {
    sessionId: "work-event",
    status: "IDLE",
  });
  const calls = [];
  const runner = new WorkRunner({
    config: { id: "runner", stateFile, repos: [] },
    state,
    client: {
      publishEvent: async (...args) => calls.push(["event", ...args]),
      ack: async (...args) => calls.push(["ack", ...args]),
    },
  });
  await runner.flushOutbox();
  assert.deepEqual(calls.map((call) => call[0]), ["event", "ack"]);
  assert.equal(state.events().length, 0);
  assert.equal(state.transitions().length, 0);
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
