import { createHash, createHmac, randomUUID, timingSafeEqual } from "node:crypto";
import { DatabaseSync } from "node:sqlite";

const SESSION_TERMINAL = new Set(["COMPLETED"]);
const SESSION_ACTIVE = new Set(["QUEUED", "RUNNING", "WAITING_FOR_USER"]);

function tokenHash(token) {
  return createHash("sha256").update(token).digest("hex");
}

function json(value) {
  return JSON.stringify(value ?? null);
}

function parseJson(value, fallback = null) {
  if (value == null) return fallback;
  return JSON.parse(value);
}

function normalizeRuntimeCatalog(repo) {
  const advertised = Array.isArray(repo.runtimes) ? repo.runtimes : [];
  const runtimes = advertised.length
    ? advertised
    : [{
      id: "codex",
      name: "Codex",
      models: repo.models ?? [],
      reasoningEfforts: repo.reasoningEfforts ?? [],
    }];
  const seen = new Set();
  return runtimes.map((runtime) => {
    const normalized = {
      id: String(runtime.id ?? "").trim(),
      name: String(runtime.name ?? runtime.id ?? "").trim(),
      models: [...new Set((runtime.models ?? []).map(String).filter(Boolean))],
      reasoningEfforts: [...new Set((runtime.reasoningEfforts ?? []).map(String).filter(Boolean))],
    };
    if (
      !normalized.id
      || !normalized.name
      || !normalized.models.length
      || !normalized.reasoningEfforts.length
      || seen.has(normalized.id)
    ) {
      throw Object.assign(new Error("Runner advertised an invalid runtime catalog"), { statusCode: 400 });
    }
    seen.add(normalized.id);
    return normalized;
  });
}

function runtimeCatalogFromRow(row) {
  const runtimes = parseJson(row.runtimes_json, []);
  if (Array.isArray(runtimes) && runtimes.length) return runtimes;
  return [{
    id: "codex",
    name: "Codex",
    models: parseJson(row.models_json, []),
    reasoningEfforts: parseJson(row.efforts_json, []),
  }];
}

function id(prefix) {
  return `${prefix}_${randomUUID()}`;
}

export class WorkStore {
  constructor({ filename = ":memory:", userToken, runnerTokens, sessionSecret }) {
    if (!userToken || !runnerTokens || !Object.keys(runnerTokens).length || !sessionSecret) {
      throw new Error("userToken, runnerTokens and sessionSecret are required");
    }
    this.db = new DatabaseSync(filename);
    this.userTokenHash = tokenHash(userToken);
    this.runnerTokenHashes = new Map(Object.entries(runnerTokens).map(([runnerId, token]) => [runnerId, tokenHash(token)]));
    this.sessionSecret = sessionSecret;
    this.db.exec("PRAGMA journal_mode = WAL; PRAGMA foreign_keys = ON;");
    this.migrate();
  }

  migrate() {
    this.db.exec(`
      CREATE TABLE IF NOT EXISTS runners (
        id TEXT PRIMARY KEY,
        instance_id TEXT,
        name TEXT NOT NULL,
        version TEXT NOT NULL,
        online INTEGER NOT NULL DEFAULT 1,
        lease_until TEXT,
        capabilities_json TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS repos (
        runner_id TEXT NOT NULL REFERENCES runners(id) ON DELETE CASCADE,
        id TEXT NOT NULL,
        name TEXT NOT NULL,
        group_name TEXT,
        models_json TEXT NOT NULL,
        efforts_json TEXT NOT NULL,
        runtimes_json TEXT NOT NULL DEFAULT '[]',
        available INTEGER NOT NULL DEFAULT 1,
        PRIMARY KEY (runner_id, id)
      );
      CREATE TABLE IF NOT EXISTS sessions (
        id TEXT PRIMARY KEY,
        runner_id TEXT NOT NULL,
        repo_id TEXT NOT NULL,
        repo_name TEXT NOT NULL,
        title TEXT NOT NULL DEFAULT '',
        model TEXT NOT NULL,
        reasoning_effort TEXT NOT NULL,
        status TEXT NOT NULL,
        runtime TEXT NOT NULL DEFAULT 'codex',
        runtime_session_id TEXT,
        codex_session_id TEXT,
        last_seq INTEGER NOT NULL DEFAULT 0,
        inbox_cursor INTEGER NOT NULL DEFAULT 0,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS events (
        session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
        seq INTEGER NOT NULL,
        id TEXT NOT NULL UNIQUE,
        type TEXT NOT NULL,
        payload_json TEXT NOT NULL,
        created_at TEXT NOT NULL,
        PRIMARY KEY (session_id, seq)
      );
      CREATE TABLE IF NOT EXISTS attachments (
        id TEXT PRIMARY KEY,
        session_id TEXT REFERENCES sessions(id) ON DELETE CASCADE,
        file_name TEXT NOT NULL,
        mime_type TEXT NOT NULL,
        size INTEGER NOT NULL,
        sha256 TEXT NOT NULL,
        data BLOB NOT NULL,
        created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS commands (
        id TEXT PRIMARY KEY,
        runner_id TEXT NOT NULL,
        session_id TEXT NOT NULL,
        kind TEXT NOT NULL,
        payload_json TEXT NOT NULL,
        state TEXT NOT NULL DEFAULT 'PENDING',
        claimed_by TEXT,
        lease_until TEXT,
        created_at TEXT NOT NULL,
        updated_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS asks (
        id TEXT PRIMARY KEY,
        session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
        client_call_id TEXT NOT NULL,
        questions_json TEXT NOT NULL,
        status TEXT NOT NULL DEFAULT 'PENDING',
        answers_json TEXT,
        created_at TEXT NOT NULL,
        answered_at TEXT,
        UNIQUE(session_id, client_call_id)
      );
      CREATE TABLE IF NOT EXISTS reports (
        id TEXT PRIMARY KEY,
        session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
        title TEXT NOT NULL,
        html TEXT NOT NULL,
        created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS session_tokens (
        jti TEXT PRIMARY KEY,
        session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
        expires_at INTEGER NOT NULL,
        revoked_at TEXT,
        created_at TEXT NOT NULL
      );
      CREATE TABLE IF NOT EXISTS idempotency (
        scope TEXT NOT NULL,
        key TEXT NOT NULL,
        response_json TEXT NOT NULL,
        created_at TEXT NOT NULL,
        PRIMARY KEY (scope, key)
      );
    `);
    this.ensureColumn("commands", "claimed_by", "TEXT");
    this.ensureColumn("commands", "lease_until", "TEXT");
    this.ensureColumn("runners", "instance_id", "TEXT");
    this.ensureColumn("repos", "group_name", "TEXT");
    this.ensureColumn("repos", "runtimes_json", "TEXT NOT NULL DEFAULT '[]'");
    this.ensureColumn("sessions", "archived_at", "TEXT");
    this.ensureColumn("sessions", "title", "TEXT NOT NULL DEFAULT ''");
    this.ensureColumn("sessions", "runtime", "TEXT NOT NULL DEFAULT 'codex'");
    this.ensureColumn("sessions", "runtime_session_id", "TEXT");
    this.db.prepare("UPDATE sessions SET title=repo_name WHERE title=''").run();
    this.db.prepare(
      "UPDATE sessions SET runtime_session_id=codex_session_id WHERE runtime='codex' AND runtime_session_id IS NULL",
    ).run();
    this.recoverInterruptedAsks();
  }

