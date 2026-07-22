import assert from "node:assert/strict";
import { once } from "node:events";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { createWorkServer } from "./server.js";
import { WorkStore } from "./store.js";

const USER_TOKEN = "user-test-token";
const RUNNER_TOKEN = "runner-test-token";
const RUNNER_INSTANCE = "runner-instance-1";
const PROTOCOL = { "x-zhixing-work-protocol": "1" };

async function fixture(t, askTimeoutMs = 150, quotaProxy = null) {
  const store = new WorkStore({
    userToken: USER_TOKEN,
    runnerTokens: { "runner-1": RUNNER_TOKEN, "runner-2": "runner-2-token" },
    sessionSecret: "test-session-secret-at-least-32-bytes",
  });
  const server = createWorkServer({ store, askTimeoutMs, quotaProxy });
  let port;
  do {
    server.listen(0, "127.0.0.1");
    await once(server, "listening");
    ({ port } = server.address());
    if (FETCH_BLOCKED_PORTS.has(port)) {
      server.close();
      await once(server, "close");
    }
  } while (FETCH_BLOCKED_PORTS.has(port));
  t.after(async () => {
    server.close();
    await once(server, "close");
    store.close();
  });
  return { store, baseUrl: `http://127.0.0.1:${port}` };
}

// Fetch follows the browser's unsafe-port list even for loopback URLs. An
// ephemeral bind can occasionally land on one of these ports and make a
// completely healthy test fail with `bad port`.
const FETCH_BLOCKED_PORTS = new Set([
  1, 7, 9, 11, 13, 15, 17, 19, 20, 21, 22, 23, 25, 37, 42, 43, 53, 69, 77,
  79, 87, 95, 101, 102, 103, 104, 109, 110, 111, 113, 115, 117, 119, 123,
  135, 137, 139, 143, 161, 179, 389, 427, 465, 512, 513, 514, 515, 526, 530,
  531, 532, 540, 548, 554, 556, 563, 587, 601, 636, 989, 990, 993, 995, 1719,
  1720, 1723, 2049, 3659, 4045, 5060, 5061, 6000, 6566, 6665, 6666, 6667,
  6668, 6669, 6697, 10080,
]);

