import { spawn } from "node:child_process";
import { execFileSync } from "node:child_process";
import { existsSync } from "node:fs";
import { createInterface } from "node:readline";
import { extname, join } from "node:path";

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

export function buildCodexArgs({
  kind,
  repoPath,
  model,
  reasoningEffort,
  fastMode = false,
  codexSessionId,
  imagePaths = [],
  additionalDirectories = [],
  profileName,
  developerInstructions,
  mcp,
}) {
  const profile = profileName ? ["--profile", profileName] : [];
  const shared = [
    "--json",
    "--ignore-user-config",
    ...(profileName ? ["--dangerously-bypass-hook-trust"] : []),
    "--dangerously-bypass-approvals-and-sandbox",
    "-m", model,
    "-c", `model_reasoning_effort=${tomlString(reasoningEffort)}`,
    "-c", `service_tier=${tomlString(fastMode ? "fast" : "default")}`,
    ...(developerInstructions ? ["-c", `developer_instructions=${tomlString(developerInstructions)}`] : []),
    ...imagePaths.flatMap((imagePath) => ["--image", imagePath]),
    ...mcpConfigArgs(mcp),
  ];
  if (kind === "START") {
    return [
      "exec", "-C", repoPath, ...profile, ...shared,
      ...additionalDirectories.flatMap((directory) => ["--add-dir", directory]),
      "-",
    ];
  }
  if (!codexSessionId) throw new Error("Cannot resume without a Codex session ID");
  // `--profile` is an `exec` option, not an `exec resume` option. Keeping it
  // after `resume` makes current Codex CLI versions exit with code 2.
  return ["exec", ...profile, "resume", ...shared, codexSessionId, "-"];
}

export function parseCodexSessionId(event) {
  if (event?.type === "thread.started") return event.thread_id ?? event.threadId ?? null;
  if (event?.type === "session.started") return event.session_id ?? event.sessionId ?? null;
  return null;
}

export function parseCodexAssistantMessage(event) {
  if (event?.type !== "item.completed" || event.item?.type !== "agent_message") return null;
  const text = String(event.item.text ?? "").trim();
  const itemId = String(event.item.id ?? "").trim();
  if (!text || !itemId) return null;
  return { itemId, text };
}

export function parseCodexTurnOutcome(event) {
  if (event?.type === "turn.completed") return { status: "COMPLETED", detail: null };
  if (event?.type !== "turn.failed") return null;
  const detail = typeof event.error === "string"
    ? event.error
    : String(event.error?.message ?? "Codex turn failed");
  return { status: "FAILED", detail };
}

export async function terminateProcessTree(child, platform = process.platform, spawnImpl = spawn) {
  if (!child) return;
  if (platform !== "win32" || !Number.isInteger(child.pid)) {
    child.kill();
    return;
  }
  await new Promise((resolve) => {
    let settled = false;
    const finish = () => {
      if (settled) return;
      settled = true;
      resolve();
    };
    try {
      const killer = spawnImpl("taskkill.exe", ["/PID", String(child.pid), "/T", "/F"], {
        windowsHide: true,
        stdio: "ignore",
      });
      killer.once("error", () => {
        try { child.kill(); } catch { /* Process already exited. */ }
        finish();
      });
      killer.once("close", finish);
    } catch {
      try { child.kill(); } catch { /* Process already exited. */ }
      finish();
    }
  });
}

export function runCodex({ command, args, prompt, cwd, env = process.env, spawnImpl = spawn, onEvent = () => {} }) {
  const executable = resolveCodexCommand(command);
  const child = spawnImpl(executable, args, {
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

export function resolveCodexCommand(command, platform = process.platform, findExecutable = defaultFindExecutable) {
  if (platform !== "win32") return command;
  const extension = extname(command).toLowerCase();
  if ([".cmd", ".bat", ".ps1"].includes(extension)) {
    throw new Error("codexCommand must point to codex.exe on Windows, not a shell shim");
  }
  if (extension || /[\\/]/.test(command)) return command;
  return findExecutable(`${command}.exe`) ?? command;
}

function defaultFindExecutable(candidate) {
  if (candidate.toLowerCase() === "codex.exe") {
    const architecture = process.arch === "arm64" ? "arm64" : "x64";
    const target = process.arch === "arm64" ? "aarch64-pc-windows-msvc" : "x86_64-pc-windows-msvc";
    const npmRoot = process.env.APPDATA ? join(process.env.APPDATA, "npm", "node_modules") : null;
    const bundled = npmRoot && join(
      npmRoot,
      "@openai",
      "codex",
      "node_modules",
      `@openai/codex-win32-${architecture}`,
      "vendor",
      target,
      "bin",
      "codex.exe",
    );
    if (bundled && existsSync(bundled)) return bundled;
  }
  try {
    return execFileSync("where.exe", [candidate], { encoding: "utf8", windowsHide: true })
      .split(/\r?\n/)
      .map((line) => line.trim())
      .find(Boolean) ?? null;
  } catch {
    return null;
  }
}