  ensureColumn(table, column, definition) {
    const existing = this.db.prepare(`PRAGMA table_info(${table})`).all().some((row) => row.name === column);
    if (!existing) this.db.exec(`ALTER TABLE ${table} ADD COLUMN ${column} ${definition}`);
  }

  recoverInterruptedAsks() {
    const pending = this.db.prepare("SELECT id FROM asks WHERE status='PENDING'").all();
    for (const ask of pending) this.timeoutAsk(ask.id);
  }

  close() {
    this.db.close();
  }

  authenticateUser(token) {
    return this.safeHashEquals(token, this.userTokenHash);
  }

  authenticateRunner(token, runnerId) {
    const expected = this.runnerTokenHashes.get(runnerId);
    return Boolean(expected) && this.safeHashEquals(token, expected);
  }

  safeHashEquals(token, expectedHash) {
    if (!token) return false;
    const actual = Buffer.from(tokenHash(token));
    const expected = Buffer.from(expectedHash);
    return actual.length === expected.length && timingSafeEqual(actual, expected);
  }

  createSessionToken(sessionId) {
    const issuedAt = Math.floor(Date.now() / 1000);
    this.db.prepare("DELETE FROM session_tokens WHERE expires_at<=?").run(issuedAt);
    const expiresAt = issuedAt + 24 * 60 * 60;
    const jti = randomUUID();
    const payload = Buffer.from(json({
      sessionId,
      scope: "mcp",
      v: 1,
      jti,
      iat: issuedAt,
      exp: expiresAt,
    })).toString("base64url");
    const signature = createHmac("sha256", this.sessionSecret).update(payload).digest("base64url");
    this.db.prepare("INSERT INTO session_tokens(jti, session_id, expires_at, created_at) VALUES (?, ?, ?, ?)")
      .run(jti, sessionId, expiresAt, new Date().toISOString());
    return `zsm.${payload}.${signature}`;
  }

  verifySessionToken(token, expectedSessionId) {
    const [prefix, payload, signature] = String(token ?? "").split(".");
    if (prefix !== "zsm" || !payload || !signature) return false;
    const expected = createHmac("sha256", this.sessionSecret).update(payload).digest("base64url");
    const actualBuffer = Buffer.from(signature);
    const expectedBuffer = Buffer.from(expected);
    if (actualBuffer.length !== expectedBuffer.length || !timingSafeEqual(actualBuffer, expectedBuffer)) return false;
    try {
      const claims = parseJson(Buffer.from(payload, "base64url").toString("utf8"));
      const session = this.getSession(claims.sessionId);
      const tokenRow = this.db.prepare(`
        SELECT 1 FROM session_tokens WHERE jti=? AND session_id=? AND revoked_at IS NULL AND expires_at>?
      `).get(claims.jti, claims.sessionId, Math.floor(Date.now() / 1000));
      return claims.scope === "mcp"
        && claims.sessionId === expectedSessionId
        && Number(claims.exp) > Math.floor(Date.now() / 1000)
        && Boolean(tokenRow)
        && session
        && !SESSION_TERMINAL.has(session.status);
    } catch {
      return false;
    }
  }

