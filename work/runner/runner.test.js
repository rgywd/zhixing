import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { existsSync, mkdtempSync, readFileSync, readdirSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { spawnSync } from "node:child_process";
import test from "node:test";
import {
  buildClaudeArgs,
  parseClaudeAssistantMessage,
  parseClaudeSessionId,
  parseClaudeTurnOutcome,
  resolveClaudeCommand,
  writeClaudeMcpConfig,
} from "./claude-process.js";
import {
  buildCodexArgs,
  parseCodexAssistantMessage,
  parseCodexSessionId,
  parseCodexTurnOutcome,
  resolveCodexCommand,
  terminateProcessTree,
} from "./codex-process.js";
import { ensurePhoneHookProfile } from "./phone-hook-profile.js";
import { PHONE_DEVELOPER_INSTRUCTIONS, WorkRunner } from "./runner.js";
import { RunnerState } from "./state.js";

const testDirectory = dirname(fileURLToPath(import.meta.url));

async function waitForCondition(predicate, timeoutMs = 1_000) {
  const deadline = Date.now() + timeoutMs;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error("Timed out waiting for test condition");
    await new Promise((resolve) => setTimeout(resolve, 1));
  }
}

test("Codex args isolate user config and fix model, effort, access and phone-line MCP", () => {
  const args = buildCodexArgs({
    kind: "START",
    repoPath: "C:/repo",
    model: "gpt-5.6-sol",
    reasoningEffort: "high",
    fastMode: true,
    profileName: "zhixing-phone",
    developerInstructions: PHONE_DEVELOPER_INSTRUCTIONS,
    imagePaths: ["C:/temp/screen.png"],
    additionalDirectories: ["C:/temp"],
    mcp: {
      nodePath: "C:/node.exe",
      mcpServerPath: "C:/mcp-server.js",
      coreUrl: "https://core.example.com",
      sessionId: "work-1",
      sessionToken: "secret",
      cursorFile: "C:/cursor.json",
    },
  });
  assert.deepEqual(
    args.slice(0, 6),
    ["exec", "-C", "C:/repo", "--profile", "zhixing-phone", "--json"],
  );
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
  assert.ok(args.includes("service_tier=\"fast\""));
  assert.ok(args.some((arg) => arg.startsWith("mcp_servers.zhixing_phone.command=")));
  assert.deepEqual(args.slice(args.indexOf("--image"), args.indexOf("--image") + 2), ["--image", "C:/temp/screen.png"]);
  assert.deepEqual(args.slice(args.indexOf("--add-dir"), args.indexOf("--add-dir") + 2), ["--add-dir", "C:/temp"]);
  assert.equal(args.at(-1), "-");
});