async function request(baseUrl, path, { token = USER_TOKEN, method = "GET", body, idempotencyKey } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      ...PROTOCOL,
      authorization: `Bearer ${token}`,
      ...(body ? { "content-type": "application/json" } : {}),
      ...(idempotencyKey ? { "idempotency-key": idempotencyKey } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const contentType = response.headers.get("content-type") ?? "";
  const payload = contentType.includes("json") ? await response.json() : await response.text();
  return { response, payload };
}

async function uploadImage(baseUrl, data, { fileName = "screen.png", mimeType = "image/png" } = {}) {
  const response = await fetch(`${baseUrl}/v1/work/attachments`, {
    method: "POST",
    headers: {
      ...PROTOCOL,
      authorization: `Bearer ${USER_TOKEN}`,
      "content-type": mimeType,
      "x-file-name": encodeURIComponent(fileName),
    },
    body: data,
  });
  return { response, payload: await response.json() };
}

async function registerAndCreate(baseUrl) {
  const registration = await request(baseUrl, "/v1/runner/register", {
    token: RUNNER_TOKEN,
    method: "POST",
    body: {
      id: "runner-1",
      instanceId: RUNNER_INSTANCE,
      name: "Minecraft",
      version: "test",
      repos: [{
        id: "zhixing",
        name: "zhixing",
        models: ["gpt-5.6-sol"],
        reasoningEfforts: ["high", "xhigh"],
      }],
    },
  });
  assert.equal(registration.response.status, 200);
  const created = await request(baseUrl, "/v1/work/sessions", {
    method: "POST",
    idempotencyKey: "create-1",
    body: {
      runnerId: "runner-1",
      repoId: "zhixing",
      title: "实现 Work 电话线闭环",
      model: "gpt-5.6-sol",
      reasoningEffort: "high",
      message: "实现 phone-line 闭环",
      clientMessageId: "phone-1",
    },
  });
  assert.equal(created.response.status, 201);
  const commands = await request(baseUrl, runnerCommandsPath(), { token: RUNNER_TOKEN });
  const start = commands.payload.commands[0];
  return { session: created.payload, sessionToken: start.payload.sessionToken };
}

function runnerCommandsPath(instanceId = RUNNER_INSTANCE) {
  return `/v1/runner/commands?runnerId=runner-1&instanceId=${encodeURIComponent(instanceId)}`;
}

test("life quota endpoint uses Work user authentication", async (t) => {
  const expected = {
    schema_version: "quota-monitor/v1",
    generated_at: "2026-07-22T06:40:00Z",
    stale_after_seconds: 1200,
    items: [],
    proxy_stale: false,
    proxy_error: null,
  };
  const { baseUrl } = await fixture(t, 150, { getQuotas: async () => expected });

  const unauthorized = await request(baseUrl, "/v1/life/quotas", { token: "wrong-token" });
  assert.equal(unauthorized.response.status, 401);

  const result = await request(baseUrl, "/v1/life/quotas");
  assert.equal(result.response.status, 200);
  assert.deepEqual(result.payload, expected);
});

test("full phone-line API flow is durable, ordered and idempotent", async (t) => {
  const { baseUrl } = await fixture(t, 500);
  const { session, sessionToken } = await registerAndCreate(baseUrl);
  assert.equal(session.title, "实现 Work 电话线闭环");

  const duplicate = await request(baseUrl, "/v1/work/sessions", {
    method: "POST",
    idempotencyKey: "create-1",
    body: { runnerId: "ignored" },
  });
  assert.equal(duplicate.payload.id, session.id);

  const invalidTitle = await request(baseUrl, "/v1/work/sessions", {
    method: "POST",
    idempotencyKey: "create-invalid-title",
    body: {
      runnerId: "runner-1",
      repoId: "zhixing",
      title: "x".repeat(81),
      model: "gpt-5.6-sol",
      reasoningEffort: "high",
      message: "标题过长",
    },
  });
  assert.equal(invalidTitle.response.status, 400);

  const report = await request(baseUrl, `/v1/mcp/sessions/${session.id}/report`, {
    token: sessionToken,
    method: "POST",
    body: { text: "接口已完成", clientCallId: "report-1", inboxAfter: 1 },
  });
  assert.equal(report.response.status, 200);
  assert.equal(report.payload.accepted, true);
  assert.deepEqual(report.payload.inbox, []);

  const supplemental = await request(baseUrl, `/v1/work/sessions/${session.id}/messages`, {
    method: "POST",
    idempotencyKey: "supplement-1",
    body: { text: "顺便覆盖升级测试" },
  });
  const reportRetry = await request(baseUrl, `/v1/mcp/sessions/${session.id}/report`, {
    token: sessionToken,
    method: "POST",
    body: { text: "不会重复显示", clientCallId: "report-1", inboxAfter: 1 },
  });
  assert.equal(reportRetry.payload.messageId, report.payload.messageId);
  assert.equal(reportRetry.payload.inbox[0].seq, supplemental.payload.seq);

  const askPromise = request(baseUrl, `/v1/mcp/sessions/${session.id}/ask`, {
    token: sessionToken,
    method: "POST",
    body: {
      clientCallId: "ask-1",
      questions: [{
        id: "scope",
        header: "范围",
        question: "要覆盖升级测试吗？",
        multiSelect: false,
        options: [{ id: "yes", label: "覆盖" }, { id: "no", label: "不覆盖" }],
      }],
    },
  });

  await new Promise((resolve) => setTimeout(resolve, 40));
  const eventsBeforeAnswer = await request(baseUrl, `/v1/work/sessions/${session.id}/events?afterSeq=0`);
  const askEvent = eventsBeforeAnswer.payload.events.find((event) => event.type === "ASK");
  assert.ok(askEvent);

  const answer = await request(baseUrl, `/v1/work/sessions/${session.id}/asks/${askEvent.payload.askId}/answer`, {
    method: "POST",
    idempotencyKey: "answer-1",
    body: { answers: [{ questionId: "scope", selectedOptionIds: ["yes"], otherText: null }] },
  });
  assert.equal(answer.response.status, 200);
  const askResult = await askPromise;
  assert.equal(askResult.payload.status, "answered");
  assert.deepEqual(askResult.payload.answers[0].selectedOptionIds, ["yes"]);

  const html = await request(baseUrl, `/v1/mcp/sessions/${session.id}/report-html`, {
    token: sessionToken,
    method: "POST",
    body: {
      title: "验收报告",
      html: "<h2>通过</h2><script>alert('xss')</script><p>完成</p>",
      clientCallId: "html-1",
    },
  });
  assert.equal(html.response.status, 200);
  const reportPage = await request(baseUrl, `/v1/work/reports/${html.payload.reportId}`);
  assert.equal(reportPage.response.status, 200);
  assert.match(reportPage.payload, /<h2>通过<\/h2>/);
  assert.doesNotMatch(reportPage.payload, /<script>/);
  assert.match(reportPage.response.headers.get("content-security-policy"), /default-src 'none'/);

  const events = await request(baseUrl, `/v1/work/sessions/${session.id}/events?afterSeq=0`);
  assert.deepEqual(events.payload.events.map((event) => event.seq), [1, 2, 3, 4, 5, 6]);
  assert.deepEqual(events.payload.events.map((event) => event.type), [
    "USER_MESSAGE", "REPORT", "USER_MESSAGE", "ASK", "ASK_ANSWERED", "HTML_REPORT",
  ]);
});

test("repository catalog preserves groups and rejects unavailable directories", async (t) => {
  const { baseUrl } = await fixture(t);
  const registration = await request(baseUrl, "/v1/runner/register", {
    token: RUNNER_TOKEN,
    method: "POST",
    body: {
      id: "runner-1",
      instanceId: RUNNER_INSTANCE,
      name: "Minecraft",
      version: "test",
      repos: [
        {
          id: "available",
          name: "company-api",
          group: "Workspace",
          models: ["gpt-5.6-sol"],
          reasoningEfforts: ["high"],
          available: true,
        },
        {
          id: "deleted",
          name: "deleted-project",
          group: "Documents",
          models: ["gpt-5.6-sol"],
          reasoningEfforts: ["high"],
          available: false,
        },
      ],
    },
  });
  assert.equal(registration.response.status, 200);

  const catalog = await request(baseUrl, "/v1/work/repos?runnerId=runner-1");
  assert.deepEqual(catalog.payload.repos.map((repo) => [repo.name, repo.group, repo.available]), [
    ["company-api", "Workspace", true],
    ["deleted-project", "Documents", false],
  ]);

  const rejected = await request(baseUrl, "/v1/work/sessions", {
    method: "POST",
    idempotencyKey: "unavailable-repo",
    body: {
      runnerId: "runner-1",
      repoId: "deleted",
      model: "gpt-5.6-sol",
      reasoningEffort: "high",
      message: "do not run",
    },
  });
  assert.equal(rejected.response.status, 409);
});

test("runner assistant messages are allow-listed, idempotent and ordered", async (t) => {
  const { baseUrl } = await fixture(t);
  const { session } = await registerAndCreate(baseUrl);
  const input = {
    instanceId: RUNNER_INSTANCE,
    clientEventId: "cmd-1:item-1",
    type: "ASSISTANT_MESSAGE",
    payload: { text: "正在跑测试。" },
  };
  const created = await request(baseUrl, `/v1/runner/sessions/${session.id}/events`, {
    method: "POST",
    token: RUNNER_TOKEN,
    body: input,
  });
  assert.equal(created.response.status, 201);
  assert.equal(created.payload.type, "ASSISTANT_MESSAGE");
  const duplicate = await request(baseUrl, `/v1/runner/sessions/${session.id}/events`, {
    method: "POST",
    token: RUNNER_TOKEN,
    body: input,
  });
  assert.equal(duplicate.response.status, 201);
  assert.equal(duplicate.payload.id, created.payload.id);
  const rejected = await request(baseUrl, `/v1/runner/sessions/${session.id}/events`, {
    method: "POST",
    token: RUNNER_TOKEN,
    body: { ...input, clientEventId: "cmd-1:item-2", type: "REASONING" },
  });
  assert.equal(rejected.response.status, 400);
  const events = await request(baseUrl, `/v1/work/sessions/${session.id}/events?afterSeq=0`);
  assert.equal(events.payload.events.filter((event) => event.type === "ASSISTANT_MESSAGE").length, 1);
});

test("duplicate run states and explicit report echoes do not create duplicate UI events", async (t) => {
  const { baseUrl } = await fixture(t);
  const { session, sessionToken } = await registerAndCreate(baseUrl);
  const firstState = await request(baseUrl, `/v1/runner/sessions/${session.id}/state`, {
    method: "POST",
    token: RUNNER_TOKEN,
    body: { instanceId: RUNNER_INSTANCE, status: "RUNNING", detail: null },
  });
  const secondState = await request(baseUrl, `/v1/runner/sessions/${session.id}/state`, {
    method: "POST",
    token: RUNNER_TOKEN,
    body: { instanceId: RUNNER_INSTANCE, status: "RUNNING", detail: null },
  });
  assert.equal(secondState.payload.id, firstState.payload.id);
  const report = await request(baseUrl, `/v1/mcp/sessions/${session.id}/report`, {
    method: "POST",
    token: sessionToken,
    body: { text: "阶段完成", clientCallId: "report-echo", inboxAfter: 0 },
  });
  const echoed = await request(baseUrl, `/v1/runner/sessions/${session.id}/events`, {
    method: "POST",
    token: RUNNER_TOKEN,
    body: {
      instanceId: RUNNER_INSTANCE,
      clientEventId: "cmd-echo:item-1",
      type: "ASSISTANT_MESSAGE",
      payload: { text: "阶段完成" },
    },
  });
  assert.equal(echoed.payload.id, report.payload.messageId);
});

test("image attachments are durable, scoped to their runner and included in Codex commands", async (t) => {
  const { baseUrl } = await fixture(t);
  await request(baseUrl, "/v1/runner/register", {
    token: RUNNER_TOKEN,
    method: "POST",
    body: {
      id: "runner-1",
      instanceId: RUNNER_INSTANCE,
      name: "Minecraft",
      version: "test",
      repos: [{
        id: "zhixing",
        name: "zhixing",
        models: ["gpt-5.6-sol"],
        reasoningEfforts: ["high"],
      }],
    },
  });
  const bytes = Buffer.from("89504e470d0a1a0a", "hex");
  const uploaded = await uploadImage(baseUrl, bytes, { fileName: "错误 截图.png" });
  assert.equal(uploaded.response.status, 201);
  assert.equal(uploaded.payload.fileName, "错误 截图.png");

  const created = await request(baseUrl, "/v1/work/sessions", {
    method: "POST",
    idempotencyKey: "image-session",
    body: {
      runnerId: "runner-1",
      repoId: "zhixing",
      model: "gpt-5.6-sol",
      reasoningEffort: "high",
      message: "",
      attachmentIds: [uploaded.payload.id],
    },
  });
  assert.equal(created.response.status, 201);
  const events = await request(baseUrl, `/v1/work/sessions/${created.payload.id}/events?afterSeq=0`);
  assert.equal(events.payload.events[0].payload.attachments[0].sha256, uploaded.payload.sha256);

  const commands = await request(baseUrl, runnerCommandsPath(), { token: RUNNER_TOKEN });
  assert.equal(commands.payload.commands[0].payload.attachments[0].id, uploaded.payload.id);
  const download = await fetch(
    `${baseUrl}/v1/runner/attachments/${uploaded.payload.id}?runnerId=runner-1`,
    { headers: { ...PROTOCOL, authorization: `Bearer ${RUNNER_TOKEN}` } },
  );
  assert.equal(download.status, 200);
  assert.deepEqual(Buffer.from(await download.arrayBuffer()), bytes);

  const crossRunner = await fetch(
    `${baseUrl}/v1/runner/attachments/${uploaded.payload.id}?runnerId=runner-2`,
    { headers: { ...PROTOCOL, authorization: "Bearer runner-2-token" } },
  );
  assert.equal(crossRunner.status, 404);
});

test("ask returns a bounded timeout and a late answer queues resume", async (t) => {
  const { baseUrl } = await fixture(t, 40);
  const { session, sessionToken } = await registerAndCreate(baseUrl);
  const timedOut = await request(baseUrl, `/v1/mcp/sessions/${session.id}/ask`, {
    token: sessionToken,
    method: "POST",
    body: {
      clientCallId: "ask-timeout",
      questions: [{
        id: "choice",
        header: "选择",
        question: "继续吗？",
        multiSelect: false,
        options: [{ id: "yes", label: "继续" }],
      }],
    },
  });
  assert.equal(timedOut.payload.status, "timeout");
  const afterTimeout = await request(baseUrl, `/v1/work/sessions/${session.id}/events?afterSeq=0`);
  assert.ok(afterTimeout.payload.events.some((event) => event.type === "ASK_TIMED_OUT"));
  const timedOutSession = (await request(baseUrl, "/v1/work/sessions")).payload.sessions.find((item) => item.id === session.id);
  assert.equal(timedOutSession.status, "IDLE");

  const answer = await request(baseUrl, `/v1/work/sessions/${session.id}/asks/${timedOut.payload.questionSetId}/answer`, {
    method: "POST",
    idempotencyKey: "late-answer",
    body: { answers: [{ questionId: "choice", selectedOptionIds: [], otherText: "先补测试" }] },
  });
  assert.equal(answer.payload.status, "ANSWERED");
  const commands = await request(baseUrl, runnerCommandsPath(), { token: RUNNER_TOKEN });
  assert.equal(commands.payload.commands.filter((command) => command.kind === "RESUME").length, 1);
});

test("ask creation rolls back completely when event persistence fails", async (t) => {
  const { baseUrl, store } = await fixture(t);
  const { session, sessionToken } = await registerAndCreate(baseUrl);
  store.db.exec(`
    CREATE TRIGGER fail_ask_event BEFORE INSERT ON events
    WHEN NEW.type='ASK' BEGIN SELECT RAISE(ABORT, 'fault injection'); END;
  `);
  const body = {
    clientCallId: "atomic-ask",
    questions: [{
      id: "choice",
      header: "选择",
      question: "继续吗？",
      multiSelect: false,
      options: [{ id: "yes", label: "继续" }],
    }],
  };
  const failed = await request(baseUrl, `/v1/mcp/sessions/${session.id}/ask`, {
    token: sessionToken,
    method: "POST",
    body,
  });
  assert.equal(failed.response.status, 500);
  assert.equal(store.db.prepare("SELECT COUNT(*) AS count FROM asks WHERE client_call_id='atomic-ask'").get().count, 0);
  assert.equal(store.getEvents(session.id).filter((event) => event.type === "ASK").length, 0);
  assert.equal(store.getSession(session.id).status, "QUEUED");

  store.db.exec("DROP TRIGGER fail_ask_event");
  const retry = await request(baseUrl, `/v1/mcp/sessions/${session.id}/ask`, {
    token: sessionToken,
    method: "POST",
    body,
  });
  assert.equal(retry.response.status, 200);
  assert.equal(store.getEvents(session.id).filter((event) => event.type === "ASK").length, 1);
});

test("expired command claims are returned to the owning runner", async (t) => {
  const { baseUrl, store } = await fixture(t);
  await registerAndCreate(baseUrl);
  const pending = await request(baseUrl, runnerCommandsPath(), { token: RUNNER_TOKEN });
  const command = pending.payload.commands[0];
  const claimed = await request(baseUrl, `/v1/runner/commands/${command.id}/ack`, {
    token: RUNNER_TOKEN,
    method: "POST",
    body: { state: "CLAIMED", instanceId: RUNNER_INSTANCE },
  });
  assert.equal(claimed.response.status, 200);
  store.db.prepare("UPDATE commands SET lease_until=? WHERE id=?").run("2000-01-01T00:00:00.000Z", command.id);
  const reclaimed = await request(baseUrl, runnerCommandsPath(), { token: RUNNER_TOKEN });
  assert.equal(reclaimed.payload.commands[0].id, command.id);
});

test("command acknowledgement and session state transition commit together", async (t) => {
  const { baseUrl } = await fixture(t);
  const { session } = await registerAndCreate(baseUrl);
  const pending = await request(baseUrl, runnerCommandsPath(), { token: RUNNER_TOKEN });
  const command = pending.payload.commands[0];
  const claim = await request(baseUrl, `/v1/runner/commands/${command.id}/ack`, {
    token: RUNNER_TOKEN,
    method: "POST",
    body: {
      state: "CLAIMED",
      instanceId: RUNNER_INSTANCE,
      sessionState: { sessionId: session.id, status: "RUNNING", detail: null },
    },
  });
  assert.equal(claim.response.status, 200);
  let current = (await request(baseUrl, "/v1/work/sessions")).payload.sessions.find((item) => item.id === session.id);
  assert.equal(current.status, "RUNNING");

  const complete = await request(baseUrl, `/v1/runner/commands/${command.id}/ack`, {
    token: RUNNER_TOKEN,
    method: "POST",
    body: {
      state: "COMPLETED",
      instanceId: RUNNER_INSTANCE,
      sessionState: { sessionId: session.id, status: "IDLE", detail: "done" },
    },
  });
  assert.equal(complete.response.status, 200);
  const retried = await request(baseUrl, `/v1/runner/commands/${command.id}/ack`, {
    token: RUNNER_TOKEN,
    method: "POST",
    body: {
      state: "COMPLETED",
      instanceId: RUNNER_INSTANCE,
      sessionState: { sessionId: session.id, status: "IDLE", detail: "done" },
    },
  });
  assert.equal(retried.response.status, 200);
  current = (await request(baseUrl, "/v1/work/sessions")).payload.sessions.find((item) => item.id === session.id);
  assert.equal(current.status, "IDLE");
});

test("a restarted runner reclaims the previous process command before its lease expires", async (t) => {
  const { baseUrl } = await fixture(t);
  await registerAndCreate(baseUrl);
  const pending = await request(baseUrl, runnerCommandsPath(), { token: RUNNER_TOKEN });
  const command = pending.payload.commands[0];
  await request(baseUrl, `/v1/runner/commands/${command.id}/ack`, {
    token: RUNNER_TOKEN,
    method: "POST",
    body: { state: "CLAIMED", instanceId: RUNNER_INSTANCE },
  });

  const restartedInstance = "runner-instance-2";
  const registration = await request(baseUrl, "/v1/runner/register", {
    token: RUNNER_TOKEN,
    method: "POST",
    body: {
      id: "runner-1",
      instanceId: restartedInstance,
      name: "Minecraft restarted",
      version: "test",
      repos: [{
        id: "zhixing",
        name: "zhixing",
        models: ["gpt-5.6-sol"],
        reasoningEfforts: ["high", "xhigh"],
      }],
    },
  });
  assert.equal(registration.response.status, 200);

  const heartbeat = await request(baseUrl, "/v1/runner/heartbeat", {
    token: RUNNER_TOKEN,
    method: "POST",
    body: { runnerId: "runner-1", instanceId: restartedInstance },
  });
  assert.equal(heartbeat.response.status, 200);
  const reclaimed = await request(baseUrl, runnerCommandsPath(restartedInstance), { token: RUNNER_TOKEN });
  assert.equal(reclaimed.payload.commands[0].id, command.id);

  const staleHeartbeat = await request(baseUrl, "/v1/runner/heartbeat", {
    token: RUNNER_TOKEN,
    method: "POST",
    body: { runnerId: "runner-1", instanceId: RUNNER_INSTANCE },
  });
  assert.equal(staleHeartbeat.response.status, 409);
});

test("Core restart times out an interrupted ask and restores the session to idle", () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-core-restart-"));
  const filename = join(directory, "core.sqlite");
  const options = {
    filename,
    userToken: USER_TOKEN,
    runnerTokens: { "runner-1": RUNNER_TOKEN },
    sessionSecret: "test-session-secret-at-least-32-bytes",
  };
  let store = new WorkStore(options);
  store.registerRunner({
    id: "runner-1",
    instanceId: RUNNER_INSTANCE,
    name: "Minecraft",
    version: "test",
    repos: [{
      id: "zhixing",
      name: "zhixing",
      models: ["gpt-5.6-sol"],
      reasoningEfforts: ["high"],
    }],
  });
  const session = store.createSession({
    runnerId: "runner-1",
    repoId: "zhixing",
    model: "gpt-5.6-sol",
    reasoningEffort: "high",
    message: "wait for me",
  }, "restart-session");
  assert.equal(session.title, "zhixing");
  const ask = store.createAsk(session.id, {
    clientCallId: "restart-ask",
    questions: [{
      id: "continue",
      header: "选择",
      question: "继续吗？",
      multiSelect: false,
      options: [{ id: "yes", label: "继续" }],
    }],
  });
  store.close();

  store = new WorkStore(options);
  try {
    assert.equal(store.getAsk(ask.id).status, "TIMED_OUT");
    assert.equal(store.getSession(session.id).status, "IDLE");
    assert.ok(store.getEvents(session.id).some((event) => event.type === "ASK_TIMED_OUT"));
  } finally {
    store.close();
    rmSync(directory, { recursive: true, force: true });
  }
});