  withIdempotency(scope, key, operation) {
    if (!key) throw Object.assign(new Error("Idempotency-Key is required"), { statusCode: 400 });
    this.db.exec("BEGIN IMMEDIATE");
    try {
      const existing = this.db.prepare("SELECT response_json FROM idempotency WHERE scope = ? AND key = ?").get(scope, key);
      if (existing) {
        this.db.exec("COMMIT");
        return parseJson(existing.response_json);
      }
      const response = operation();
      this.db.prepare("INSERT INTO idempotency(scope, key, response_json, created_at) VALUES (?, ?, ?, ?)")
        .run(scope, key, json(response), new Date().toISOString());
      this.db.exec("COMMIT");
      return response;
    } catch (error) {
      this.db.exec("ROLLBACK");
      throw error;
    }
  }

  registerRunner(input) {
    if (!String(input.instanceId ?? "").trim()) {
      throw Object.assign(new Error("Runner instanceId is required"), { statusCode: 400 });
    }
    const now = new Date().toISOString();
    const leaseUntil = new Date(Date.now() + 45_000).toISOString();
    this.db.exec("BEGIN IMMEDIATE");
    try {
      this.db.prepare(`
        UPDATE commands SET state='PENDING', claimed_by=NULL, lease_until=NULL, updated_at=?
        WHERE runner_id=? AND state='CLAIMED' AND (claimed_by IS NULL OR claimed_by<>?)
      `).run(now, input.id, input.instanceId);
      this.db.prepare(`
        INSERT INTO runners(id, instance_id, name, version, online, lease_until, capabilities_json, updated_at)
        VALUES (?, ?, ?, ?, 1, ?, ?, ?)
        ON CONFLICT(id) DO UPDATE SET instance_id=excluded.instance_id, name=excluded.name, version=excluded.version, online=1,
          lease_until=excluded.lease_until, capabilities_json=excluded.capabilities_json, updated_at=excluded.updated_at
      `).run(input.id, input.instanceId, input.name, input.version, leaseUntil, json(input.capabilities ?? {}), now);
      this.db.prepare("DELETE FROM repos WHERE runner_id = ?").run(input.id);
      const insertRepo = this.db.prepare(`
        INSERT INTO repos(
          runner_id, id, name, group_name, models_json, efforts_json, runtimes_json, available
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      `);
      for (const repo of input.repos ?? []) {
        const runtimes = normalizeRuntimeCatalog(repo);
        insertRepo.run(
          input.id,
          repo.id,
          repo.name,
          repo.group ?? null,
          json(repo.models ?? []),
          json(repo.reasoningEfforts ?? []),
          json(runtimes),
          repo.available === false ? 0 : 1,
        );
      }
      this.db.exec("COMMIT");
    } catch (error) {
      this.db.exec("ROLLBACK");
      throw error;
    }
    return { accepted: true, leaseUntil };
  }

  isRunnerInstance(runnerId, instanceId) {
    if (!instanceId) return false;
    return Boolean(this.db.prepare("SELECT 1 FROM runners WHERE id=? AND instance_id=?").get(runnerId, instanceId));
  }

  heartbeatRunner(runnerId, instanceId) {
    const leaseUntil = new Date(Date.now() + 45_000).toISOString();
    const result = this.db.prepare("UPDATE runners SET online=1, lease_until=?, updated_at=? WHERE id=? AND instance_id=?")
      .run(leaseUntil, new Date().toISOString(), runnerId, instanceId);
    if (!result.changes) throw Object.assign(new Error("Runner instance is stale"), { statusCode: 409 });
    this.db.prepare("UPDATE commands SET lease_until=? WHERE state='CLAIMED' AND claimed_by=?")
      .run(leaseUntil, instanceId);
    return { accepted: true, leaseUntil };
  }

  listRunners() {
    const now = new Date().toISOString();
    return this.db.prepare("SELECT * FROM runners ORDER BY name").all().map((row) => ({
      id: row.id,
      name: row.name,
      version: row.version,
      online: Boolean(row.online) && row.lease_until > now,
      leaseUntil: row.lease_until,
      capabilities: parseJson(row.capabilities_json, {}),
    }));
  }

  listRepos(runnerId) {
    return this.db.prepare("SELECT * FROM repos WHERE runner_id=? ORDER BY name").all(runnerId).map((row) => ({
      id: row.id,
      runnerId: row.runner_id,
      name: row.name,
      group: row.group_name,
      models: parseJson(row.models_json, []),
      reasoningEfforts: parseJson(row.efforts_json, []),
      runtimes: runtimeCatalogFromRow(row),
      available: Boolean(row.available),
    }));
  }

  createAttachment({ fileName, mimeType, data }) {
    const safeName = String(fileName ?? "image").replace(/[\\/\0-\x1f]/g, "_").slice(0, 160) || "image";
    const bytes = Buffer.from(data ?? []);
    const attachmentId = id("att");
    const now = new Date().toISOString();
    this.db.prepare("DELETE FROM attachments WHERE session_id IS NULL AND created_at<?")
      .run(new Date(Date.now() - 24 * 60 * 60 * 1000).toISOString());
    this.db.prepare(`
      INSERT INTO attachments(id, session_id, file_name, mime_type, size, sha256, data, created_at)
      VALUES (?, NULL, ?, ?, ?, ?, ?, ?)
    `).run(attachmentId, safeName, mimeType, bytes.length, createHash("sha256").update(bytes).digest("hex"), bytes, now);
    return this.attachmentMetadata(attachmentId);
  }

  attachmentMetadata(attachmentId) {
    const row = this.db.prepare("SELECT id, file_name, mime_type, size, sha256 FROM attachments WHERE id=?").get(attachmentId);
    return row ? {
      id: row.id,
      fileName: row.file_name,
      mimeType: row.mime_type,
      size: row.size,
      sha256: row.sha256,
    } : null;
  }