test("Codex standard speed explicitly clears a persisted fast service tier", () => {
  const args = buildCodexArgs({
    kind: "START",
    repoPath: "C:/repo",
    model: "gpt-5.6-sol",
    reasoningEffort: "high",
    fastMode: false,
    mcp: {
      nodePath: "C:/node.exe",
      mcpServerPath: "C:/mcp-server.js",
      coreUrl: "https://core.example.com",
      sessionId: "work-1",
      sessionToken: "secret",
      cursorFile: "C:/cursor.json",
    },
  });

  assert.ok(args.includes("service_tier=\"default\""));
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

test("Codex JSONL mapper treats turn events as the semantic terminal state", () => {
  assert.deepEqual(parseCodexTurnOutcome({ type: "turn.completed" }), { status: "COMPLETED", detail: null });
  assert.deepEqual(parseCodexTurnOutcome({ type: "turn.interrupted" }), { status: "INTERRUPTED", detail: null });
  assert.deepEqual(parseCodexTurnOutcome({
    type: "turn.failed",
    error: { message: "tool host failed" },
  }), { status: "FAILED", detail: "tool host failed" });
  assert.equal(parseCodexTurnOutcome({ type: "item.completed" }), null);
});

test("Claude Code args load user API settings while isolating hooks and MCP", () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-claude-config-"));
  const mcpConfigPath = writeClaudeMcpConfig(join(directory, "mcp.json"), {
    nodePath: "C:/node.exe",
    mcpServerPath: "C:/mcp-server.js",
    coreUrl: "https://core.example.com",
    sessionId: "work-claude",
    sessionToken: "secret",
    cursorFile: "C:/cursor.json",
    initialInboxCursor: 7,
  });
  const config = JSON.parse(readFileSync(mcpConfigPath, "utf8"));
  assert.equal(config.mcpServers.zhixing_phone.command, "C:/node.exe");
  assert.equal(config.mcpServers.zhixing_phone.env.WORK_SESSION_TOKEN, "secret");

  const args = buildClaudeArgs({
    kind: "RESUME",
    model: "sonnet",
    reasoningEffort: "xhigh",
    runtimeSessionId: "claude-session",
    developerInstructions: PHONE_DEVELOPER_INSTRUCTIONS,
    mcpConfigPath,
    additionalDirectories: ["C:/turn-images"],
  });
  assert.ok(args.includes("-p"));
  assert.deepEqual(args.slice(args.indexOf("--output-format"), args.indexOf("--output-format") + 2), [
    "--output-format", "stream-json",
  ]);
  assert.deepEqual(args.slice(args.indexOf("--permission-mode"), args.indexOf("--permission-mode") + 2), [
    "--permission-mode", "bypassPermissions",
  ]);
  assert.ok(args.includes("--strict-mcp-config"));
  assert.ok(args.includes("--disable-slash-commands"));
  assert.ok(args.includes("--no-chrome"));
  assert.deepEqual(args.slice(args.indexOf("--setting-sources"), args.indexOf("--setting-sources") + 2), [
    "--setting-sources", "user,project",
  ]);
  assert.deepEqual(JSON.parse(args[args.indexOf("--settings") + 1]), { disableAllHooks: true });
  assert.deepEqual(args.slice(args.indexOf("--effort"), args.indexOf("--effort") + 2), ["--effort", "xhigh"]);
  assert.ok(args.includes("claude-session"));
  assert.ok(!args.includes("secret"));
});

test("Claude Code stream-json mapper exposes only assistant text and terminal result", () => {
  assert.equal(parseClaudeSessionId({
    type: "system",
    subtype: "init",
    session_id: "claude-session",
  }), "claude-session");
  assert.deepEqual(parseClaudeAssistantMessage({
    type: "assistant",
    message: {
      id: "message-1",
      content: [
        { type: "thinking", thinking: "private" },
        { type: "text", text: "阶段结果" },
        { type: "tool_use", name: "Bash", input: { command: "secret" } },
      ],
    },
  }), { itemId: "message-1", text: "阶段结果" });
  assert.equal(parseClaudeAssistantMessage({
    type: "assistant",
    error: "authentication_failed",
    message: { id: "message-2", content: [{ type: "text", text: "Failed to authenticate" }] },
  }), null);
  assert.deepEqual(parseClaudeTurnOutcome({
    type: "result",
    is_error: false,
    result: "done",
  }), { status: "COMPLETED", detail: null });
  assert.deepEqual(parseClaudeTurnOutcome({
    type: "result",
    is_error: true,
    api_error_status: 401,
    result: "OAuth access token has been revoked",
  }), { status: "FAILED", detail: "OAuth access token has been revoked" });
});

test("Windows process cleanup terminates the full Codex child tree", async () => {
  const calls = [];
  const child = { pid: 4321, kill: () => calls.push(["fallback"]) };
  await terminateProcessTree(child, "win32", (command, args, options) => ({
    once(event, callback) {
      if (event === "close") queueMicrotask(callback);
      calls.push([event, command, args, options]);
    },
  }));
  const close = calls.find((call) => call[0] === "close");
  assert.equal(close[1], "taskkill.exe");
  assert.deepEqual(close[2], ["/PID", "4321", "/T", "/F"]);
  assert.equal(close[3].windowsHide, true);
  assert.ok(!calls.some((call) => call[0] === "fallback"));
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

test("runner rejects a reasoning effort that is unsupported by the selected model", async () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-runner-model-effort-"));
  try {
    const state = new RunnerState(join(directory, "state.json"));
    const acknowledgements = [];
    const runner = new WorkRunner({
      config: {
        id: "runner",
        coreUrl: "https://core",
        stateFile: state.filename,
        repos: [{
          id: "repo",
          name: "repo",
          path: directory,
          runtimes: [{
            id: "codex",
            name: "Codex",
            models: ["gpt-5.6-sol", "gpt-5.3-codex-spark"],
            reasoningEfforts: ["low", "medium", "high", "xhigh", "max"],
            reasoningEffortsByModel: {
              "gpt-5.3-codex-spark": ["low", "medium", "high", "xhigh"],
            },
          }],
        }],
      },
      state,
      client: {
        ack: async (...args) => acknowledgements.push(args),
      },
      spawnCodex: () => {
        throw new Error("unsupported combinations must not start Codex");
      },
    });

    await runner.startCommand({
      id: "cmd-spark-max",
      sessionId: "work-spark-max",
      kind: "START",
      payload: {
        repoId: "repo",
        model: "gpt-5.3-codex-spark",
        reasoningEffort: "max",
        sessionToken: "session-token",
        message: "do not run",
      },
    });

    assert.equal(acknowledgements.at(-1)[1], "FAILED");
    assert.equal(acknowledgements.at(-1)[2].detail, "Runner rejected the session snapshot");
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("runner rejects Fast mode for a Codex model that does not advertise it", async () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-runner-fast-model-"));
  try {
    const state = new RunnerState(join(directory, "state.json"));
    const acknowledgements = [];
    const runner = new WorkRunner({
      config: {
        id: "runner",
        coreUrl: "https://core",
        stateFile: state.filename,
        repos: [{
          id: "repo",
          name: "repo",
          path: directory,
          runtimes: [{
            id: "codex",
            name: "Codex",
            models: ["gpt-5.3-codex-spark"],
            reasoningEfforts: ["high"],
            fastModels: [],
          }],
        }],
      },
      state,
      client: { ack: async (...args) => acknowledgements.push(args) },
      spawnCodex: () => { throw new Error("unsupported Fast mode must not start Codex"); },
    });

    await runner.startCommand({
      id: "cmd-spark-fast",
      sessionId: "work-spark-fast",
      kind: "START",
      payload: {
        repoId: "repo",
        runtime: "codex",
        model: "gpt-5.3-codex-spark",
        reasoningEffort: "high",
        fastMode: true,
        sessionToken: "session-token",
        message: "do not run",
      },
    });

    assert.equal(acknowledgements.at(-1)[1], "FAILED");
    assert.equal(acknowledgements.at(-1)[2].detail, "Runner rejected the session snapshot");
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
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

test("runner gives Codex mixed attachments with native images and an explicit file manifest", async () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-runner-files-"));
  const state = new RunnerState(join(directory, "state.json"));
  const imageBytes = Buffer.from("image");
  const fileBytes = Buffer.from("release notes", "utf8");
  let resolveProcess;
  let attachmentDirectory;
  let filePath;
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
      downloadAttachment: async (_runnerId, attachmentId) => attachmentId === "att-image"
        ? { data: imageBytes, mimeType: "image/png" }
        : { data: fileBytes, mimeType: "text/plain" },
    },
    spawnCodex: ({ args, prompt, onEvent }) => {
      const imagePath = args[args.indexOf("--image") + 1];
      attachmentDirectory = args[args.indexOf("--add-dir") + 1];
      filePath = join(attachmentDirectory, "2-release-notes.txt");
      assert.equal(existsSync(imagePath), true);
      assert.equal(existsSync(filePath), true);
      assert.match(prompt, /User-provided attachments for this turn/);
      assert.match(prompt, /release-notes\.txt/);
      assert.match(prompt, /text\/plain/);
      assert.match(prompt, /Archives remain compressed/);
      onEvent({ type: "thread.started", thread_id: "file-session" });
      return {
        child: { kill() {} },
        completed: new Promise((resolve) => { resolveProcess = resolve; }),
      };
    },
  });
  await runner.startCommand({
    id: "cmd-files",
    sessionId: "work-files",
    kind: "START",
    payload: {
      repoId: "repo",
      model: "gpt-5.6-sol",
      reasoningEffort: "high",
      sessionToken: "session-token",
      message: "inspect these",
      attachments: [
        {
          id: "att-image",
          fileName: "screen.png",
          mimeType: "image/png",
          size: imageBytes.length,
          sha256: createHash("sha256").update(imageBytes).digest("hex"),
        },
        {
          id: "att-file",
          fileName: "release-notes.txt",
          mimeType: "text/plain",
          size: fileBytes.length,
          sha256: createHash("sha256").update(fileBytes).digest("hex"),
        },
      ],
    },
  });
  resolveProcess({ code: 0, signal: null, stderr: "" });
  await waitForCondition(() => !existsSync(attachmentDirectory));
  assert.equal(existsSync(filePath), false);
});

test("runner gives Claude Code archive attachments through its turn directory", async () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-runner-claude-files-"));
  const state = new RunnerState(join(directory, "state.json"));
  const archiveBytes = Buffer.from("PK\u0003\u0004");
  let resolveProcess;
  let attachmentDirectory;
  const runner = new WorkRunner({
    config: {
      id: "runner",
      coreUrl: "https://core",
      stateFile: state.filename,
      repos: [{
        id: "repo",
        name: "repo",
        path: directory,
        runtimes: [{
          id: "claude-code",
          name: "Claude Code",
          command: "claude",
          models: ["sonnet"],
          reasoningEfforts: ["high"],
        }],
      }],
    },
    state,
    client: {
      ack: async () => {},
      updateState: async () => {},
      downloadAttachment: async () => ({ data: archiveBytes, mimeType: "application/zip" }),
    },
    spawnClaude: ({ args, prompt, onEvent }) => {
      attachmentDirectory = args[args.indexOf("--add-dir") + 1];
      assert.equal(existsSync(join(attachmentDirectory, "1-source.zip")), true);
      assert.match(prompt, /source\.zip/);
      assert.match(prompt, /application\/zip/);
      onEvent({ type: "system", subtype: "init", session_id: "claude-file-session" });
      return {
        child: { kill() {} },
        completed: new Promise((resolve) => { resolveProcess = resolve; }),
      };
    },
  });
  await runner.startCommand({
    id: "cmd-claude-file",
    sessionId: "work-claude-file",
    kind: "START",
    payload: {
      repoId: "repo",
      runtime: "claude-code",
      model: "sonnet",
      reasoningEffort: "high",
      sessionToken: "session-token",
      message: "inspect archive",
      attachments: [{
        id: "att-archive",
        fileName: "source.zip",
        mimeType: "application/zip",
        size: archiveBytes.length,
        sha256: createHash("sha256").update(archiveBytes).digest("hex"),
      }],
    },
  });
  resolveProcess({ code: 0, signal: null, stderr: "" });
  await waitForCondition(() => !existsSync(attachmentDirectory));
});

test("runner can restart a failed first turn with its persisted repository and image", async () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-runner-start-retry-"));
  const state = new RunnerState(join(directory, "state.json"));
  const acknowledgements = [];
  let downloadAttempts = 0;
  let resolveProcess;
  let retryArgs;
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
      ack: async (...args) => acknowledgements.push(args),
      updateState: async () => {},
      downloadAttachment: async () => {
        downloadAttempts += 1;
        if (downloadAttempts === 1) throw new Error("attachment timeout");
        return { data: Buffer.from("image"), mimeType: "image/png" };
      },
    },
    spawnCodex: ({ args, onEvent }) => {
      retryArgs = args;
      onEvent({ type: "thread.started", thread_id: "retried-session" });
      return {
        child: { kill() {} },
        completed: new Promise((resolve) => { resolveProcess = resolve; }),
      };
    },
  });
  const attachment = { id: "att-image", mimeType: "image/png" };

  await runner.startCommand({
    id: "cmd-start",
    sessionId: "work-retry",
    kind: "START",
    payload: {
      repoId: "repo",
      model: "gpt-5.6-sol",
      reasoningEffort: "high",
      sessionToken: "session-token",
      message: "inspect this",
      attachments: [attachment],
    },
  });
  assert.equal(acknowledgements.at(-1)[1], "FAILED");
  assert.equal(state.get("work-retry").repoId, "repo");
  assert.deepEqual(state.get("work-retry").pendingAttachments, [attachment]);

  await runner.startCommand({
    id: "cmd-resume",
    sessionId: "work-retry",
    kind: "RESUME",
    payload: {
      sessionToken: "session-token-2",
      message: "retry",
      attachments: [],
    },
  });
  assert.deepEqual(retryArgs.slice(0, 3), ["exec", "-C", directory]);
  assert.ok(retryArgs.includes("--image"));
  assert.deepEqual(state.get("work-retry").pendingAttachments, []);

  resolveProcess({ code: 0, signal: null, stderr: "" });
  await waitForCondition(() => acknowledgements.some(([id, status]) => id === "cmd-resume" && status === "COMPLETED"));
  assert.equal(state.get("work-retry").codexSessionId, "retried-session");
});

