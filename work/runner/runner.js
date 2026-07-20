import { existsSync, mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { CoreClient } from "./core-client.js";
import { buildCodexArgs, parseCodexSessionId, runCodex } from "./codex-process.js";
import { RunnerState } from "./state.js";

const here = dirname(fileURLToPath(import.meta.url));

export class WorkRunner {
  constructor({ config, state, client, spawnCodex = runCodex, nodePath = process.execPath, mcpServerPath = resolve(here, "mcp-server.js") }) {
    this.config = config;
    this.state = state;
    this.client = client;
    this.spawnCodex = spawnCodex;
    this.nodePath = nodePath;
    this.mcpServerPath = mcpServerPath;
    this.active = new Map();
    this.stopped = false;
  }

  static fromConfig(config) {
    const state = new RunnerState(config.stateFile);
    const client = new CoreClient({ baseUrl: config.coreUrl, token: config.token });
    return new WorkRunner({ config, state, client });
  }

  async start() {
    validateConfig(this.config);
    let backoffMs = 500;
    while (!this.stopped) {
      try {
        await this.client.register(this.config);
        break;
      } catch (error) {
        this.logError("register", error);
        await delay(backoffMs);
        backoffMs = Math.min(backoffMs * 2, 30_000);
      }
    }
    const heartbeat = setInterval(() => {
      this.client.heartbeat(this.config.id).catch((error) => this.logError("heartbeat", error));
    }, 20_000);
    try {
      backoffMs = 500;
      while (!this.stopped) {
        try {
          await this.pollOnce();
          backoffMs = 500;
          await delay(this.config.pollIntervalMs ?? 2_000);
        } catch (error) {
          this.logError("poll", error);
          await delay(backoffMs);
          backoffMs = Math.min(backoffMs * 2, 30_000);
        }
      }
    } finally {
      clearInterval(heartbeat);
      for (const value of this.active.values()) value.child.kill();
    }
  }

  stop() {
    this.stopped = true;
  }

  async pollOnce() {
    await this.flushOutbox();
    const commands = await this.client.commands(this.config.id);
    for (const command of commands) {
      if (this.active.has(command.sessionId) && !["STOP", "COMPLETE"].includes(command.kind)) continue;
      if (command.kind === "START" || command.kind === "RESUME") {
        await this.startCommand(command);
      } else if (command.kind === "STOP" || command.kind === "COMPLETE") {
        await this.stopCommand(command);
      } else {
        await this.commitTransition(command.id, "FAILED", sessionState(command.sessionId, "FAILED", "Unsupported runner command"));
      }
    }
  }

  async startCommand(command) {
    const repo = this.config.repos.find((candidate) => candidate.id === (command.payload.repoId ?? this.state.get(command.sessionId)?.repoId));
    if (!repo || !existsSync(repo.path)) {
      await this.commitTransition(command.id, "FAILED", sessionState(command.sessionId, "FAILED", "Repository is not available on the runner"));
      return;
    }
    const previous = this.state.get(command.sessionId) ?? {};
    const model = command.payload.model ?? previous.model;
    const reasoningEffort = command.payload.reasoningEffort ?? previous.reasoningEffort;
    const sessionToken = command.payload.sessionToken ?? previous.sessionToken;
    if (!repo.models.includes(model) || !repo.reasoningEfforts.includes(reasoningEffort) || !sessionToken) {
      await this.commitTransition(command.id, "FAILED", sessionState(command.sessionId, "FAILED", "Runner rejected the session snapshot"));
      return;
    }

    await this.client.ack(command.id, "CLAIMED", sessionState(command.sessionId, "RUNNING"));
    const cursorFile = resolve(dirname(this.config.stateFile), "cursors", `${command.sessionId}.json`);
    const args = buildCodexArgs({
      kind: command.kind,
      repoPath: repo.path,
      model,
      reasoningEffort,
      codexSessionId: previous.codexSessionId,
      mcp: {
        nodePath: this.nodePath,
        mcpServerPath: this.mcpServerPath,
        coreUrl: this.config.coreUrl,
        sessionId: command.sessionId,
        sessionToken,
        cursorFile,
        initialInboxCursor: command.payload.inboxCursor ?? 0,
      },
    });
    this.state.set(command.sessionId, {
      repoId: repo.id,
      model,
      reasoningEffort,
      sessionToken,
      lastCommandId: command.id,
    });
    let discoveredSessionId = previous.codexSessionId ?? null;
    let running;
    try {
      running = this.spawnCodex({
        command: this.config.codexCommand ?? "codex",
        args,
        prompt: command.payload.message,
        cwd: repo.path,
        env: isolatedCodexEnv(this.config.codexHome),
        onEvent: (event) => {
          const parsed = parseCodexSessionId(event);
          if (parsed && parsed !== discoveredSessionId) {
            discoveredSessionId = parsed;
            this.state.set(command.sessionId, { codexSessionId: parsed });
            this.client.updateState(command.sessionId, "RUNNING", null, parsed).catch((error) => this.logError("session-id", error));
          }
        },
      });
    } catch (error) {
      await this.commitTransition(command.id, "FAILED", sessionState(command.sessionId, "FAILED", safeError(error)));
      return;
    }
    this.active.set(command.sessionId, { ...running, startCommandId: command.id, stoppedByUser: false });
    running.completed.then(async (result) => {
      const active = this.active.get(command.sessionId);
      this.active.delete(command.sessionId);
      if (active?.stoppedByUser) {
        if (!active.startCommandAcknowledged) await this.commitTransition(command.id, "COMPLETED", null);
        return;
      }
      if (result.code === 0 && discoveredSessionId) {
        await this.commitTransition(command.id, "COMPLETED", sessionState(
          command.sessionId,
          "IDLE",
          "Codex turn completed",
          discoveredSessionId,
        ));
      } else {
        const detail = discoveredSessionId ? "Codex process exited with an error" : "Codex exited before returning a session ID";
        await this.commitTransition(command.id, "FAILED", sessionState(command.sessionId, "FAILED", detail, discoveredSessionId));
      }
    }).catch((error) => this.logError("process-exit", error));
  }

  async stopCommand(command) {
    const running = this.active.get(command.sessionId);
    if (running) {
      running.stoppedByUser = true;
      running.startCommandAcknowledged = true;
      running.child.kill();
      this.state.enqueueTransition(running.startCommandId, "COMPLETED", null);
    }
    let finalState;
    if (command.kind === "COMPLETE") {
      finalState = sessionState(command.sessionId, "COMPLETED", "Session completed by user");
      this.state.enqueueTransition(command.id, "COMPLETED", finalState);
      this.state.delete(command.sessionId);
    } else {
      finalState = sessionState(command.sessionId, "IDLE", "Codex turn stopped by user");
      this.state.enqueueTransition(command.id, "COMPLETED", finalState);
    }
    await this.flushOutbox();
  }

  async commitTransition(commandId, state, snapshot) {
    this.state.enqueueTransition(commandId, state, snapshot);
    await this.flushOutbox();
  }

  async flushOutbox() {
    for (const transition of this.state.transitions()) {
      await this.client.ack(transition.commandId, transition.state, transition.sessionState);
      this.state.removeTransition(transition.commandId);
    }
  }

  logError(area, error) {
    console.error(`[${area}] ${safeError(error)}`);
  }
}

function validateConfig(config) {
  for (const key of ["id", "name", "version", "coreUrl", "token", "stateFile", "codexHome"]) {
    if (!config[key]) throw new Error(`Runner config is missing ${key}`);
  }
  if (!Array.isArray(config.repos) || !config.repos.length) throw new Error("Runner config needs at least one repository");
  for (const repo of config.repos) {
    if (!repo.id || !repo.name || !repo.path || !repo.models?.length || !repo.reasoningEfforts?.length) {
      throw new Error(`Repository ${repo.id ?? "<unknown>"} is incomplete`);
    }
  }
}

export function isolatedCodexEnv(codexHome, source = process.env) {
  if (!codexHome) return source;
  mkdirSync(codexHome, { recursive: true });
  const allowed = new Set([
    "PATH", "Path", "PATHEXT", "SystemRoot", "SYSTEMROOT", "ComSpec", "COMSPEC",
    "TEMP", "TMP", "USERPROFILE", "HOME", "APPDATA", "LOCALAPPDATA", "ProgramFiles",
    "ProgramFiles(x86)", "HTTP_PROXY", "HTTPS_PROXY", "NO_PROXY", "ALL_PROXY",
    "http_proxy", "https_proxy", "no_proxy", "all_proxy", "SSL_CERT_FILE", "SSL_CERT_DIR",
  ]);
  return Object.fromEntries([
    ...Object.entries(source).filter(([key]) => allowed.has(key)),
    ["CODEX_HOME", codexHome],
  ]);
}

function safeError(error) {
  return error instanceof Error ? error.message : String(error);
}

function sessionState(sessionId, status, detail = null, codexSessionId = null) {
  return { sessionId, status, detail, codexSessionId };
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