  getAttachmentForRunner(attachmentId, runnerId) {
    const row = this.db.prepare(`
      SELECT a.* FROM attachments a
      JOIN sessions s ON s.id=a.session_id
      WHERE a.id=? AND s.runner_id=?
    `).get(attachmentId, runnerId);
    return row ? {
      id: row.id,
      fileName: row.file_name,
      mimeType: row.mime_type,
      size: row.size,
      sha256: row.sha256,
      data: row.data,
    } : null;
  }

  validateAttachments(attachmentIds) {
    if (attachmentIds == null) return [];
    if (!Array.isArray(attachmentIds) || attachmentIds.some((attachmentId) => typeof attachmentId !== "string")) {
      throw Object.assign(new Error("attachmentIds must be an array of IDs"), { statusCode: 400 });
    }
    const ids = [...new Set(attachmentIds)];
    if (ids.length > 4) throw Object.assign(new Error("At most 4 images are allowed per message"), { statusCode: 400 });
    return ids.map((attachmentId) => {
      const row = this.db.prepare("SELECT session_id FROM attachments WHERE id=?").get(attachmentId);
      if (!row || row.session_id) {
        throw Object.assign(new Error("Attachment is missing or already used"), { statusCode: 409 });
      }
      return this.attachmentMetadata(attachmentId);
    });
  }

  bindAttachments(sessionId, attachmentIds) {
    const metadata = this.validateAttachments(attachmentIds);
    for (const attachment of metadata) {
      this.db.prepare("UPDATE attachments SET session_id=? WHERE id=? AND session_id IS NULL").run(sessionId, attachment.id);
    }
    return metadata;
  }

  createSession(input, idempotencyKey) {
    return this.withIdempotency("create-session", idempotencyKey, () => {
      const repo = this.db.prepare("SELECT * FROM repos WHERE runner_id=? AND id=? AND available=1").get(input.runnerId, input.repoId);
      if (!repo) throw Object.assign(new Error("Repository is not available"), { statusCode: 409 });
      const runtime = String(input.runtime ?? "codex").trim();
      const advertisedRuntime = runtimeCatalogFromRow(repo).find((candidate) => candidate.id === runtime);
      if (
        !advertisedRuntime
        || !advertisedRuntime.models.includes(input.model)
        || !advertisedRuntime.reasoningEfforts.includes(input.reasoningEffort)
      ) {
        throw Object.assign(
          new Error("Runtime, model or reasoning effort is not advertised by the runner"),
          { statusCode: 400 },
        );
      }
      if (!String(input.message ?? "").trim() && !(input.attachmentIds?.length)) {
        throw Object.assign(new Error("First message or image is required"), { statusCode: 400 });
      }
      if (input.title != null && typeof input.title !== "string") {
        throw Object.assign(new Error("Session title must be a string"), { statusCode: 400 });
      }
      const title = input.title?.trim() || repo.name;
      if (title.length > 80) {
        throw Object.assign(new Error("Session title must not exceed 80 characters"), { statusCode: 400 });
      }
      this.validateAttachments(input.attachmentIds);
      const sessionId = id("work");
      const now = new Date().toISOString();
      this.db.prepare(`
          INSERT INTO sessions(
            id, runner_id, repo_id, repo_name, title, runtime, model, reasoning_effort, status, created_at, updated_at
          ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)
      `).run(
        sessionId,
        input.runnerId,
        input.repoId,
        repo.name,
        title,
        runtime,
        input.model,
        input.reasoningEffort,
        now,
        now,
      );
      const attachments = this.bindAttachments(sessionId, input.attachmentIds);
      const firstMessage = this.appendEvent(sessionId, "USER_MESSAGE", {
        text: input.message ?? "",
        attachments,
        clientMessageId: input.clientMessageId ?? null,
      }, now);
      this.createCommand(input.runnerId, sessionId, "START", {
        message: input.message ?? "",
        attachments,
        repoId: input.repoId,
        runtime,
        model: input.model,
        reasoningEffort: input.reasoningEffort,
        sessionToken: this.createSessionToken(sessionId),
        inboxCursor: firstMessage.seq,
      }, now);
      return this.getSession(sessionId);
    });
  }

  createCommand(runnerId, sessionId, kind, payload, now = new Date().toISOString()) {
    const commandId = id("cmd");
    this.db.prepare(`
      INSERT INTO commands(id, runner_id, session_id, kind, payload_json, state, created_at, updated_at)
      VALUES (?, ?, ?, ?, ?, 'PENDING', ?, ?)
    `).run(commandId, runnerId, sessionId, kind, json(payload), now, now);
    return commandId;
  }

  appendEvent(sessionId, type, payload, now = new Date().toISOString()) {
    const session = this.db.prepare("SELECT last_seq FROM sessions WHERE id=?").get(sessionId);
    if (!session) throw Object.assign(new Error("Session not found"), { statusCode: 404 });
    const seq = Number(session.last_seq) + 1;
    const eventId = id("evt");
    this.db.prepare("INSERT INTO events(session_id, seq, id, type, payload_json, created_at) VALUES (?, ?, ?, ?, ?, ?)")
      .run(sessionId, seq, eventId, type, json(payload), now);
    this.db.prepare("UPDATE sessions SET last_seq=?, updated_at=? WHERE id=?").run(seq, now, sessionId);
    return { sessionId, seq, id: eventId, type, payload, createdAt: now };
  }