test("session token is scoped and reports cannot cross sessions", async (t) => {
  const { baseUrl } = await fixture(t);
  const first = await registerAndCreate(baseUrl);
  const secondCreate = await request(baseUrl, "/v1/work/sessions", {
    method: "POST",
    idempotencyKey: "create-2",
    body: {
      runnerId: "runner-1",
      repoId: "zhixing",
      model: "gpt-5.6-sol",
      reasoningEffort: "high",
      message: "另一个会话",
    },
  });
  const crossSession = await request(baseUrl, `/v1/mcp/sessions/${secondCreate.payload.id}/report`, {
    token: first.sessionToken,
    method: "POST",
    body: { text: "越权", clientCallId: "cross" },
  });
  assert.equal(crossSession.response.status, 401);
});

test("idle sessions can be archived, restored and resumed without losing the Codex session", async (t) => {
  const { baseUrl, store } = await fixture(t);
  const { session } = await registerAndCreate(baseUrl);
  store.updateSessionState(session.id, {
    status: "IDLE",
    codexSessionId: "codex-original-session",
    detail: "turn finished",
  });

  const archived = await request(baseUrl, `/v1/work/sessions/${session.id}/archive`, { method: "POST" });
  assert.equal(archived.response.status, 200);
  assert.ok(archived.payload.archivedAt);
  assert.equal(archived.payload.codexSessionId, "codex-original-session");

  const active = await request(baseUrl, "/v1/work/sessions");
  assert.equal(active.payload.sessions.some((item) => item.id === session.id), false);
  const archive = await request(baseUrl, "/v1/work/sessions?archived=true");
  assert.equal(archive.payload.sessions[0].id, session.id);

  const restored = await request(baseUrl, `/v1/work/sessions/${session.id}/unarchive`, { method: "POST" });
  assert.equal(restored.response.status, 200);
  assert.equal(restored.payload.archivedAt, null);
  assert.equal(restored.payload.codexSessionId, "codex-original-session");

  const resumed = await request(baseUrl, `/v1/work/sessions/${session.id}/messages`, {
    method: "POST",
    idempotencyKey: "resume-archived-session",
    body: { text: "继续原任务", clientMessageId: "resume-archived-session" },
  });
  assert.equal(resumed.response.status, 202);
});

