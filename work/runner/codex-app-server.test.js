import assert from "node:assert/strict";
import { EventEmitter } from "node:events";
import { PassThrough, Writable } from "node:stream";
import test from "node:test";

import {
  buildCodexAppServerArgs,
  codexInputItems,
  mapAppServerNotification,
  startCodexAppServerTurn,
} from "./codex-app-server.js";

test("App Server args keep the phone MCP isolated in the turn process", () => {
  const args = buildCodexAppServerArgs({
    developerInstructions: "phone instructions",
    mcp: {
      nodePath: "node",
      mcpServerPath: "mcp.js",
      coreUrl: "https://core.example",
      sessionId: "session-1",
      sessionToken: "opaque-token",
      cursorFile: "cursor.json",
      initialInboxCursor: 4,
    },
  });
  assert.deepEqual(args.slice(0, 6), ["app-server", "--stdio", "--disable", "hooks", "-c", "mcp_servers={}"]);
  assert.ok(args.includes("developer_instructions=\"phone instructions\""));
  assert.ok(args.some((arg) => arg.includes("WORK_SESSION_ID=\"session-1\"")));
});

test("App Server input items preserve text and local images", () => {
  assert.deepEqual(codexInputItems("guide", ["C:\\tmp\\one.png"]), [
    { type: "text", text: "guide" },
    { type: "localImage", path: "C:\\tmp\\one.png" },
  ]);
});

test("App Server notifications map only public Work events", () => {
  assert.deepEqual(mapAppServerNotification({
    method: "item/completed",
    params: { item: { type: "agentMessage", id: "item-1", text: "done" } },
  }), {
    type: "item.completed",
    item: { type: "agent_message", id: "item-1", text: "done" },
  });
  assert.deepEqual(mapAppServerNotification({
    method: "turn/completed",
    params: { turn: { id: "turn-1", status: "interrupted" } },
  }), { type: "turn.interrupted" });
  assert.equal(mapAppServerNotification({
    method: "item/completed",
    params: { item: { type: "reasoning", id: "reasoning-1", summary: [] } },
  }), null);
});

test("App Server turn initializes, starts, steers, and interrupts on one connection", async () => {
  const requests = [];
  const events = [];
  const child = fakeAppServerChild((message) => {
    requests.push(message);
    if (message.method === "initialize") return { platformFamily: "windows" };
    if (message.method === "thread/start") return { thread: { id: "thread-1" } };
    if (message.method === "turn/start") return { turn: { id: "turn-1", status: "inProgress" } };
    if (message.method === "turn/steer") return { turnId: "turn-1" };
    if (message.method === "turn/interrupt") return {};
    return undefined;
  });
  const running = await startCodexAppServerTurn({
    command: "codex",
    args: ["app-server", "--stdio"],
    cwd: "C:\\repo",
    runtimeSessionId: null,
    prompt: "start",
    clientUserMessageId: "message-1",
    model: "gpt-5.6-terra",
    reasoningEffort: "high",
    developerInstructions: "phone instructions",
    spawnImpl: () => child,
    onEvent: (event) => events.push(event),
  });
  assert.equal(running.runtimeSessionId, "thread-1");
  assert.equal(running.turnId, "turn-1");
  const threadStart = requests.find((request) => request.method === "thread/start");
  assert.equal(threadStart.params.sandbox, "danger-full-access");
  assert.equal(threadStart.params.developerInstructions, "phone instructions");
  assert.ok(requests.some((request) => request.method === "initialized" && request.id == null));
  assert.equal(
    await running.steer({
      expectedTurnId: "turn-1",
      prompt: "change direction",
      clientUserMessageId: "message-2",
    }),
    "turn-1",
  );
  assert.equal(
    requests.find((request) => request.method === "turn/start").params.clientUserMessageId,
    "message-1",
  );
  assert.equal(
    requests.find((request) => request.method === "turn/steer").params.clientUserMessageId,
    "message-2",
  );
  await running.interrupt();

  child.stdout.write(`${JSON.stringify({
    method: "item/completed",
    params: { item: { type: "agentMessage", id: "item-1", text: "finished" } },
  })}\n`);
  child.stdout.write(`${JSON.stringify({
    method: "turn/completed",
    params: { turn: { id: "turn-1", status: "completed" } },
  })}\n`);
  child.stdout.write(`${JSON.stringify({
    method: "tool/requestUserInput",
    id: 77,
    params: { threadId: "thread-1", turnId: "turn-1" },
  })}\n`);
  await new Promise((resolve) => setImmediate(resolve));
  assert.deepEqual(events, [
    {
      type: "item.completed",
      item: { type: "agent_message", id: "item-1", text: "finished" },
    },
    { type: "turn.completed" },
  ]);
  assert.equal(requests.find((request) => request.id === 77).error.code, -32601);
  running.close();
  child.emit("close", 0, null);
  assert.equal((await running.completed).code, 0);
});