  getSession(sessionId) {
    const row = this.db.prepare("SELECT * FROM sessions WHERE id=?").get(sessionId);
    if (!row) return null;
    return {
      id: row.id,
      runnerId: row.runner_id,
      repoId: row.repo_id,
      repoName: row.repo_name,
      title: row.title || row.repo_name,
      runtime: row.runtime ?? "codex",
      model: row.model,
      reasoningEffort: row.reasoning_effort,
      sandboxMode: "danger-full-access",
      approvalPolicy: "never",
      status: row.status,
      runtimeSessionId: row.runtime_session_id ?? row.codex_session_id,
      codexSessionId: (row.runtime ?? "codex") === "codex"
        ? row.runtime_session_id ?? row.codex_session_id
        : null,
      lastSeq: row.last_seq,
      archivedAt: row.archived_at ?? null,
      createdAt: row.created_at,
      updatedAt: row.updated_at,
    };
  }

  listSessions({ archived = false } = {}) {
    const sql = archived
      ? "SELECT id FROM sessions WHERE archived_at IS NOT NULL ORDER BY archived_at DESC"
      : "SELECT id FROM sessions WHERE archived_at IS NULL ORDER BY updated_at DESC";
    return this.db.prepare(sql).all().map((row) => this.getSession(row.id));
  }

  setSessionArchived(sessionId, archived) {
    const session = this.getSession(sessionId);
    if (!session) throw Object.assign(new Error("Session not found"), { statusCode: 404 });
    if (archived && SESSION_ACTIVE.has(session.status)) {
      throw Object.assign(new Error("Active session cannot be archived"), { statusCode: 409 });
    }
    const now = new Date().toISOString();
    this.db.prepare("UPDATE sessions SET archived_at=?, updated_at=? WHERE id=?")
      .run(archived ? now : null, now, sessionId);
    return this.getSession(sessionId);
  }

  getEvents(sessionId, afterSeq = 0) {
    if (!this.getSession(sessionId)) throw Object.assign(new Error("Session not found"), { statusCode: 404 });
    return this.db.prepare("SELECT * FROM events WHERE session_id=? AND seq>? ORDER BY seq").all(sessionId, Number(afterSeq)).map((row) => this.eventFromRow(row));
  }

  eventFromRow(row) {
    return {
      sessionId: row.session_id,
      seq: row.seq,
      id: row.id,
      type: row.type,
      payload: parseJson(row.payload_json, {}),
      createdAt: row.created_at,
    };
  }

  postUserMessage(sessionId, input, idempotencyKey) {
    return this.withIdempotency(`message:${sessionId}`, idempotencyKey, () => {
      const session = this.getSession(sessionId);
      if (!session) throw Object.assign(new Error("Session not found"), { statusCode: 404 });
      if (session.archivedAt) throw Object.assign(new Error("Restore the archived session before sending"), { statusCode: 409 });
      if (SESSION_TERMINAL.has(session.status)) throw Object.assign(new Error("Session is completed"), { statusCode: 409 });
      if (!String(input.text ?? "").trim() && !(input.attachmentIds?.length)) {
        throw Object.assign(new Error("Message or image is required"), { statusCode: 400 });
      }
      const attachments = this.bindAttachments(sessionId, input.attachmentIds);
      const event = this.appendEvent(sessionId, "USER_MESSAGE", {
        text: input.text ?? "",
        attachments,
        clientMessageId: input.clientMessageId ?? null,
      });
      const restartFailedStart = session.status === "FAILED" && !session.runtimeSessionId;
      const commandInput = restartFailedStart
        ? this.buildFailedStartInput(sessionId)
        : { message: input.text ?? "", attachments };
      this.createCommand(session.runnerId, sessionId, restartFailedStart ? "START" : "RESUME", {
        ...commandInput,
        repoId: session.repoId,
        runtime: session.runtime,
        model: session.model,
        reasoningEffort: session.reasoningEffort,
        inboxCursor: event.seq,
        sessionToken: this.createSessionToken(sessionId),
      });
      return event;
    });
  }

  buildFailedStartInput(sessionId) {
    const messages = this.db.prepare(`
      SELECT payload_json FROM events
      WHERE session_id=? AND type='USER_MESSAGE'
      ORDER BY seq
    `).all(sessionId).map((row) => parseJson(row.payload_json, {}));
    const uniqueTexts = [];
    const seenTexts = new Set();
    const attachmentMap = new Map();
    for (const message of messages) {
      const text = String(message.text ?? "").trim();
      if (text && !seenTexts.has(text)) {
        seenTexts.add(text);
        uniqueTexts.push(text);
      }
      for (const attachment of message.attachments ?? []) {
        if (attachment?.id) attachmentMap.set(attachment.id, attachment);
      }
    }
    return {
      message: uniqueTexts.join("\n\n补充消息：\n"),
      attachments: [...attachmentMap.values()],
    };
  }

