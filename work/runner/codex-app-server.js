import { spawn } from "node:child_process";
import { createInterface } from "node:readline";

import {
  mcpConfigArgs,
  resolveCodexCommand,
} from "./codex-process.js";

function tomlString(value) {
  return JSON.stringify(String(value));
}

export function buildCodexAppServerArgs({ developerInstructions, mcp }) {
  return [
    "app-server",
    "--stdio",
    "--disable",
    "hooks",
    "-c",
    "mcp_servers={}",
    ...(developerInstructions
      ? ["-c", `developer_instructions=${tomlString(developerInstructions)}`]
      : []),
    ...mcpConfigArgs(mcp),
  ];
}

export function codexInputItems(prompt, imagePaths = []) {
  return [
    ...(String(prompt ?? "").trim() ? [{ type: "text", text: String(prompt) }] : []),
    ...imagePaths.map((path) => ({ type: "localImage", path })),
  ];
}

export async function startCodexAppServerTurn({
  command,
  args,
  cwd,
  env = process.env,
  runtimeSessionId,
  prompt,
  imagePaths = [],
  model,
  reasoningEffort,
  fastMode = false,
  clientUserMessageId = null,
  developerInstructions = null,
  clientVersion = "1",
  spawnImpl = spawn,
  onEvent = () => {},
}) {
  const executable = resolveCodexCommand(command);
  const child = spawnImpl(executable, args, {
    cwd,
    env,
    windowsHide: true,
    stdio: ["pipe", "pipe", "pipe"],
  });
  const rpc = new CodexAppServerRpc(child, onEvent);
  try {
    await rpc.request("initialize", {
      clientInfo: {
        name: "zhixing-work-runner",
        title: "Zhixing Work Runner",
        version: String(clientVersion),
      },
      capabilities: {},
    });
    rpc.notify("initialized", {});

    const threadResult = runtimeSessionId
      ? await rpc.request("thread/resume", {
        threadId: runtimeSessionId,
        model,
        cwd,
        approvalPolicy: "never",
        sandbox: "danger-full-access",
        developerInstructions,
        serviceTier: fastMode ? "fast" : "default",
      })
      : await rpc.request("thread/start", {
        model,
        cwd,
        approvalPolicy: "never",
        sandbox: "danger-full-access",
        developerInstructions,
        serviceName: "zhixing_work",
        serviceTier: fastMode ? "fast" : "default",
      });
    const threadId = String(threadResult?.thread?.id ?? runtimeSessionId ?? "").trim();
    if (!threadId) throw new Error("Codex App Server did not return a thread ID");

    const turnResult = await rpc.request("turn/start", {
      threadId,
      input: codexInputItems(prompt, imagePaths),
      ...(clientUserMessageId ? { clientUserMessageId } : {}),
      cwd,
      approvalPolicy: "never",
      sandboxPolicy: { type: "dangerFullAccess" },
      model,
      effort: reasoningEffort,
      serviceTier: fastMode ? "fast" : "default",
    });
    const turnId = String(turnResult?.turn?.id ?? "").trim();
    if (!turnId) throw new Error("Codex App Server did not return a turn ID");

    return {
      child,
      completed: rpc.completed,
      runtimeSessionId: threadId,
      turnId,
      steer: async ({
        expectedTurnId,
        prompt: nextPrompt,
        imagePaths: nextImages = [],
        clientUserMessageId: nextClientUserMessageId = null,
      }) => {
        const result = await rpc.request("turn/steer", {
          threadId,
          input: codexInputItems(nextPrompt, nextImages),
          expectedTurnId,
          ...(nextClientUserMessageId ? { clientUserMessageId: nextClientUserMessageId } : {}),
        });
        return String(result?.turnId ?? "").trim();
      },
      interrupt: (expectedTurnId = turnId) => rpc.request("turn/interrupt", {
        threadId,
        turnId: expectedTurnId,
      }),
      close: () => rpc.close(),
    };
  } catch (error) {
    rpc.close();
    throw error;
  }
}

export function mapAppServerNotification(message) {
  const params = message?.params ?? {};
  if (message?.method === "thread/started") {
    const threadId = params.thread?.id ?? params.threadId;
    return threadId ? { type: "thread.started", thread_id: threadId } : null;
  }
  if (message?.method === "item/completed" && params.item?.type === "agentMessage") {
    return {
      type: "item.completed",
      item: {
        type: "agent_message",
        id: params.item.id,
        text: params.item.text,
      },
    };
  }
  if (message?.method === "turn/completed") {
    const status = params.turn?.status;
    if (status === "completed") return { type: "turn.completed" };
    if (status === "interrupted") return { type: "turn.interrupted" };
    if (status === "failed") {
      return {
        type: "turn.failed",
        error: params.turn?.error?.message ?? "Codex turn failed",
      };
    }
  }
  return null;
}

class CodexAppServerRpc {
  constructor(child, onEvent) {
    this.child = child;
    this.onEvent = onEvent;
    this.nextId = 1;
    this.pending = new Map();
    this.stderr = "";
    this.closed = false;

    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk) => {
      this.stderr = `${this.stderr}${chunk}`.slice(-16_384);
    });
    const lines = createInterface({ input: child.stdout });
    lines.on("line", (line) => this.handleLine(line));
    this.completed = new Promise((resolve, reject) => {
      child.once("error", (error) => {
        this.rejectPending(error);
        reject(error);
      });
      child.once("close", (code, signal) => {
        const error = new Error(this.stderr || `Codex App Server exited with code ${code}`);
        this.rejectPending(error);
        resolve({ code, signal, stderr: this.stderr });
      });
    });
  }

  request(method, params = {}, timeoutMs = 30_000) {
    if (this.closed) return Promise.reject(new Error("Codex App Server connection is closed"));
    const id = this.nextId;
    this.nextId += 1;
    const result = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        if (!this.pending.delete(id)) return;
        reject(new Error(`Codex App Server request timed out: ${method}`));
      }, timeoutMs);
      this.pending.set(id, { resolve, reject, timeout });
    });
    this.write({ method, id, params });
    return result;
  }

  notify(method, params = {}) {
    if (this.closed) return;
    this.write({ method, params });
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    try { this.child.stdin.end(); } catch { /* Process already exited. */ }
  }

  write(message) {
    this.child.stdin.write(`${JSON.stringify(message)}\n`);
  }

  handleLine(line) {
    let message;
    try {
      message = JSON.parse(line);
    } catch {
      return;
    }
    if (message.id != null && (Object.hasOwn(message, "result") || Object.hasOwn(message, "error"))) {
      const pending = this.pending.get(message.id);
      if (!pending) return;
      this.pending.delete(message.id);
      clearTimeout(pending.timeout);
      if (message.error) {
        const error = new Error(message.error.message ?? "Codex App Server request failed");
        error.code = message.error.code;
        error.data = message.error.data;
        pending.reject(error);
      } else {
        pending.resolve(message.result);
      }
      return;
    }
    const event = mapAppServerNotification(message);
    if (event) this.onEvent(event);
    if (message.id != null && message.method) {
      this.write({
        id: message.id,
        error: { code: -32601, message: `Unsupported App Server request: ${message.method}` },
      });
    }
  }

  rejectPending(error) {
    for (const pending of this.pending.values()) {
      clearTimeout(pending.timeout);
      pending.reject(error);
    }
    this.pending.clear();
  }
}