test("resume targets the persisted Codex session", () => {
  const args = buildCodexArgs({
    kind: "RESUME",
    repoPath: "C:/repo",
    model: "gpt-5.6-sol",
    reasoningEffort: "xhigh",
    codexSessionId: "019f-session",
    additionalDirectories: ["C:/turn-files"],
    profileName: "zhixing-phone",
    mcp: {
      nodePath: "node",
      mcpServerPath: "mcp.js",
      coreUrl: "https://core",
      sessionId: "work-1",
      sessionToken: "token",
      cursorFile: "cursor.json",
    },
  });
  assert.deepEqual(args.slice(0, 4), ["exec", "--profile", "zhixing-phone", "resume"]);
  assert.ok(args.indexOf("--profile") < args.indexOf("resume"));
  assert.ok(args.indexOf("--dangerously-bypass-hook-trust") > args.indexOf("resume"));
  assert.equal(args.includes("--add-dir"), false);
  assert.ok(args.includes('model_reasoning_effort="xhigh"'));
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

test("runner keeps an App Server turn active for steer and closes it after completion", async () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-runner-app-server-"));
  const state = new RunnerState(join(directory, "state.json"));
  const calls = [];
  const steerCalls = [];
  const appServerStarts = [];
  let emit;
  let resolveProcess;
  const runner = new WorkRunner({
    config: {
      id: "runner",
      version: "1.0.0",
      coreUrl: "https://core",
      stateFile: state.filename,
      semanticExitGraceMs: 0,
      repos: [{
        id: "repo",
        name: "repo",
        path: directory,
        runtimes: [{
          id: "codex",
          name: "Codex",
          command: "codex.exe",
          transport: "app-server",
          models: ["gpt-5.6-sol"],
          reasoningEfforts: ["high"],
        }],
      }],
    },
    state,
    client: {
      ack: async (...args) => calls.push(["ack", ...args]),
      updateState: async (...args) => calls.push(["state", ...args]),
      publishEvent: async (...args) => calls.push(["event", ...args]),
    },
    spawnCodexAppServer: async (input) => {
      appServerStarts.push(input);
      emit = input.onEvent;
      return {
        child: { kill() {} },
        completed: new Promise((resolve) => { resolveProcess = resolve; }),
        runtimeSessionId: "thread-app-server",
        turnId: "turn-app-server",
        steer: async (steerInput) => {
          steerCalls.push(steerInput);
          return "turn-app-server";
        },
        interrupt: async () => {},
        close: () => {
          calls.push(["close"]);
          resolveProcess({ code: 0, signal: null, stderr: "" });
        },
      };
    },
  });
  await runner.startCommand({
    id: "cmd-start",
    sessionId: "work-app-server",
    kind: "START",
    payload: {
      repoId: "repo",
      runtime: "codex",
      model: "gpt-5.6-sol",
      reasoningEffort: "high",
      sessionToken: "session-token",
      clientMessageId: "message-start",
      message: "start",
    },
  });
  assert.equal(runner.active.get("work-app-server").turnId, "turn-app-server");
  assert.ok(calls.some((call) =>
    call[0] === "state"
    && call[1] === "work-app-server"
    && call[4] === "thread-app-server"
    && call[6] === "turn-app-server"));

  await runner.steerCommand({
    id: "cmd-steer",
    sessionId: "work-app-server",
    kind: "STEER",
    payload: {
      expectedTurnId: "turn-app-server",
      clientMessageId: "message-steer",
      message: "focus on tests",
    },
  });
  assert.deepEqual(steerCalls, [{
    expectedTurnId: "turn-app-server",
    prompt: "focus on tests",
    imagePaths: [],
    clientUserMessageId: "message-steer",
  }]);
  assert.ok(calls.some((call) => call[0] === "ack" && call[1] === "cmd-steer" && call[2] === "COMPLETED"));

  emit({ type: "item.completed", item: { id: "agent-app-server", type: "agent_message", text: "done" } });
  emit({ type: "turn.completed" });
  await waitForCondition(() => runner.active.size === 0);
  const completed = calls.find((call) =>
    call[0] === "ack" && call[1] === "cmd-start" && call[2] === "COMPLETED");
  assert.equal(completed[3].status, "IDLE");
  assert.equal(completed[3].runtimeSessionId, "thread-app-server");
  assert.equal(completed[3].activeTurnId, null);
  assert.ok(
    calls.findIndex((call) => call[0] === "close")
      < calls.findIndex((call) => call[0] === "ack" && call[1] === "cmd-start" && call[2] === "COMPLETED"),
  );

  await runner.startCommand({
    id: "cmd-resume",
    sessionId: "work-app-server",
    kind: "RESUME",
    payload: {
      repoId: "repo",
      runtime: "codex",
      model: "gpt-5.6-sol",
      reasoningEffort: "high",
      sessionToken: "session-token-2",
      clientMessageId: "message-resume",
      message: "continue",
    },
  });
  assert.equal(appServerStarts.length, 2);
  assert.equal(appServerStarts[1].runtimeSessionId, "thread-app-server");
  emit({ type: "turn.completed" });
  await waitForCondition(() => runner.active.size === 0);
});

test("runner fails an App Server turn that exits cleanly without turn/completed", async () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-runner-app-server-early-exit-"));
  const state = new RunnerState(join(directory, "state.json"));
  const calls = [];
  const runner = new WorkRunner({
    config: {
      id: "runner",
      version: "1.0.0",
      coreUrl: "https://core",
      stateFile: state.filename,
      repos: [{
        id: "repo",
        name: "repo",
        path: directory,
        runtimes: [{
          id: "codex",
          name: "Codex",
          command: "codex.exe",
          transport: "app-server",
          models: ["gpt-5.6-sol"],
          reasoningEfforts: ["high"],
        }],
      }],
    },
    state,
    client: {
      ack: async (...args) => calls.push(args),
      updateState: async () => {},
      publishEvent: async () => {},
    },
    spawnCodexAppServer: async () => ({
      child: { kill() {} },
      completed: Promise.resolve({ code: 0, signal: null, stderr: "" }),
      runtimeSessionId: "thread-early-exit",
      turnId: "turn-early-exit",
      steer: async () => "turn-early-exit",
      interrupt: async () => {},
      close: () => {},
    }),
  });
  await runner.startCommand({
    id: "cmd-early-exit",
    sessionId: "work-early-exit",
    kind: "START",
    payload: {
      repoId: "repo",
      runtime: "codex",
      model: "gpt-5.6-sol",
      reasoningEffort: "high",
      sessionToken: "session-token",
      message: "start",
    },
  });
  await waitForCondition(() => calls.some((call) => call[0] === "cmd-early-exit" && call[1] === "FAILED"));
  assert.equal(calls.find((call) => call[0] === "cmd-early-exit" && call[1] === "FAILED")[2].status, "FAILED");
});