  listCommands(runnerId, instanceId, after = "") {
    if (!this.isRunnerInstance(runnerId, instanceId)) {
      throw Object.assign(new Error("Runner instance is stale"), { statusCode: 409 });
    }
    const now = new Date().toISOString();
    this.db.prepare("UPDATE commands SET state='PENDING', claimed_by=NULL, lease_until=NULL, updated_at=? WHERE state='CLAIMED' AND lease_until<?")
      .run(now, now);
    const query = after
      ? "SELECT * FROM commands WHERE runner_id=? AND state='PENDING' AND created_at>? ORDER BY created_at LIMIT 20"
      : "SELECT * FROM commands WHERE runner_id=? AND state='PENDING' ORDER BY created_at LIMIT 20";
    return this.db.prepare(query).all(...(after ? [runnerId, after] : [runnerId])).map((row) => ({
      id: row.id,
      runnerId: row.runner_id,
      sessionId: row.session_id,
      kind: row.kind,
      payload: parseJson(row.payload_json, {}),
      createdAt: row.created_at,
    }));
  }

  commandRunnerId(commandId) {
    return this.db.prepare("SELECT runner_id FROM commands WHERE id=?").get(commandId)?.runner_id ?? null;
  }

  sessionRunnerId(sessionId) {
    return this.db.prepare("SELECT runner_id FROM sessions WHERE id=?").get(sessionId)?.runner_id ?? null;
  }

  ackCommand(commandId, input, runnerId, instanceId) {
    const allowed = new Set(["CLAIMED", "COMPLETED", "FAILED"]);
    if (!allowed.has(input.state)) throw Object.assign(new Error("Invalid command state"), { statusCode: 400 });
    const command = this.db.prepare("SELECT * FROM commands WHERE id=? AND runner_id=?").get(commandId, runnerId);
    if (!command) throw Object.assign(new Error("Command not found"), { statusCode: 404 });
    if (command.state === input.state) return { accepted: true };
    if (input.sessionState?.sessionId && input.sessionState.sessionId !== command.session_id) {
      throw Object.assign(new Error("Command session mismatch"), { statusCode: 400 });
    }
    const now = new Date().toISOString();
    this.db.exec("BEGIN IMMEDIATE");
    try {
      let result;
      if (input.state === "CLAIMED") {
        const leaseUntil = new Date(Date.now() + 45_000).toISOString();
        result = this.db.prepare(`
          UPDATE commands SET state='CLAIMED', claimed_by=?, lease_until=?, updated_at=?
          WHERE id=? AND runner_id=? AND state='PENDING'
        `).run(instanceId, leaseUntil, now, commandId, runnerId);
      } else {
        result = this.db.prepare(`
          UPDATE commands SET state=?, claimed_by=NULL, lease_until=NULL, updated_at=?
          WHERE id=? AND runner_id=? AND (claimed_by=? OR (claimed_by IS NULL AND state='PENDING'))
        `).run(input.state, now, commandId, runnerId, instanceId);
      }
      if (!result.changes) throw Object.assign(new Error("Command state conflict"), { statusCode: 409 });
      if (input.sessionState) this.updateSessionState(command.session_id, input.sessionState);
      this.db.exec("COMMIT");
    } catch (error) {
      this.db.exec("ROLLBACK");
      throw error;
    }
    return { accepted: true };
  }

  updateSessionState(sessionId, input) {
    const session = this.getSession(sessionId);
    if (!session) throw Object.assign(new Error("Session not found"), { statusCode: 404 });
    const allowed = new Set(["QUEUED", "RUNNING", "WAITING_FOR_USER", "IDLE", "COMPLETED", "FAILED"]);
    if (!allowed.has(input.status)) throw Object.assign(new Error("Invalid session state"), { statusCode: 400 });
    if (input.runtime && input.runtime !== session.runtime) {
      throw Object.assign(new Error("Session runtime mismatch"), { statusCode: 400 });
    }
    const runtimeSessionId = input.runtimeSessionId
      ?? (session.runtime === "codex" ? input.codexSessionId : null)
      ?? null;
    this.db.prepare(`
      UPDATE sessions
      SET status=?,
          runtime_session_id=COALESCE(?, runtime_session_id),
          codex_session_id=CASE
            WHEN runtime='codex' THEN COALESCE(?, codex_session_id)
            ELSE codex_session_id
          END,
          updated_at=?
      WHERE id=?
    `).run(
      input.status,
      runtimeSessionId,
      runtimeSessionId,
      new Date().toISOString(),
      sessionId,
    );
    const payload = { status: input.status, detail: input.detail ?? null };
    const last = this.db.prepare("SELECT * FROM events WHERE session_id=? AND type='RUN_STATE' ORDER BY seq DESC LIMIT 1")
      .get(sessionId);
    if (last && json(parseJson(last.payload_json, {})) === json(payload)) return this.eventFromRow(last);
    return this.appendEvent(sessionId, "RUN_STATE", payload);
  }

  appendRunnerEvent(sessionId, input, runnerId) {
    if (this.sessionRunnerId(sessionId) !== runnerId) {
      throw Object.assign(new Error("Session not found"), { statusCode: 404 });
    }
    if (input.type !== "ASSISTANT_MESSAGE") {
      throw Object.assign(new Error("Unsupported runner event type"), { statusCode: 400 });
    }
    const text = String(input.payload?.text ?? "").trim();
    if (!text) throw Object.assign(new Error("Assistant message text is required"), { statusCode: 400 });
    if (text.length > 20_000) throw Object.assign(new Error("Assistant message exceeds 20000 characters"), { statusCode: 413 });
    return this.withIdempotency(`runner-event:${sessionId}`, input.clientEventId, () => {
      const last = this.db.prepare(`
        SELECT * FROM events
        WHERE session_id=? AND type IN ('REPORT', 'ASSISTANT_MESSAGE')
        ORDER BY seq DESC LIMIT 1
      `).get(sessionId);
      if (last && parseJson(last.payload_json, {}).text === text) return this.eventFromRow(last);
      return this.appendEvent(sessionId, "ASSISTANT_MESSAGE", { text });
    });
  }

