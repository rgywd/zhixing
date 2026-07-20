const PROTOCOL_HEADERS = { "x-zhixing-work-protocol": "1" };

export class CoreClient {
  constructor({ baseUrl, token }) {
    this.baseUrl = baseUrl.replace(/\/$/, "");
    this.token = token;
  }

  async request(path, { method = "GET", body, idempotencyKey, signal } = {}) {
    const response = await fetch(`${this.baseUrl}${path}`, {
      method,
      signal,
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
        name: config.name,
        version: config.version,
        capabilities: { codex: true, phoneLineProtocol: 1 },
        repos: config.repos.map((repo) => ({
          id: repo.id,
          name: repo.name,
          models: repo.models,
          reasoningEfforts: repo.reasoningEfforts,
          available: true,
        })),
      },
    });
  }

  heartbeat(runnerId) {
    return this.request("/v1/runner/heartbeat", { method: "POST", body: { runnerId } });
  }

  async commands(runnerId) {
    const payload = await this.request(`/v1/runner/commands?runnerId=${encodeURIComponent(runnerId)}`);
    return payload.commands;
  }

  ack(commandId, state) {
    return this.request(`/v1/runner/commands/${encodeURIComponent(commandId)}/ack`, {
      method: "POST",
      body: { state },
    });
  }

  updateState(sessionId, status, detail = null, codexSessionId = null) {
    return this.request(`/v1/runner/sessions/${encodeURIComponent(sessionId)}/state`, {
      method: "POST",
      body: { status, detail, codexSessionId },
    });
  }
}