test("runner starts and resumes Claude Code with a generic runtime session id", async () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-runner-claude-"));
  const state = new RunnerState(join(directory, "state.json"));
  const calls = [];
  let invocation;
  const runner = new WorkRunner({
    config: {
      id: "runner",
      coreUrl: "https://core",
      stateFile: state.filename,
      repos: [{
        id: "repo",
        name: "repo",
        path: directory,
        runtimes: [{
          id: "claude-code",
          name: "Claude Code",
          command: "claude",
          models: ["sonnet"],
          reasoningEfforts: ["high"],
        }],
      }],
    },
    state,
    client: {
      ack: async (...args) => calls.push(["ack", ...args]),
      updateState: async (...args) => calls.push(["state", ...args]),
      publishEvent: async (...args) => calls.push(["event", ...args]),
    },
    spawnClaude: (input) => {
      invocation = input;
      input.onEvent({ type: "system", subtype: "init", session_id: "claude-session" });
      input.onEvent({
        type: "assistant",
        message: { id: "assistant-1", content: [{ type: "text", text: "Claude 阶段结果" }] },
      });
      input.onEvent({ type: "result", is_error: false, result: "Claude 阶段结果" });
      return {
        child: { kill() {} },
        completed: Promise.resolve({ code: 0, signal: null, stderr: "" }),
      };
    },
  });

  await runner.startCommand({
    id: "cmd-claude",
    sessionId: "work-claude",
    kind: "START",
    payload: {
      repoId: "repo",
      runtime: "claude-code",
      model: "sonnet",
      reasoningEffort: "high",
      sessionToken: "session-token",
      message: "hello",
    },
  });
  await waitForCondition(() => calls.some((call) => call[0] === "ack" && call[2] === "COMPLETED"));

  assert.equal(invocation.command, "claude");
  assert.ok(invocation.args.includes("--strict-mcp-config"));
  assert.equal(state.get("work-claude").runtime, "claude-code");
  assert.equal(state.get("work-claude").runtimeSessionId, "claude-session");
  assert.ok(calls.some((call) =>
    call[0] === "state"
    && call[1] === "work-claude"
    && call[4] === "claude-session"
    && call[5] === "claude-code"));
  const completed = calls.find((call) => call[0] === "ack" && call[2] === "COMPLETED");
  assert.equal(completed[3].runtime, "claude-code");
  assert.equal(completed[3].runtimeSessionId, "claude-session");
  assert.equal(completed[3].codexSessionId, undefined);
  const event = calls.find((call) => call[0] === "event");
  assert.equal(event[2].payload.text, "Claude 阶段结果");
});

