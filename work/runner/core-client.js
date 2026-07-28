import { randomUUID } from "node:crypto";

const PROTOCOL_HEADERS = { "x-zhixing-work-protocol": "1" };

export class CoreClient {
  constructor({ baseUrl, token }) {
    this.baseUrl = baseUrl.replace(/\/$/, "");
    this.token = token;
    this.instanceId = randomUUID();
  }

  async request(path, { method = "GET", body, idempotencyKey, signal } = {}) {
    const timeoutSignal = AbortSignal.timeout(20_000);
    const requestSignal = signal ? AbortSignal.any([signal, timeoutSignal]) : timeoutSignal;
    const response = await fetch(`${this.baseUrl}${path}`, {
      method,
      signal: requestSignal,
      headers: {
        ...PROTOCOL_HEADERS,
        authorization: `Bearer ${this.token}`,
        ...(body ? { "content-type": "application/json" } : {}),
        ...(idempotencyKey ? { "idempotency-key": idempotencyKey } : {}),
      },
      body: body ? JSON.stringify(body) : undefined,
    });
    const payload = await response.json().catch(() => ({}));
    if (!response.ok) {
      const error = new Error(payload.message ?? `Core request failed with ${response.status}`);
      error.statusCode = response.status;
      throw error;
    }
    return payload;
  }

  register(config) {
    return this.request("/v1/runner/register", {
      method: "POST",
      body: {
        id: config.id,
        instanceId: this.instanceId,
        name: config.name,
        version: config.version,
        capabilities: {
          codex: config.repos.some((repo) => repo.runtimes.some((runtime) => runtime.id === "codex")),
          claudeCode: config.repos.some((repo) => repo.runtimes.some((runtime) => runtime.id === "claude-code")),
          phoneLineProtocol: 1,
        },
        repos: config.repos.map((repo) => ({
          id: repo.id,
          name: repo.name,
          group: repo.group ?? null,
          runtimes: repo.runtimes.map((runtime) => ({
            id: runtime.id,
            name: runtime.name,
            models: runtime.models,
            reasoningEfforts: runtime.reasoningEfforts,
            reasoningEffortsByModel: runtime.reasoningEffortsByModel ?? {},
          })),
          models: repo.models,
          reasoningEfforts: repo.reasoningEfforts,
          available: repo.available !== false,
        })),
      },
    });
  }

  heartbeat(runnerId) {
    return this.request("/v1/runner/heartbeat", { method: "POST", body: { runnerId, instanceId: this.instanceId } });
  }

  async commands(runnerId) {
    const payload = await this.request(`/v1/runner/commands?runnerId=${encodeURIComponent(runnerId)}&instanceId=${encodeURIComponent(this.instanceId)}`);
    return payload.commands;
  }

  async downloadAttachment(runnerId, attachmentId) {
    let lastError;
    for (let attempt = 0; attempt < 3; attempt += 1) {
      try {
        const response = await fetch(
          `${this.baseUrl}/v1/runner/attachments/${encodeURIComponent(attachmentId)}?runnerId=${encodeURIComponent(runnerId)}`,
          {
            signal: AbortSignal.timeout(15_000),
            headers: {
              ...PROTOCOL_HEADERS,
              authorization: `Bearer ${this.token}`,
            },
          },
        );
        if (!response.ok) {
          const payload = await response.json().catch(() => ({}));
          const error = new Error(payload.message ?? `Attachment download failed with ${response.status}`);
          error.statusCode = response.status;
          throw error;
        }
        return {
          data: Buffer.from(await response.arrayBuffer()),
          sha256: response.headers.get("x-content-sha256"),
          mimeType: response.headers.get("content-type"),
        };
      } catch (error) {
        lastError = error;
        if (error.statusCode && error.statusCode < 500) throw error;
        if (attempt < 2) await new Promise((resolve) => setTimeout(resolve, 250 * (2 ** attempt)));
      }
    }
    throw lastError;
  }

  async ack(commandId, state, sessionState = null) {
    let lastError;
    for (let attempt = 0; attempt < 3; attempt += 1) {
      try {
        return await this.request(`/v1/runner/commands/${encodeURIComponent(commandId)}/ack`, {
          method: "POST",
          body: { state, instanceId: this.instanceId, sessionState },
        });
      } catch (error) {
        lastError = error;
        if (error.statusCode && error.statusCode < 500) throw error;
        if (attempt < 2) await new Promise((resolve) => setTimeout(resolve, 500 * (2 ** attempt)));
      }
    }
    throw lastError;
  }

  updateState(sessionId, status, detail = null, runtimeSessionId = null, runtime = null) {
    return this.request(`/v1/runner/sessions/${encodeURIComponent(sessionId)}/state`, {
      method: "POST",
      body: {
        status,
        detail,
        runtime,
        runtimeSessionId,
        ...(runtime === "codex" ? { codexSessionId: runtimeSessionId } : {}),
        instanceId: this.instanceId,
      },
    });
  }

  publishEvent(sessionId, event) {
    return this.request(`/v1/runner/sessions/${encodeURIComponent(sessionId)}/events`, {
      method: "POST",
      body: { ...event, instanceId: this.instanceId },
    });
  }
}
