import { spawn } from "node:child_process";
import { execFileSync } from "node:child_process";
import { existsSync, mkdirSync, renameSync, writeFileSync } from "node:fs";
import { createInterface } from "node:readline";
import { dirname, extname } from "node:path";

const PHONE_SETTINGS = JSON.stringify({
  disableAllHooks: true,
});

export function writeClaudeMcpConfig(filename, {
  nodePath,
  mcpServerPath,
  coreUrl,
  sessionId,
  sessionToken,
  cursorFile,
  initialInboxCursor = 0,
}) {
  const config = {
    mcpServers: {
      zhixing_phone: {
        type: "stdio",
        command: nodePath,
        args: [mcpServerPath],
        env: {
          WORK_CORE_URL: coreUrl,
          WORK_SESSION_ID: sessionId,
          WORK_SESSION_TOKEN: sessionToken,
          WORK_CURSOR_FILE: cursorFile,
          WORK_INITIAL_INBOX_CURSOR: String(initialInboxCursor),
        },
      },
    },
  };
  mkdirSync(dirname(filename), { recursive: true });
  const temporary = `${filename}.tmp`;
  writeFileSync(temporary, `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });
  renameSync(temporary, filename);
  return filename;
}

export function buildClaudeArgs({
  kind,
  model,
  reasoningEffort,
  runtimeSessionId,
  developerInstructions,
  mcpConfigPath,
  additionalDirectories = [],
  slashCommandsEnabled = false,
}) {
  if (!mcpConfigPath) throw new Error("Claude Code requires an explicit phone-line MCP config");
  if (kind === "RESUME" && !runtimeSessionId) {
    throw new Error("Cannot resume without a Claude Code session ID");
  }
  return [
    "-p",
    "--output-format", "stream-json",
    "--verbose",
    "--permission-mode", "bypassPermissions",
    "--model", model,
    "--effort", reasoningEffort,
    "--settings", PHONE_SETTINGS,
    "--setting-sources", "user,project",
    "--strict-mcp-config",
    "--mcp-config", mcpConfigPath,
    ...(!slashCommandsEnabled ? ["--disable-slash-commands"] : []),
    "--no-chrome",
    ...(developerInstructions ? ["--append-system-prompt", developerInstructions] : []),
    ...additionalDirectories.flatMap((directory) => ["--add-dir", directory]),
    ...(kind === "RESUME" ? ["--resume", runtimeSessionId] : []),
  ];
}

export function parseClaudeSessionId(event) {
  if (event?.type !== "system" || event?.subtype !== "init") return null;
  return String(event.session_id ?? event.sessionId ?? "").trim() || null;
}

export function parseClaudeAssistantMessage(event) {
  if (event?.type !== "assistant" || event.error) return null;
  const content = Array.isArray(event.message?.content) ? event.message.content : [];
  const text = content
    .filter((part) => part?.type === "text")
    .map((part) => String(part.text ?? "").trim())
    .filter(Boolean)
    .join("\n\n");
  const itemId = String(event.message?.id ?? event.uuid ?? "").trim();
  if (!text || !itemId) return null;
  return { itemId, text };
}

export function parseClaudeTurnOutcome(event) {
  if (event?.type !== "result") return null;
  if (event.is_error === true || event.error || event.api_error_status) {
    return {
      status: "FAILED",
      detail: String(event.result ?? event.error ?? "Claude Code turn failed").trim() || "Claude Code turn failed",
    };
  }
  return { status: "COMPLETED", detail: null };
}

export function runClaude({ command, args, prompt, cwd, env = process.env, spawnImpl = spawn, onEvent = () => {} }) {
  const executable = resolveClaudeCommand(command);
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
      // Claude Code stream-json should be JSONL; ignore local diagnostics instead of forwarding them to Core.
    }
  });
  const completed = new Promise((resolve, reject) => {
    child.once("error", reject);
    child.once("close", (code, signal) => resolve({ code, signal, stderr }));
  });
  return { child, completed };
}

export function resolveClaudeCommand(command, platform = process.platform, findExecutable = defaultFindExecutable) {
  if (platform !== "win32") return command;
  const extension = extname(command).toLowerCase();
  if ([".cmd", ".bat", ".ps1"].includes(extension)) {
    throw new Error("claudeCommand must point to claude.exe on Windows, not a shell shim");
  }
  if (extension || /[\\/]/.test(command)) return command;
  return findExecutable(`${command}.exe`) ?? command;
}

function defaultFindExecutable(candidate) {
  try {
    return execFileSync("where.exe", [candidate], { encoding: "utf8", windowsHide: true })
      .split(/\r?\n/)
      .map((line) => line.trim())
      .find((line) => line && existsSync(line)) ?? null;
  } catch {
    return null;
  }
}