test("runner completes a semantic turn even when the Codex process never exits", async () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-runner-semantic-complete-"));
  const state = new RunnerState(join(directory, "state.json"));
  const calls = [];
  let emitEvent;
  let terminated = false;
  const runner = new WorkRunner({
    config: {
      id: "runner",
      coreUrl: "https://core",
      stateFile: state.filename,
      semanticExitGraceMs: 0,
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
      ack: async (...args) => calls.push(["ack", ...args]),
      updateState: async () => {},
      publishEvent: async () => {},
    },
    spawnCodex: ({ onEvent }) => {
      emitEvent = onEvent;
      onEvent({ type: "thread.started", thread_id: "019f-semantic" });
      return {
        child: { pid: 1234, kill() {} },
        completed: new Promise(() => {}),
      };
    },
    terminateCodex: async () => { terminated = true; },
  });

  await runner.startCommand({
    id: "cmd-semantic",
    sessionId: "work-semantic",
    kind: "START",
    payload: {
      repoId: "repo",
      model: "gpt-5.6-sol",
      reasoningEffort: "high",
      sessionToken: "session-token",
      message: "hello",
    },
  });
  emitEvent({ type: "turn.completed" });
  await waitForCondition(() => terminated && runner.active.size === 0);

  assert.equal(terminated, true);
  assert.equal(runner.active.size, 0);
  const completed = calls.find((call) => call[0] === "ack" && call[2] === "COMPLETED");
  assert.equal(completed[3].status, "IDLE");
  assert.equal(completed[3].codexSessionId, "019f-semantic");
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

test("stop interrupts an active App Server turn before acknowledging", async () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-runner-app-server-stop-"));
  const state = new RunnerState(join(directory, "state.json"));
  const calls = [];
  const runner = new WorkRunner({
    config: { id: "runner", stateFile: state.filename, repos: [] },
    state,
    client: { ack: async (...args) => calls.push(args) },
  });
  let interrupted = null;
  runner.active.set("work-stop", {
    startCommandId: "cmd-start",
    stoppedByUser: false,
    runtime: "codex",
    turnId: "turn-1",
    interrupt: async (turnId) => { interrupted = turnId; },
    child: { kill() { throw new Error("App Server should be interrupted before process cleanup"); } },
  });

  await runner.stopCommand({ id: "cmd-stop", sessionId: "work-stop", kind: "STOP" });

  assert.equal(interrupted, "turn-1");
  assert.equal(runner.active.get("work-stop").stoppedByUser, true);
  assert.ok(calls.some((call) => call[0] === "cmd-stop" && call[1] === "COMPLETED"));
});

