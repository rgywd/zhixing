import { createHash } from "node:crypto";
import { existsSync, mkdirSync, rmSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { CoreClient } from "./core-client.js";
import {
  buildCodexArgs,
  parseCodexAssistantMessage,
  parseCodexSessionId,
  parseCodexTurnOutcome,
  runCodex,
  terminateProcessTree,
} from "./codex-process.js";
import { ensurePhoneHookProfile } from "./phone-hook-profile.js";
import {
  buildRepositoryCatalog,
  repositoryCatalogFingerprint,
  validateRepositoryConfig,
} from "./repo-catalog.js";
import { RunnerState } from "./state.js";

const here = dirname(fileURLToPath(import.meta.url));

export const PHONE_DEVELOPER_INSTRUCTIONS = `You are running in a Zhixing mobile Work session.
The user sees your ordinary assistant messages automatically. In addition, you have exactly three zhixing_phone communication tools and must use them intentionally:
- report(text): call after a meaningful milestone, before a long unattended wait, and once with the final result before ending. Read and act on any queued user messages returned by the tool. Do not report every routine tool action.
- ask(questions): call only when a user decision blocks safe progress. Ask 1-4 concise choice questions; each may be single- or multi-select and the app always provides an Other field. If it times out, stop or proceed only with a safe reversible assumption.
- report_html(html, title): use for a long structured deliverable that is better opened as a report card. Write plain semantic HTML only (headings, paragraphs, lists, tables, pre/code, blockquote, details/summary, inline data: images); CSS, classes, scripts, div wrappers and external resources are stripped by the sanitizer, and the built-in template already provides typography and dark mode. Max 1 MiB.
Continue to write normal assistant responses. Never assume a phone tool call is the only record of your work.`;

export class WorkRunner {
  constructor({
    config,
    state,
    client,
    spawnCodex = runCodex,
    terminateCodex = terminateProcessTree,
    nodePath = process.execPath,
    mcpServerPath = resolve(here, "mcp-server.js"),
    hookScriptPath = resolve(here, "phone-stop-hook.js"),
  }) {
    this.config = config;
    this.state = state;
    this.client = client;
    this.spawnCodex = spawnCodex;
    this.terminateCodex = terminateCodex;
    this.nodePath = nodePath;
    this.mcpServerPath = mcpServerPath;
    this.hookScriptPath = hookScriptPath;
    this.active = new Map();
    this.stopped = false;
    this.flushPromise = Promise.resolve();
    this.repositories = buildRepositoryCatalog(config);
    this.publishedCatalogFingerprint = null;
    this.nextCatalogRefreshAt = 0;
    this.catalogReady = false;
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
        await this.refreshCatalog(true);
        this.catalogReady = true;
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
      await Promise.all([...this.active.values()].map(async (value) => {
        try {
          await this.terminateCodex(value.child);
        } catch (error) {
          this.logError("shutdown-cleanup", error);
        }
      }));
    }
  }

  stop() {
    this.stopped = true;
  }

  async pollOnce() {
    if (this.catalogReady) await this.refreshCatalog();
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
    const previous = this.state.get(command.sessionId) ?? {};
    const repo = this.repositories.find((candidate) => candidate.id === (command.payload.repoId ?? previous.repoId));
    if (!repo || !existsSync(repo.path)) {
      await this.commitTransition(command.id, "FAILED", sessionState(command.sessionId, "FAILED", "Repository is not available on the runner"));
      return;
    }
    const model = command.payload.model ?? previous.model;
    const reasoningEffort = command.payload.reasoningEffort ?? previous.reasoningEffort;
    const sessionToken = command.payload.sessionToken ?? previous.sessionToken;
    if (!repo.models.includes(model) || !repo.reasoningEfforts.includes(reasoningEffort) || !sessionToken) {
      await this.commitTransition(command.id, "FAILED", sessionState(command.sessionId, "FAILED", "Runner rejected the session snapshot"));
      return;
    }

    const pendingAttachments = command.payload.attachments?.length
      ? command.payload.attachments
      : previous.codexSessionId
        ? []
        : previous.pendingAttachments ?? [];
    this.state.set(command.sessionId, {
      repoId: repo.id,
      model,
      reasoningEffort,
      sessionToken,
      pendingAttachments,
      lastCommandId: command.id,
    });

    await this.client.ack(command.id, "CLAIMED", sessionState(command.sessionId, "RUNNING"));
    const cursorFile = resolve(dirname(this.config.stateFile), "cursors", `${command.sessionId}.json`);
    let attachmentDirectory = null;
    let hookOutboxDirectory = null;
    let profileName = null;
    let args;
    try {
      const downloaded = await this.downloadAttachments(command, pendingAttachments);
      attachmentDirectory = downloaded.directory;
      if (this.config.codexHome) {
        try {
          profileName = ensurePhoneHookProfile({
            codexHome: this.config.codexHome,
            nodePath: this.nodePath,
            hookScriptPath: this.hookScriptPath,
          }).profileName;
          hookOutboxDirectory = resolve(dirname(this.config.stateFile), "hooks", command.sessionId, command.id);
          mkdirSync(hookOutboxDirectory, { recursive: true });
        } catch (error) {
          profileName = null;
          hookOutboxDirectory = null;
          this.logError("phone-hook", error);
        }
      }
      args = buildCodexArgs({
        kind: command.kind === "RESUME" && !previous.codexSessionId ? "START" : command.kind,
        repoPath: repo.path,
        model,
        reasoningEffort,
        codexSessionId: previous.codexSessionId,
        imagePaths: downloaded.paths,
        profileName,
        developerInstructions: PHONE_DEVELOPER_INSTRUCTIONS,
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
    } catch (error) {
      if (attachmentDirectory) rmSync(attachmentDirectory, { recursive: true, force: true });
      if (hookOutboxDirectory) rmSync(hookOutboxDirectory, { recursive: true, force: true });
      await this.commitTransition(command.id, "FAILED", sessionState(command.sessionId, "FAILED", safeError(error)));
      return;
    }
    let discoveredSessionId = previous.codexSessionId ?? null;
    let resolveSemanticOutcome;
    const semanticOutcome = new Promise((resolve) => { resolveSemanticOutcome = resolve; });
    let running;
    try {
      running = this.spawnCodex({
        command: this.config.codexCommand ?? "codex",
        args,
        prompt: command.payload.message,
        cwd: repo.path,
        env: isolatedCodexEnv(this.config.codexHome, process.env, hookOutboxDirectory ? {
          ZHIXING_WORK_HOOK_OUTBOX: hookOutboxDirectory,
          ZHIXING_WORK_SESSION_ID: command.sessionId,
        } : {}),
        onEvent: (event) => {
          const parsed = parseCodexSessionId(event);
          if (parsed && parsed !== discoveredSessionId) {
            discoveredSessionId = parsed;
            this.state.set(command.sessionId, { codexSessionId: parsed, pendingAttachments: [] });
            this.client.updateState(command.sessionId, "RUNNING", null, parsed).catch((error) => this.logError("session-id", error));
          }
          const message = parseCodexAssistantMessage(event);
          if (message) {
            this.state.enqueueEvent(command.sessionId, {
              clientEventId: `${command.id}:${message.itemId}`,
              type: "ASSISTANT_MESSAGE",
              payload: { text: message.text },
            });
            this.flushOutbox().catch((error) => this.logError("assistant-message", error));
          }
          const outcome = parseCodexTurnOutcome(event);
          if (outcome) resolveSemanticOutcome(outcome);
        },
      });
    } catch (error) {
      if (attachmentDirectory) rmSync(attachmentDirectory, { recursive: true, force: true });
      if (hookOutboxDirectory) rmSync(hookOutboxDirectory, { recursive: true, force: true });
      await this.commitTransition(command.id, "FAILED", sessionState(command.sessionId, "FAILED", safeError(error)));
      return;
    }
    this.active.set(command.sessionId, { ...running, startCommandId: command.id, stoppedByUser: false });
    this.monitorCommand({
      command,
      running,
      semanticOutcome,
      getDiscoveredSessionId: () => discoveredSessionId,
      attachmentDirectory,
      hookOutboxDirectory,
    }).catch((error) => this.logError("process-exit", error));
  }

  async monitorCommand({
    command,
    running,
    semanticOutcome,
    getDiscoveredSessionId,
    attachmentDirectory,
    hookOutboxDirectory,
  }) {
    const processOutcome = running.completed.then(
      (result) => ({ source: "process", result }),
      (error) => ({ source: "process-error", error }),
    );
    const first = await Promise.race([
      processOutcome,
      semanticOutcome.then((outcome) => ({ source: "semantic", outcome })),
    ]);
    if (first.source === "semantic") {
      const graceMs = this.config.semanticExitGraceMs ?? 2_000;
      const exited = await Promise.race([
        processOutcome.then(() => true),
        delay(graceMs).then(() => false),
      ]);
      if (!exited) {
        try {
          await this.terminateCodex(running.child);
        } catch (error) {
          this.logError("process-cleanup", error);
        }
      }
    }
    try {
      const active = this.active.get(command.sessionId);
      this.active.delete(command.sessionId);
      if (active?.stoppedByUser) {
        if (!active.startCommandAcknowledged) await this.commitTransition(command.id, "COMPLETED", null);
        return;
      }
      const discoveredSessionId = getDiscoveredSessionId();
      const semanticSuccess = first.source === "semantic" && first.outcome.status === "COMPLETED";
      const processSuccess = first.source === "process" && first.result.code === 0;
      if ((semanticSuccess || processSuccess) && discoveredSessionId) {
        await this.commitTransition(command.id, "COMPLETED", sessionState(
          command.sessionId,
          "IDLE",
          "Codex 本轮已完成",
          discoveredSessionId,
        ));
      } else {
        const detail = first.source === "semantic" && first.outcome.detail
          ? first.outcome.detail
          : discoveredSessionId
            ? "Codex process exited with an error"
            : "Codex exited before returning a session ID";
        await this.commitTransition(command.id, "FAILED", sessionState(command.sessionId, "FAILED", detail, discoveredSessionId));
      }
    } finally {
      if (attachmentDirectory) rmSync(attachmentDirectory, { recursive: true, force: true });
      if (hookOutboxDirectory) rmSync(hookOutboxDirectory, { recursive: true, force: true });
    }
  }

  async downloadAttachments(command, attachments = command.payload.attachments ?? []) {
    if (!attachments.length) return { directory: null, paths: [] };
    const directory = resolve(dirname(this.config.stateFile), "attachments", command.sessionId, command.id);
    mkdirSync(directory, { recursive: true });
    try {
      const paths = [];
      for (const [index, attachment] of attachments.entries()) {
        const result = await this.client.downloadAttachment(this.config.id, attachment.id);
        const actualHash = createHash("sha256").update(result.data).digest("hex");
        if (attachment.sha256 && attachment.sha256 !== actualHash) throw new Error("Attachment checksum mismatch");
        const extension = extensionForImage(attachment.mimeType ?? result.mimeType);
        if (!extension) throw new Error("Runner rejected an unsupported image type");
        const filePath = resolve(directory, `${index + 1}-${attachment.id}${extension}`);
        writeFileSync(filePath, result.data, { flag: "wx" });
        paths.push(filePath);
      }
      return { directory, paths };
    } catch (error) {
      rmSync(directory, { recursive: true, force: true });
      throw error;
    }
  }

  async stopCommand(command) {
    const running = this.active.get(command.sessionId);
    if (running) {
      running.stoppedByUser = true;
      running.startCommandAcknowledged = true;
      await this.terminateCodex(running.child);
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
    const previous = this.flushPromise;
    let release;
    this.flushPromise = new Promise((resolve) => { release = resolve; });
    await previous;
    try {
      await this.flushOutboxNow();
    } finally {
      release();
    }
  }

  async flushOutboxNow() {
    for (const queued of this.state.events()) {
      await this.client.publishEvent(queued.sessionId, queued.event);
      this.state.removeEvent(queued.event.clientEventId);
    }
    for (const transition of this.state.transitions()) {
      await this.client.ack(transition.commandId, transition.state, transition.sessionState);
      this.state.removeTransition(transition.commandId);
    }
  }

  async refreshCatalog(force = false) {
    const now = Date.now();
    if (!force && now < this.nextCatalogRefreshAt) return false;
    this.nextCatalogRefreshAt = now + (this.config.catalogRefreshIntervalMs ?? 30_000);
    const repositories = buildRepositoryCatalog(this.config);
    const fingerprint = repositoryCatalogFingerprint(repositories);
    this.repositories = repositories;
    if (!force && fingerprint === this.publishedCatalogFingerprint) return false;
    try {
      await this.client.register({ ...this.config, repos: repositories });
      this.publishedCatalogFingerprint = fingerprint;
      return true;
    } catch (error) {
      this.nextCatalogRefreshAt = 0;
      throw error;
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
  validateRepositoryConfig(config);
}

export function isolatedCodexEnv(codexHome, source = process.env, extra = {}) {
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
    ...Object.entries(extra),
  ]);
}

function safeError(error) {
  return error instanceof Error ? error.message : String(error);
}

function extensionForImage(mimeType) {
  return ({
    "image/png": ".png",
    "image/jpeg": ".jpg",
    "image/webp": ".webp",
    "image/gif": ".gif",
  })[String(mimeType ?? "").split(";", 1)[0].toLowerCase()] ?? null;
}

function sessionState(sessionId, status, detail = null, codexSessionId = null) {
  return { sessionId, status, detail, codexSessionId };
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