  report(sessionId, input) {
    if (!String(input.text ?? "").trim()) throw Object.assign(new Error("text is required"), { statusCode: 400 });
    if (input.text.length > 20_000) throw Object.assign(new Error("text exceeds 20000 characters"), { statusCode: 413 });
    const accepted = this.withIdempotency(`report:${sessionId}`, input.clientCallId, () => {
      const event = this.appendEvent(sessionId, "REPORT", { text: input.text });
      return {
        accepted: true,
        messageId: event.id,
      };
    });
    return { ...accepted, ...this.readInbox(sessionId, input.inboxAfter ?? 0) };
  }

  readInbox(sessionId, afterSeq) {
    const events = this.db.prepare(`
      SELECT * FROM events WHERE session_id=? AND seq>? AND type='USER_MESSAGE' ORDER BY seq
    `).all(sessionId, Number(afterSeq));
    const inbox = events.map((row) => ({
      seq: row.seq,
      id: row.id,
      text: parseJson(row.payload_json, {}).text,
      attachments: parseJson(row.payload_json, {}).attachments ?? [],
      createdAt: row.created_at,
    }));
    return { inbox, nextInboxCursor: inbox.at(-1)?.seq ?? Number(afterSeq) };
  }

  createAsk(sessionId, input, timeoutMs = null) {
    validateQuestions(input.questions);
    const existing = this.db.prepare("SELECT * FROM asks WHERE session_id=? AND client_call_id=?").get(sessionId, input.clientCallId);
    if (existing) return this.toAsk(existing);
    const askId = id("ask");
    const now = new Date().toISOString();
    const deadlineAt = Number.isFinite(timeoutMs) ? new Date(Date.now() + timeoutMs).toISOString() : null;
    this.db.exec("BEGIN IMMEDIATE");
    try {
      const concurrent = this.db.prepare("SELECT * FROM asks WHERE session_id=? AND client_call_id=?").get(sessionId, input.clientCallId);
      if (concurrent) {
        this.db.exec("COMMIT");
        return this.toAsk(concurrent);
      }
      this.db.prepare(`
        INSERT INTO asks(id, session_id, client_call_id, questions_json, status, created_at)
        VALUES (?, ?, ?, ?, 'PENDING', ?)
      `).run(askId, sessionId, input.clientCallId, json(input.questions), now);
      this.appendEvent(sessionId, "ASK", { askId, questions: input.questions, deadlineAt }, now);
      this.db.prepare("UPDATE sessions SET status='WAITING_FOR_USER', updated_at=? WHERE id=?").run(now, sessionId);
      this.db.exec("COMMIT");
    } catch (error) {
      this.db.exec("ROLLBACK");
      throw error;
    }
    return this.getAsk(askId);
  }

  getAsk(askId) {
    const row = this.db.prepare("SELECT * FROM asks WHERE id=?").get(askId);
    return row ? this.toAsk(row) : null;
  }

  timeoutAsk(askId, nextSessionStatus = "IDLE") {
    const ask = this.getAsk(askId);
    if (!ask || ask.status !== "PENDING") return ask;
    const answers = defaultAnswers(ask.questions);
    const now = new Date().toISOString();
    this.db.exec("BEGIN IMMEDIATE");
    try {
      this.db.prepare("UPDATE asks SET status='ANSWERED', answers_json=?, answered_at=? WHERE id=? AND status='PENDING'")
        .run(json(answers), now, askId);
      this.appendEvent(ask.sessionId, "ASK_ANSWERED", { askId, answers, source: "timeout_default" }, now);
      this.db.prepare("UPDATE sessions SET status=?, updated_at=? WHERE id=? AND status='WAITING_FOR_USER'")
        .run(nextSessionStatus, now, ask.sessionId);
      this.db.exec("COMMIT");
    } catch (error) {
      this.db.exec("ROLLBACK");
      throw error;
    }
    return this.getAsk(askId);
  }

  toAsk(row) {
    return {
      id: row.id,
      sessionId: row.session_id,
      clientCallId: row.client_call_id,
      questions: parseJson(row.questions_json, []),
      status: row.status,
      answers: parseJson(row.answers_json),
      createdAt: row.created_at,
      answeredAt: row.answered_at,
    };
  }

  answerAsk(sessionId, askId, input, idempotencyKey) {
    return this.withIdempotency(`answer:${askId}`, idempotencyKey, () => {
      const ask = this.getAsk(askId);
      if (!ask || ask.sessionId !== sessionId) throw Object.assign(new Error("Question set not found"), { statusCode: 404 });
      if (ask.status === "ANSWERED") return ask;
      validateAnswers(ask.questions, input.answers);
      const now = new Date().toISOString();
      this.db.prepare("UPDATE asks SET status='ANSWERED', answers_json=?, answered_at=? WHERE id=?")
        .run(json(input.answers), now, askId);
      this.appendEvent(sessionId, "ASK_ANSWERED", { askId, answers: input.answers, source: "user" }, now);
      this.db.prepare("UPDATE sessions SET status='RUNNING', updated_at=? WHERE id=? AND status='WAITING_FOR_USER'").run(now, sessionId);
      return this.getAsk(askId);
    });
  }