test("App Server retries one timed out initialize after terminating the first process", async () => {
  const children = [];
  const terminated = [];
  const spawnImpl = () => {
    const attempt = children.length + 1;
    const child = fakeAppServerChild((message) => {
      if (attempt === 1 && message.method === "initialize") return undefined;
      if (message.method === "initialize") return { platformFamily: "windows" };
      if (message.method === "thread/start") return { thread: { id: "thread-retried" } };
      if (message.method === "turn/start") return { turn: { id: "turn-retried", status: "inProgress" } };
      return undefined;
    });
    children.push(child);
    if (attempt === 1) {
      setTimeout(() => child.stdout.write(`${JSON.stringify({
        id: 1,
        result: { platformFamily: "windows" },
      })}\n`), 20);
    }
    return child;
  };

  const running = await startCodexAppServerTurn({
    command: "codex",
    args: ["app-server", "--stdio"],
    cwd: "C:\\repo",
    runtimeSessionId: null,
    prompt: "start",
    model: "gpt-5.6-sol",
    reasoningEffort: "high",
    initializeTimeoutMs: 5,
    initializeAttempts: 2,
    spawnImpl,
    terminateProcess: async (child) => {
      terminated.push(child);
      child.emit("close", 1, null);
    },
  });

  assert.equal(children.length, 2);
  assert.deepEqual(terminated, [children[0]]);
  assert.equal(running.runtimeSessionId, "thread-retried");
  assert.equal(running.turnId, "turn-retried");
  running.close();
  children[1].emit("close", 0, null);
  await running.completed;
});

test("App Server terminates the process tree when startup fails after initialize", async () => {
  const child = fakeAppServerChild((message) => {
    if (message.method === "initialize") return { platformFamily: "windows" };
    if (message.method === "thread/start") {
      queueMicrotask(() => child.emit("close", 1, null));
    }
    return undefined;
  });
  const terminated = [];

  await assert.rejects(() => startCodexAppServerTurn({
    command: "codex",
    args: ["app-server", "--stdio"],
    cwd: "C:\\repo",
    runtimeSessionId: null,
    prompt: "start",
    model: "gpt-5.6-sol",
    reasoningEffort: "high",
    spawnImpl: () => child,
    terminateProcess: async (value) => terminated.push(value),
  }), /exited with code 1/);

  assert.deepEqual(terminated, [child]);
});

function fakeAppServerChild(respond) {
  const child = new EventEmitter();
  child.stdout = new PassThrough();
  child.stderr = new PassThrough();
  let buffered = "";
  child.stdin = new Writable({
    write(chunk, _encoding, callback) {
      buffered += chunk.toString("utf8");
      const lines = buffered.split("\n");
      buffered = lines.pop();
      for (const line of lines.filter(Boolean)) {
        const message = JSON.parse(line);
        const result = respond(message);
        if (message.id != null && result !== undefined) {
          queueMicrotask(() => child.stdout.write(`${JSON.stringify({ id: message.id, result })}\n`));
        }
      }
      callback();
    },
  });
  child.kill = () => {};
  return child;
}