test("active sessions cannot be archived", async (t) => {
  const { baseUrl } = await fixture(t);
  const { session } = await registerAndCreate(baseUrl);
  const archived = await request(baseUrl, `/v1/work/sessions/${session.id}/archive`, { method: "POST" });
  assert.equal(archived.response.status, 409);
});

test("session tokens are short-lived, individually revocable and refreshed on resume", async (t) => {
  const { baseUrl } = await fixture(t);
  const { session, sessionToken } = await registerAndCreate(baseUrl);
  const revoked = await request(baseUrl, `/v1/work/sessions/${session.id}/revoke-tokens`, { method: "POST" });
  assert.equal(revoked.response.status, 200);
  assert.ok(revoked.payload.revoked >= 1);
  const rejected = await request(baseUrl, `/v1/mcp/sessions/${session.id}/report`, {
    token: sessionToken,
    method: "POST",
    body: { text: "stale", clientCallId: "revoked-token" },
  });
  assert.equal(rejected.response.status, 401);

  await request(baseUrl, `/v1/work/sessions/${session.id}/messages`, {
    method: "POST",
    idempotencyKey: "resume-after-revoke",
    body: { text: "continue", clientMessageId: "continue-1" },
  });
  const commands = await request(baseUrl, runnerCommandsPath(), { token: RUNNER_TOKEN });
  const resume = commands.payload.commands.find((command) => command.kind === "RESUME");
  assert.equal(resume.payload.repoId, "zhixing");
  assert.equal(resume.payload.model, "gpt-5.6-sol");
  assert.equal(resume.payload.reasoningEffort, "high");
  assert.ok(resume.payload.sessionToken);
  const accepted = await request(baseUrl, `/v1/mcp/sessions/${session.id}/report`, {
    token: resume.payload.sessionToken,
    method: "POST",
    body: { text: "fresh", clientCallId: "fresh-token" },
  });
  assert.equal(accepted.response.status, 200);
});