  reportHtml(sessionId, input, sanitizedHtml) {
    if (!String(input.title ?? "").trim() || input.title.length > 120) {
      throw Object.assign(new Error("title must contain 1-120 characters"), { statusCode: 400 });
    }
    if (!String(input.html ?? "").trim()) throw Object.assign(new Error("html is required"), { statusCode: 400 });
    if (Buffer.byteLength(input.html, "utf8") > 1024 * 1024) throw Object.assign(new Error("html exceeds 1 MiB"), { statusCode: 413 });
    return this.withIdempotency(`report-html:${sessionId}`, input.clientCallId, () => {
      const reportId = id("report");
      const now = new Date().toISOString();
      this.db.prepare("INSERT INTO reports(id, session_id, title, html, created_at) VALUES (?, ?, ?, ?, ?)")
        .run(reportId, sessionId, input.title, sanitizedHtml, now);
      const size = Buffer.byteLength(sanitizedHtml, "utf8");
      const event = this.appendEvent(sessionId, "HTML_REPORT", {
        reportId,
        title: input.title,
        size,
      }, now);
      return { accepted: true, reportId, messageId: event.id, sanitized: true, outputBytes: size };
    });
  }

  getReport(reportId) {
    const row = this.db.prepare("SELECT * FROM reports WHERE id=?").get(reportId);
    return row ? { id: row.id, sessionId: row.session_id, title: row.title, html: row.html, createdAt: row.created_at } : null;
  }

  completeSession(sessionId) {
    const session = this.getSession(sessionId);
    if (!session) throw Object.assign(new Error("Session not found"), { statusCode: 404 });
    this.db.exec("BEGIN IMMEDIATE");
    try {
      this.createCommand(session.runnerId, sessionId, "COMPLETE", {});
      this.revokeSessionTokens(sessionId);
      const result = this.updateSessionState(sessionId, { status: "COMPLETED" });
      this.db.exec("COMMIT");
      return result;
    } catch (error) {
      this.db.exec("ROLLBACK");
      throw error;
    }
  }

  revokeSessionTokens(sessionId) {
    if (!this.getSession(sessionId)) throw Object.assign(new Error("Session not found"), { statusCode: 404 });
    const now = new Date().toISOString();
    const result = this.db.prepare("UPDATE session_tokens SET revoked_at=? WHERE session_id=? AND revoked_at IS NULL")
      .run(now, sessionId);
    return { accepted: true, revoked: Number(result.changes) };
  }

  stopSession(sessionId) {
    const session = this.getSession(sessionId);
    if (!session) throw Object.assign(new Error("Session not found"), { statusCode: 404 });
    this.createCommand(session.runnerId, sessionId, "STOP", {});
    return { accepted: true };
  }
}

function validateQuestions(questions) {
  if (!Array.isArray(questions) || questions.length < 1 || questions.length > 4) {
    throw Object.assign(new Error("questions must contain 1-4 items"), { statusCode: 400 });
  }
  const ids = new Set();
  for (const question of questions) {
    if (!question.id || ids.has(question.id) || !question.header || !question.question) {
      throw Object.assign(new Error("Each question needs a unique id, header and question"), { statusCode: 400 });
    }
    ids.add(question.id);
    if (!Array.isArray(question.options) || question.options.length < 1 || question.options.length > 8) {
      throw Object.assign(new Error("Each question needs 1-8 options"), { statusCode: 400 });
    }
    const optionIds = new Set();
    for (const option of question.options) {
      if (!option.id || optionIds.has(option.id) || !option.label) {
        throw Object.assign(new Error("Each option needs a unique id and label"), { statusCode: 400 });
      }
      optionIds.add(option.id);
    }
    const recommended = question.recommendedOptionIds;
    if (!Array.isArray(recommended) || recommended.length < 1) {
      throw Object.assign(new Error("Each question needs recommendedOptionIds for the timeout fallback"), { statusCode: 400 });
    }
    if (!question.multiSelect && recommended.length !== 1) {
      throw Object.assign(new Error("Single-select question needs exactly one recommended option"), { statusCode: 400 });
    }
    if (recommended.some((optionId) => !optionIds.has(optionId))) {
      throw Object.assign(new Error("recommendedOptionIds must reference existing options"), { statusCode: 400 });
    }
  }
}

function defaultAnswers(questions) {
  return questions.map((question) => ({
    questionId: question.id,
    selectedOptionIds: [...question.recommendedOptionIds],
    otherText: null,
  }));
}

function validateAnswers(questions, answers) {
  if (!Array.isArray(answers)) throw Object.assign(new Error("answers must be an array"), { statusCode: 400 });
  const questionById = new Map(questions.map((question) => [question.id, question]));
  for (const answer of answers) {
    const question = questionById.get(answer.questionId);
    if (!question) throw Object.assign(new Error("Answer references an unknown question"), { statusCode: 400 });
    const selected = answer.selectedOptionIds ?? [];
    if (!question.multiSelect && selected.length > 1) {
      throw Object.assign(new Error("Single-select question received multiple answers"), { statusCode: 400 });
    }
    const allowed = new Set(question.options.map((option) => option.id));
    if (selected.some((optionId) => !allowed.has(optionId))) {
      throw Object.assign(new Error("Answer references an unknown option"), { statusCode: 400 });
    }
    if (!selected.length && !String(answer.otherText ?? "").trim()) {
      throw Object.assign(new Error("Each answer needs a selected option or other text"), { statusCode: 400 });
    }
  }
}
