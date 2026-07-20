import assert from "node:assert/strict";
import { once } from "node:events";
import test from "node:test";
import { createWorkServer } from "./server.js";
import { WorkStore } from "./store.js";

const USER_TOKEN = "user-test-token";
const RUNNER_TOKEN = "runner-test-token";
const PROTOCOL = { "x-zhixing-work-protocol": "1" };

async function fixture(t, askTimeoutMs = 150) {
  const store = new WorkStore({
    userToken: USER_TOKEN,
    runnerTokens: { "runner-1": RUNNER_TOKEN, "runner-2": "runner-2-token" },
    sessionSecret: "test-session-secret-at-least-32-bytes",
  });
  const server = createWorkServer({ store, askTimeoutMs });
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

async function registerAndCreate(baseUrl) {
  const registration = await request(baseUrl, "/v1/runner/register", {
    token: RUNNER_TOKEN,
    method: "POST",
    body: {
      id: "runner-1",
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
      model: "gpt-5.6-sol",
      reasoningEffort: "high",
      message: "实现 phone-line 闭环",
      clientMessageId: "phone-1",
    },
  });
  assert.equal(created.response.status, 201);
  const commands = await request(baseUrl, "/v1/runner/commands?runnerId=runner-1", { token: RUNNER_TOKEN });
  const start = commands.payload.commands[0];
  return { session: created.payload, sessionToken: start.payload.sessionToken };
}

test("full phone-line API flow is durable, ordered and idempotent", async (t) => {
  const { baseUrl } = await fixture(t, 500);
  const { session, sessionToken } = await registerAndCreate(baseUrl);

  const duplicate = await request(baseUrl, "/v1/work/sessions", {
    method: "POST",
    idempotencyKey: "create-1",
    body: { runnerId: "ignored" },
  });
  assert.equal(duplicate.payload.id, session.id);

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
  const commands = await request(baseUrl, "/v1/runner/commands?runnerId=runner-1", { token: RUNNER_TOKEN });
  assert.equal(commands.payload.commands.filter((command) => command.kind === "RESUME").length, 1);
});

test("expired command claims are returned to the owning runner", async (t) => {
  const { baseUrl, store } = await fixture(t);
  await registerAndCreate(baseUrl);
  const pending = await request(baseUrl, "/v1/runner/commands?runnerId=runner-1", { token: RUNNER_TOKEN });
  const command = pending.payload.commands[0];
  const claimed = await request(baseUrl, `/v1/runner/commands/${command.id}/ack`, {
    token: RUNNER_TOKEN,
    method: "POST",
    body: { state: "CLAIMED" },
  });
  assert.equal(claimed.response.status, 200);
  store.db.prepare("UPDATE commands SET lease_until=? WHERE id=?").run("2000-01-01T00:00:00.000Z", command.id);
  const reclaimed = await request(baseUrl, "/v1/runner/commands?runnerId=runner-1", { token: RUNNER_TOKEN });
  assert.equal(reclaimed.payload.commands[0].id, command.id);
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

test("runner credentials cannot read or acknowledge another runner's commands", async (t) => {
  const { baseUrl } = await fixture(t);
  await registerAndCreate(baseUrl);
  const crossRead = await request(baseUrl, "/v1/runner/commands?runnerId=runner-1", { token: "runner-2-token" });
  assert.equal(crossRead.response.status, 401);

  const own = await request(baseUrl, "/v1/runner/commands?runnerId=runner-1", { token: RUNNER_TOKEN });
  const commandId = own.payload.commands[0].id;
  const crossAck = await request(baseUrl, `/v1/runner/commands/${commandId}/ack`, {
    token: "runner-2-token",
    method: "POST",
    body: { state: "CLAIMED" },
  });
  assert.equal(crossAck.response.status, 401);
});
