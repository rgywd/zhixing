import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

export class RunnerState {
  constructor(filename) {
    this.filename = filename;
    this.value = { sessions: {}, outbox: {} };
    if (existsSync(filename)) {
      this.value = JSON.parse(readFileSync(filename, "utf8"));
    }
    this.value.sessions ??= {};
    this.value.outbox ??= {};
  }

  get(sessionId) {
    return this.value.sessions[sessionId] ?? null;
  }

  set(sessionId, patch) {
    this.value.sessions[sessionId] = { ...(this.value.sessions[sessionId] ?? {}), ...patch };
    this.persist();
    return this.value.sessions[sessionId];
  }

  delete(sessionId) {
    delete this.value.sessions[sessionId];
    this.persist();
  }

  enqueueTransition(commandId, state, sessionState) {
    this.value.outbox[commandId] = { commandId, state, sessionState };
    this.persist();
    return this.value.outbox[commandId];
  }

  transitions() {
    return Object.values(this.value.outbox);
  }

  removeTransition(commandId) {
    delete this.value.outbox[commandId];
    this.persist();
  }

  persist() {
    mkdirSync(dirname(this.filename), { recursive: true });
    const temporary = `${this.filename}.tmp`;
    writeFileSync(temporary, `${JSON.stringify(this.value, null, 2)}\n`, { mode: 0o600 });
    renameSync(temporary, this.filename);
  }
}
