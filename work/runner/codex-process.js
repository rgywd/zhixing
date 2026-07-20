import { spawn } from "node:child_process";
import { createInterface } from "node:readline";

function tomlString(value) {
  return JSON.stringify(String(value));
}

export function mcpConfigArgs({ nodePath, mcpServerPath, coreUrl, sessionId, sessionToken, cursorFile, initialInboxCursor = 0 }) {
  return [
    "-c", `mcp_servers.zhixing_phone.command=${tomlString(nodePath)}`,
    "-c", `mcp_servers.zhixing_phone.args=[${tomlString(mcpServerPath)}]`,
    "-c", `mcp_servers.zhixing_phone.env.WORK_CORE_URL=${tomlString(coreUrl)}`,
    "-c", `mcp_servers.zhixing_phone.env.WORK_SESSION_ID=${tomlString(sessionId)}`,
    "-c", `mcp_servers.zhixing_phone.env.WORK_SESSION_TOKEN=${tomlString(sessionToken)}`,
    "-c", `mcp_servers.zhixing_phone.env.WORK_CURSOR_FILE=${tomlString(cursorFile)}`,
    "-c", `mcp_servers.zhixing_phone.env.WORK_INITIAL_INBOX_CURSOR=${tomlString(initialInboxCursor)}`,
    "-c", "mcp_servers.zhixing_phone.tool_timeout_sec=210",
  ];
}

export function buildCodexArgs({ kind, repoPath, model, reasoningEffort, codexSessionId, mcp }) {
  const shared = [
    "--json",
    "--ignore-user-config",
    "--dangerously-bypass-approvals-and-sandbox",
    "-m", model,
    "-c", `model_reasoning_effort=${tomlString(reasoningEffort)}`,
    ...mcpConfigArgs(mcp),
  ];
  if (kind === "START") {
    return ["exec", "-C", repoPath, ...shared, "-"];
  }
  if (!codexSessionId) throw new Error("Cannot resume without a Codex session ID");
  return ["exec", "resume", ...shared, codexSessionId, "-"];
}

export function parseCodexSessionId(event) {
  if (event?.type === "thread.started") return event.thread_id ?? event.threadId ?? null;
  if (event?.type === "session.started") return event.session_id ?? event.sessionId ?? null;
  return null;
}

export function runCodex({ command, args, prompt, cwd, env = process.env, spawnImpl = spawn, onEvent = () => {} }) {
  const child = spawnImpl(command, args, {
    cwd,
    env,
    windowsHide: true,
    stdio: ["pipe", "pipe", "pipe"],
  });
  child.stdin.end(prompt);
  let stderr = "";
  child.stderr.setEncoding("utf8");
  child.stderr.on("data", (chunk) => {
    stderr = `${stderr}${chunk}`.slice(-16_384);
  });
  const lines = createInterface({ input: child.stdout });
  lines.on("line", (line) => {
    try {
      onEvent(JSON.parse(line));
    } catch {
      // Codex JSON mode should be JSONL; ignore non-JSON diagnostics without leaking them to Core.
    }
  });
  const completed = new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("close", (code, signal) => resolve({ code, signal, stderr }));
  });
  return { child, completed };
}