test("session id parser accepts current Codex JSONL event", () => {
  assert.equal(parseCodexSessionId({ type: "thread.started", thread_id: "abc" }), "abc");
  assert.equal(parseCodexSessionId({ type: "item.completed" }), null);
});

test("Windows resolves the real Codex executable instead of an npm shell shim", () => {
  assert.equal(resolveCodexCommand("codex", "win32", () => "C:/Codex/codex.exe"), "C:/Codex/codex.exe");
  assert.equal(
    resolveCodexCommand(
      "%USERPROFILE%/.zhixing-work/codex.exe",
      "win32",
      undefined,
      { USERPROFILE: "C:/Users/test" },
    ),
    "C:/Users/test/.zhixing-work/codex.exe",
  );
  assert.throws(
    () => resolveCodexCommand("C:/Users/me/AppData/Roaming/npm/codex.cmd", "win32"),
    /codex\.exe/,
  );
  assert.equal(resolveCodexCommand("codex", "linux"), "codex");
});

test("Windows resolves the native Claude Code executable instead of a shell shim", () => {
  assert.equal(resolveClaudeCommand("claude", "win32", () => "C:/Claude/claude.exe"), "C:/Claude/claude.exe");
  assert.throws(
    () => resolveClaudeCommand("C:/Users/me/AppData/Roaming/npm/claude.cmd", "win32"),
    /claude\.exe/,
  );
  assert.equal(resolveClaudeCommand("claude", "linux"), "claude");
});
