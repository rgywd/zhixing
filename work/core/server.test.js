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
    runnerToken: RUNNER_TOKEN,
    sessionSecret: "test-session-secret-at-least-32-bytes",
  });
  const server = createWorkServer({ store, askTimeoutMs });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const { port } = server.address();
  t.after(async () => {
    server.close();
    await once(server, "close");
    store.close();
  });
  return { store, baseUrl: `http://127.0.0.1:${port}` };
}

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

  const answer = await request(baseUrl, `/v1/work/sessions/${session.id}/asks/${timedOut.payload.questionSetId}/answer`, {
    method: "POST",
    idempotencyKey: "late-answer",
    body: { answers: [{ questionId: "choice", selectedOptionIds: [], otherText: "先补测试" }] },
  });
  assert.equal(answer.payload.status, "ANSWERED");
  const commands = await request(baseUrl, "/v1/runner/commands?runnerId=runner-1", { token: RUNNER_TOKEN });
  assert.equal(commands.payload.commands.filter((command) => command.kind === "RESUME").length, 1);
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
