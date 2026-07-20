import { createHash, createHmac, randomUUID, timingSafeEqual } from "node:crypto";
import { DatabaseSync } from "node:sqlite";

const SESSION_TERMINAL = new Set(["COMPLETED"]);

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
        models_json TEXT NOT NULL,
        efforts_json TEXT NOT NULL,
        available INTEGER NOT NULL DEFAULT 1,
        PRIMARY KEY (runner_id, id)
      );
      CREATE TABLE IF NOT EXISTS sessions (
        id TEXT PRIMARY KEY,
        runner_id TEXT NOT NULL,
        repo_id TEXT NOT NULL,
        repo_name TEXT NOT NULL,
        model TEXT NOT NULL,
        reasoning_effort TEXT NOT NULL,
        status TEXT NOT NULL,
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
    const payload = Buffer.from(json({
      sessionId,
      scope: "mcp",
      v: 1,
      jti: randomUUID(),
      iat: issuedAt,
      exp: issuedAt + 30 * 24 * 60 * 60,
    })).toString("base64url");
    const signature = createHmac("sha256", this.sessionSecret).update(payload).digest("base64url");
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
      return claims.scope === "mcp"
        && claims.sessionId === expectedSessionId
        && Number(claims.exp) > Math.floor(Date.now() / 1000)
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
        INSERT INTO repos(runner_id, id, name, models_json, efforts_json, available) VALUES (?, ?, ?, ?, ?, ?)
      `);
      for (const repo of input.repos ?? []) {
        insertRepo.run(input.id, repo.id, repo.name, json(repo.models ?? []), json(repo.reasoningEfforts ?? []), repo.available === false ? 0 : 1);
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
      models: parseJson(row.models_json, []),
      reasoningEfforts: parseJson(row.efforts_json, []),
      available: Boolean(row.available),
    }));
  }

  createSession(input, idempotencyKey) {
    return this.withIdempotency("create-session", idempotencyKey, () => {
      const repo = this.db.prepare("SELECT * FROM repos WHERE runner_id=? AND id=? AND available=1").get(input.runnerId, input.repoId);
      if (!repo) throw Object.assign(new Error("Repository is not available"), { statusCode: 409 });
      const models = parseJson(repo.models_json, []);
      const efforts = parseJson(repo.efforts_json, []);
      if (!models.includes(input.model) || !efforts.includes(input.reasoningEffort)) {
        throw Object.assign(new Error("Model or reasoning effort is not advertised by the runner"), { statusCode: 400 });
      }
      if (!String(input.message ?? "").trim()) throw Object.assign(new Error("First message is required"), { statusCode: 400 });
      const sessionId = id("work");
      const now = new Date().toISOString();
      this.db.prepare(`
          INSERT INTO sessions(id, runner_id, repo_id, repo_name, model, reasoning_effort, status, created_at, updated_at)
          VALUES (?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)
      `).run(sessionId, input.runnerId, input.repoId, repo.name, input.model, input.reasoningEffort, now, now);
      const firstMessage = this.appendEvent(sessionId, "USER_MESSAGE", { text: input.message, clientMessageId: input.clientMessageId ?? null }, now);
      this.createCommand(input.runnerId, sessionId, "START", {
        message: input.message,
        repoId: input.repoId,
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
      model: row.model,
      reasoningEffort: row.reasoning_effort,
      sandboxMode: "danger-full-access",
      approvalPolicy: "never",
      status: row.status,
      codexSessionId: row.codex_session_id,
      lastSeq: row.last_seq,
      createdAt: row.created_at,
      updatedAt: row.updated_at,
    };
  }

  listSessions() {
    return this.db.prepare("SELECT id FROM sessions ORDER BY updated_at DESC").all().map((row) => this.getSession(row.id));
  }

  getEvents(sessionId, afterSeq = 0) {
    if (!this.getSession(sessionId)) throw Object.assign(new Error("Session not found"), { statusCode: 404 });
    return this.db.prepare("SELECT * FROM events WHERE session_id=? AND seq>? ORDER BY seq").all(sessionId, Number(afterSeq)).map((row) => ({
      sessionId: row.session_id,
      seq: row.seq,
      id: row.id,
      type: row.type,
      payload: parseJson(row.payload_json, {}),
      createdAt: row.created_at,
    }));
  }

  postUserMessage(sessionId, input, idempotencyKey) {
    return this.withIdempotency(`message:${sessionId}`, idempotencyKey, () => {
      const session = this.getSession(sessionId);
      if (!session) throw Object.assign(new Error("Session not found"), { statusCode: 404 });
      if (SESSION_TERMINAL.has(session.status)) throw Object.assign(new Error("Session is completed"), { statusCode: 409 });
      if (!String(input.text ?? "").trim()) throw Object.assign(new Error("Message is required"), { statusCode: 400 });
      const event = this.appendEvent(sessionId, "USER_MESSAGE", { text: input.text, clientMessageId: input.clientMessageId ?? null });
      this.createCommand(session.runnerId, sessionId, "RESUME", { message: input.text, inboxCursor: event.seq });
      return event;
    });
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
    const now = new Date().toISOString();
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
    if (!result.changes) throw Object.assign(new Error("Command not found"), { statusCode: 404 });
    return { accepted: true };
  }

  updateSessionState(sessionId, input) {
    const session = this.getSession(sessionId);
    if (!session) throw Object.assign(new Error("Session not found"), { statusCode: 404 });
    const allowed = new Set(["QUEUED", "RUNNING", "WAITING_FOR_USER", "IDLE", "COMPLETED", "FAILED"]);
    if (!allowed.has(input.status)) throw Object.assign(new Error("Invalid session state"), { statusCode: 400 });
    this.db.prepare("UPDATE sessions SET status=?, codex_session_id=COALESCE(?, codex_session_id), updated_at=? WHERE id=?")
      .run(input.status, input.codexSessionId ?? null, new Date().toISOString(), sessionId);
    return this.appendEvent(sessionId, "RUN_STATE", { status: input.status, detail: input.detail ?? null });
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
      createdAt: row.created_at,
    }));
    return { inbox, nextInboxCursor: inbox.at(-1)?.seq ?? Number(afterSeq) };
  }

  createAsk(sessionId, input) {
    validateQuestions(input.questions);
    const existing = this.db.prepare("SELECT * FROM asks WHERE session_id=? AND client_call_id=?").get(sessionId, input.clientCallId);
    if (existing) return this.toAsk(existing);
    const askId = id("ask");
    const now = new Date().toISOString();
    this.db.prepare(`
      INSERT INTO asks(id, session_id, client_call_id, questions_json, status, created_at)
      VALUES (?, ?, ?, ?, 'PENDING', ?)
    `).run(askId, sessionId, input.clientCallId, json(input.questions), now);
    this.appendEvent(sessionId, "ASK", { askId, questions: input.questions });
    this.db.prepare("UPDATE sessions SET status='WAITING_FOR_USER', updated_at=? WHERE id=?").run(now, sessionId);
    return this.getAsk(askId);
  }

  getAsk(askId) {
    const row = this.db.prepare("SELECT * FROM asks WHERE id=?").get(askId);
    return row ? this.toAsk(row) : null;
  }

  timeoutAsk(askId) {
    const ask = this.getAsk(askId);
    if (!ask || ask.status !== "PENDING") return ask;
    const now = new Date().toISOString();
    this.db.exec("BEGIN IMMEDIATE");
    try {
      this.db.prepare("UPDATE asks SET status='TIMED_OUT' WHERE id=? AND status='PENDING'").run(askId);
      this.appendEvent(ask.sessionId, "ASK_TIMED_OUT", { askId }, now);
      this.db.prepare("UPDATE sessions SET status='IDLE', updated_at=? WHERE id=? AND status='WAITING_FOR_USER'")
        .run(now, ask.sessionId);
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

  answerAsk(sessionId, askId, input, idempotencyKey, shouldResume = true) {
    return this.withIdempotency(`answer:${askId}`, idempotencyKey, () => {
      const ask = this.getAsk(askId);
      if (!ask || ask.sessionId !== sessionId) throw Object.assign(new Error("Question set not found"), { statusCode: 404 });
      if (ask.status === "ANSWERED") return ask;
      validateAnswers(ask.questions, input.answers);
      const now = new Date().toISOString();
      this.db.prepare("UPDATE asks SET status='ANSWERED', answers_json=?, answered_at=? WHERE id=?")
        .run(json(input.answers), now, askId);
      this.appendEvent(sessionId, "ASK_ANSWERED", { askId, answers: input.answers }, now);
      const session = this.getSession(sessionId);
      if (shouldResume) {
        this.createCommand(session.runnerId, sessionId, "RESUME", {
          message: formatLateAnswer(ask.questions, input.answers),
          askId,
          inboxCursor: this.getSession(sessionId).lastSeq,
        }, now);
      } else {
        this.db.prepare("UPDATE sessions SET status='RUNNING', updated_at=? WHERE id=?").run(now, sessionId);
      }
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
      const event = this.appendEvent(sessionId, "HTML_REPORT", {
        reportId,
        title: input.title,
        size: Buffer.byteLength(sanitizedHtml, "utf8"),
      }, now);
      return { accepted: true, reportId, messageId: event.id, sanitized: true };
    });
  }

  getReport(reportId) {
    const row = this.db.prepare("SELECT * FROM reports WHERE id=?").get(reportId);
    return row ? { id: row.id, sessionId: row.session_id, title: row.title, html: row.html, createdAt: row.created_at } : null;
  }

  completeSession(sessionId) {
    const session = this.getSession(sessionId);
    if (!session) throw Object.assign(new Error("Session not found"), { statusCode: 404 });
    this.createCommand(session.runnerId, sessionId, "COMPLETE", {});
    return this.updateSessionState(sessionId, { status: "COMPLETED" });
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
  }
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

function formatLateAnswer(questions, answers) {
  const questionById = new Map(questions.map((question) => [question.id, question]));
  const lines = ["用户已回答之前的问题："];
  for (const answer of answers) {
    const question = questionById.get(answer.questionId);
    const optionById = new Map(question.options.map((option) => [option.id, option.label]));
    const values = (answer.selectedOptionIds ?? []).map((optionId) => optionById.get(optionId));
    if (answer.otherText) values.push(`其他：${answer.otherText}`);
    lines.push(`- ${question.question} ${values.join("、")}`);
  }
  return lines.join("\n");
}