test("a failed first turn restarts with its original message, image and session snapshot", async (t) => {
  const { baseUrl } = await fixture(t);
  await request(baseUrl, "/v1/runner/register", {
    token: RUNNER_TOKEN,
    method: "POST",
    body: {
      id: "runner-1",
      instanceId: RUNNER_INSTANCE,
      name: "Minecraft",
      version: "test",
      repos: [{
        id: "zhixing",
        name: "zhixing",
        models: ["gpt-5.6-sol"],
        reasoningEfforts: ["high"],
      }],
    },
  });
  const uploaded = await uploadImage(baseUrl, Buffer.from("89504e470d0a1a0a", "hex"));
  assert.equal(uploaded.response.status, 201);
  const created = await request(baseUrl, "/v1/work/sessions", {
    method: "POST",
    idempotencyKey: "legacy-create",
    body: {
      runnerId: "runner-1",
      repoId: "zhixing",
      model: "gpt-5.6-sol",
      reasoningEffort: "high",
      message: "按截图修复布局",
      attachmentIds: [uploaded.payload.id],
      clientMessageId: "legacy-first",
    },
  });
  const initialCommands = await request(baseUrl, runnerCommandsPath(), { token: RUNNER_TOKEN });
  const first = initialCommands.payload.commands.find((command) => command.kind === "START");
  await request(baseUrl, `/v1/runner/commands/${first.id}/ack`, {
    token: RUNNER_TOKEN,
    method: "POST",
    body: { instanceId: RUNNER_INSTANCE, state: "CLAIMED" },
  });
  await request(baseUrl, `/v1/runner/commands/${first.id}/ack`, {
    token: RUNNER_TOKEN,
    method: "POST",
    body: {
      instanceId: RUNNER_INSTANCE,
      state: "FAILED",
      sessionState: { sessionId: created.payload.id, status: "FAILED", detail: "download timeout" },
    },
  });

  const retried = await request(baseUrl, `/v1/work/sessions/${created.payload.id}/messages`, {
    method: "POST",
    idempotencyKey: "legacy-retry",
    body: { text: "继续处理", clientMessageId: "legacy-retry" },
  });
  assert.equal(retried.response.status, 202);
  const commands = await request(baseUrl, runnerCommandsPath(), { token: RUNNER_TOKEN });
  const restart = commands.payload.commands.find((command) => command.id !== first.id);
  assert.equal(restart.kind, "START");
  assert.equal(restart.payload.repoId, "zhixing");
  assert.equal(restart.payload.model, "gpt-5.6-sol");
  assert.equal(restart.payload.reasoningEffort, "high");
  assert.equal(restart.payload.message, "按截图修复布局\n\n补充消息：\n继续处理");
  assert.deepEqual(restart.payload.attachments.map((item) => item.id), [uploaded.payload.id]);
});

test("runner credentials cannot read or acknowledge another runner's commands", async (t) => {
  const { baseUrl } = await fixture(t);
  await registerAndCreate(baseUrl);
  const crossRead = await request(baseUrl, runnerCommandsPath(), { token: "runner-2-token" });
  assert.equal(crossRead.response.status, 401);

  const own = await request(baseUrl, runnerCommandsPath(), { token: RUNNER_TOKEN });
  const commandId = own.payload.commands[0].id;
  const crossAck = await request(baseUrl, `/v1/runner/commands/${commandId}/ack`, {
    token: "runner-2-token",
    method: "POST",
    body: { state: "CLAIMED", instanceId: RUNNER_INSTANCE },
  });
  assert.equal(crossAck.response.status, 401);
});
