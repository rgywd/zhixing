import assert from "node:assert/strict";
import { once } from "node:events";
import { existsSync, mkdtempSync, readFileSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { DatabaseSync } from "node:sqlite";
import test from "node:test";
import { createWorkServer } from "./server.js";
import { WorkStore } from "./store.js";

const USER_TOKEN = "user-test-token";
const RUNNER_TOKEN = "runner-test-token";
const RUNNER_INSTANCE = "runner-instance-1";
const PROTOCOL = { "x-zhixing-work-protocol": "1" };

async function fixture(t, askTimeoutMs = 150, quotaProxy = null, informationMonitorProxy = null) {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-core-test-"));
  const attachmentRoot = join(directory, "attachments");
  const store = new WorkStore({
    userToken: USER_TOKEN,
    runnerTokens: { "runner-1": RUNNER_TOKEN, "runner-2": "runner-2-token" },
    sessionSecret: "test-session-secret-at-least-32-bytes",
    attachmentRoot,
  });
  const server = createWorkServer({ store, askTimeoutMs, quotaProxy, informationMonitorProxy });
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
    rmSync(directory, { recursive: true, force: true });
  });
  return { store, attachmentRoot, baseUrl: `http://127.0.0.1:${port}` };
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

async function uploadAttachment(baseUrl, data, { fileName = "screen.png", mimeType = "image/png" } = {}) {
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

async function uploadImage(baseUrl, data, options) {
  return uploadAttachment(baseUrl, data, options);
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

test("life inbox endpoints use Work user authentication and expose stale state only in safe headers", async (t) => {
  const calls = [];
  const expected = {
    schema: "information-monitor/v1",
    items: [{
      id: "event_01",
      sourceLabel: "工作邮箱",
      channel: "email",
      occurredAt: "2026-07-26T03:04:05Z",
      sender: null,
      title: "本周安排",
      summary: "项目组更新了本周安排。",
      actionItems: [],
      importance: "high",
    }],
  };
  const informationMonitorProxy = {
    get: async (action, searchParams) => {
      calls.push({ action, query: searchParams.toString() });
      return { body: expected, isStale: true, errorCode: "upstream_timeout" };
    },
  };
  const { baseUrl } = await fixture(t, 150, null, informationMonitorProxy);

  const missingProtocol = await fetch(`${baseUrl}/v1/life/inbox/items`, {
    headers: { authorization: `Bearer ${USER_TOKEN}` },
  });
  assert.equal(missingProtocol.status, 426);
  assert.equal(calls.length, 0);

  const unauthorized = await request(baseUrl, "/v1/life/inbox/items", { token: "wrong-token" });
  assert.equal(unauthorized.response.status, 401);
  assert.equal(calls.length, 0);

  const result = await request(
    baseUrl,
    "/v1/life/inbox/items?channel=email&hours=24&limit=10&minImportance=high",
  );
  assert.equal(result.response.status, 200);
  assert.deepEqual(result.payload, expected);
  assert.deepEqual(calls, [{
    action: "items",
    query: "channel=email&hours=24&limit=10&minImportance=high",
  }]);
  assert.equal(result.response.headers.get("x-zhixing-life-cache"), "stale");
  assert.equal(result.response.headers.get("x-zhixing-life-error"), "upstream_timeout");
  assert.match(result.response.headers.get("warning"), /^110 /);
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
        recommendedOptionIds: ["yes"],
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
      html: '<h2>通过</h2><script>alert(\'xss\')</script><p>完成</p>'
        + '<img src="data:image/png;base64,iVBORw0KGgo=" alt="图表">'
        + '<img src="https://evil.example/x.png">'
        + '<a href="https://example.com/spec">规范</a>'
        + '<a href="javascript:alert(1)">坏链接</a>',
      clientCallId: "html-1",
    },
  });
  assert.equal(html.response.status, 200);
  assert.ok(html.payload.outputBytes > 0);
  const reportPage = await request(baseUrl, `/v1/work/reports/${html.payload.reportId}`);
  assert.equal(reportPage.response.status, 200);
  assert.match(reportPage.payload, /<h2>通过<\/h2>/);
  assert.doesNotMatch(reportPage.payload, /<script>/);
  assert.match(reportPage.payload, /<title>验收报告<\/title>/);
  assert.match(reportPage.payload, /<img src="data:image\/png;base64,iVBORw0KGgo=" alt="图表" ?\/>/);
  assert.doesNotMatch(reportPage.payload, /evil\.example/);
  assert.match(reportPage.payload, /<a href="https:\/\/example\.com\/spec">规范<\/a>/);
  assert.doesNotMatch(reportPage.payload, /javascript:/);
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

test("runtime catalog creates and resumes a Claude Code session without breaking Codex fields", async (t) => {
  const { baseUrl } = await fixture(t);
  const registration = await request(baseUrl, "/v1/runner/register", {
    token: RUNNER_TOKEN,
    method: "POST",
    body: {
      id: "runner-1",
      instanceId: RUNNER_INSTANCE,
      name: "Minecraft",
      version: "test",
      capabilities: { codex: true, claudeCode: true, phoneLineProtocol: 1 },
      repos: [{
        id: "zhixing",
        name: "zhixing",
        runtimes: [
          {
            id: "codex",
            name: "Codex",
            models: ["gpt-5.6-sol"],
            reasoningEfforts: ["high"],
          },
          {
            id: "claude-code",
            name: "Claude Code",
            models: ["sonnet", "opus"],
            reasoningEfforts: ["high", "xhigh"],
          },
        ],
        models: ["gpt-5.6-sol"],
        reasoningEfforts: ["high"],
      }],
    },
  });
  assert.equal(registration.response.status, 200);

  const repos = await request(baseUrl, "/v1/work/repos?runnerId=runner-1");
  assert.deepEqual(repos.payload.repos[0].runtimes.map((runtime) => runtime.id), ["codex", "claude-code"]);

  const created = await request(baseUrl, "/v1/work/sessions", {
    method: "POST",
    idempotencyKey: "create-claude",
    body: {
      runnerId: "runner-1",
      repoId: "zhixing",
      title: "Claude Code 接入",
      runtime: "claude-code",
      model: "sonnet",
      reasoningEffort: "high",
      message: "实现 Claude Code 接入",
    },
  });
  assert.equal(created.response.status, 201);
  assert.equal(created.payload.runtime, "claude-code");
  assert.equal(created.payload.runtimeSessionId, null);
  assert.equal(created.payload.codexSessionId, null);

  const commands = await request(baseUrl, runnerCommandsPath(), { token: RUNNER_TOKEN });
  assert.equal(commands.payload.commands[0].payload.runtime, "claude-code");

  const runtimeState = await request(
    baseUrl,
    `/v1/runner/sessions/${created.payload.id}/state`,
    {
      token: RUNNER_TOKEN,
      method: "POST",
      body: {
        instanceId: RUNNER_INSTANCE,
        status: "IDLE",
        runtime: "claude-code",
        runtimeSessionId: "claude-session-1",
      },
    },
  );
  assert.equal(runtimeState.response.status, 200);

  const updated = (await request(baseUrl, "/v1/work/sessions")).payload.sessions
    .find((session) => session.id === created.payload.id);
  assert.equal(updated.runtimeSessionId, "claude-session-1");
  assert.equal(updated.codexSessionId, null);

  await request(baseUrl, `/v1/work/sessions/${created.payload.id}/messages`, {
    method: "POST",
    idempotencyKey: "resume-claude",
    body: { text: "继续" },
  });
  const resumed = await request(baseUrl, runnerCommandsPath(), { token: RUNNER_TOKEN });
  const resumeCommand = resumed.payload.commands.find((command) => command.kind === "RESUME");
  assert.equal(resumeCommand.payload.runtime, "claude-code");
  assert.equal(resumeCommand.payload.model, "sonnet");
});

test("runtime catalog enforces model-specific reasoning efforts", async (t) => {
  const { baseUrl } = await fixture(t);
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
        runtimes: [{
          id: "codex",
          name: "Codex",
          models: ["gpt-5.6-sol", "gpt-5.3-codex-spark"],
          reasoningEfforts: ["low", "medium", "high", "xhigh", "max"],
          reasoningEffortsByModel: {
            "gpt-5.3-codex-spark": ["low", "medium", "high", "xhigh"],
          },
        }],
      }],
    },
  });
  assert.equal(registration.response.status, 200);

  const repos = await request(baseUrl, "/v1/work/repos?runnerId=runner-1");
  assert.deepEqual(
    repos.payload.repos[0].runtimes[0].reasoningEffortsByModel,
    { "gpt-5.3-codex-spark": ["low", "medium", "high", "xhigh"] },
  );

  const rejected = await request(baseUrl, "/v1/work/sessions", {
    method: "POST",
    idempotencyKey: "spark-max-rejected",
    body: {
      runnerId: "runner-1",
      repoId: "zhixing",
      model: "gpt-5.3-codex-spark",
      reasoningEffort: "max",
      message: "do not start",
    },
  });
  assert.equal(rejected.response.status, 400);

  const accepted = await request(baseUrl, "/v1/work/sessions", {
    method: "POST",
    idempotencyKey: "spark-xhigh-accepted",
    body: {
      runnerId: "runner-1",
      repoId: "zhixing",
      model: "gpt-5.3-codex-spark",
      reasoningEffort: "xhigh",
      message: "start",
    },
  });
  assert.equal(accepted.response.status, 201);
});

test("a follow-up message atomically changes the reasoning effort for its resume turn", async (t) => {
  const { baseUrl } = await fixture(t);
  const { session } = await registerAndCreate(baseUrl);

  const changed = await request(baseUrl, `/v1/work/sessions/${session.id}/messages`, {
    method: "POST",
    idempotencyKey: "resume-with-xhigh",
    body: {
      text: "请用更深的思考复查",
      reasoningEffort: "xhigh",
      clientMessageId: "message-xhigh",
    },
  });

  assert.equal(changed.response.status, 202);
  const commands = await request(baseUrl, runnerCommandsPath(), { token: RUNNER_TOKEN });
  const resume = commands.payload.commands.find((command) => command.kind === "RESUME");
  assert.equal(resume.payload.reasoningEffort, "xhigh");
  const sessions = await request(baseUrl, "/v1/work/sessions");
  assert.equal(sessions.payload.sessions.find((candidate) => candidate.id === session.id).reasoningEffort, "xhigh");
});

test("an invalid follow-up reasoning effort rejects the message without partial writes", async (t) => {
  const { baseUrl } = await fixture(t);
  const { session } = await registerAndCreate(baseUrl);

  const rejected = await request(baseUrl, `/v1/work/sessions/${session.id}/messages`, {
    method: "POST",
    idempotencyKey: "resume-with-invalid-effort",
    body: {
      text: "这条消息不能落库",
      reasoningEffort: "ultra",
      clientMessageId: "message-invalid",
    },
  });

  assert.equal(rejected.response.status, 400);
  const events = await request(baseUrl, `/v1/work/sessions/${session.id}/events?afterSeq=0`);
  assert.deepEqual(
    events.payload.events.filter((event) => event.type === "USER_MESSAGE").map((event) => event.payload.text),
    ["实现 phone-line 闭环"],
  );
  const commands = await request(baseUrl, runnerCommandsPath(), { token: RUNNER_TOKEN });
  assert.equal(commands.payload.commands.some((command) => command.kind === "RESUME"), false);
  const sessions = await request(baseUrl, "/v1/work/sessions");
  assert.equal(sessions.payload.sessions.find((candidate) => candidate.id === session.id).reasoningEffort, "high");
});

test("follow-up effort changes remain idempotent and older clients keep the current effort", async (t) => {
  const { baseUrl } = await fixture(t);
  const { session } = await registerAndCreate(baseUrl);

  const first = await request(baseUrl, `/v1/work/sessions/${session.id}/messages`, {
    method: "POST",
    idempotencyKey: "same-effort-change",
    body: { text: "深度复查", reasoningEffort: "xhigh", clientMessageId: "message-deep" },
  });
  const duplicate = await request(baseUrl, `/v1/work/sessions/${session.id}/messages`, {
    method: "POST",
    idempotencyKey: "same-effort-change",
    body: { text: "不应覆盖", reasoningEffort: "high", clientMessageId: "message-duplicate" },
  });
  assert.equal(duplicate.payload.id, first.payload.id);

  await request(baseUrl, `/v1/work/sessions/${session.id}/messages`, {
    method: "POST",
    idempotencyKey: "legacy-follow-up",
    body: { text: "旧客户端继续", clientMessageId: "message-legacy" },
  });
  await request(baseUrl, `/v1/work/sessions/${session.id}/messages`, {
    method: "POST",
    idempotencyKey: "queued-effort-change",
    body: { text: "下一轮恢复常规深度", reasoningEffort: "high", clientMessageId: "message-high" },
  });

  const commands = await request(baseUrl, runnerCommandsPath(), { token: RUNNER_TOKEN });
  const resumes = commands.payload.commands.filter((command) => command.kind === "RESUME");
  assert.deepEqual(resumes.map((command) => command.payload.reasoningEffort), ["xhigh", "xhigh", "high"]);
  const events = await request(baseUrl, `/v1/work/sessions/${session.id}/events?afterSeq=0`);
  assert.equal(events.payload.events.filter((event) => event.type === "USER_MESSAGE").length, 4);
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

test("ordinary and archive attachments live on disk and require runner capability", async (t) => {
  const { baseUrl, store, attachmentRoot } = await fixture(t);
  await request(baseUrl, "/v1/runner/register", {
    token: RUNNER_TOKEN,
    method: "POST",
    body: {
      id: "runner-1",
      instanceId: RUNNER_INSTANCE,
      name: "Minecraft",
      version: "test",
      capabilities: { codex: true, phoneLineProtocol: 1, fileAttachments: 1 },
      repos: [{
        id: "zhixing",
        name: "zhixing",
        models: ["gpt-5.6-sol"],
        reasoningEfforts: ["high"],
      }],
    },
  });
  const gzipBytes = Buffer.from("1f8b0800000000000003", "hex");
  const uploaded = await uploadAttachment(baseUrl, gzipBytes, {
    fileName: "logs.tar.gz",
    mimeType: "application/gzip",
  });
  assert.equal(uploaded.response.status, 201);
  assert.equal(uploaded.payload.fileName, "logs.tar.gz");
  assert.equal(uploaded.payload.mimeType, "application/gzip");

  const stored = store.db.prepare("SELECT data, storage_key FROM attachments WHERE id=?").get(uploaded.payload.id);
  assert.equal(stored.data, null);
  assert.ok(stored.storage_key);
  assert.equal(stored.storage_key.includes("logs.tar.gz"), false);
  assert.equal(stored.storage_key.includes(attachmentRoot), false);
  const storedPath = join(attachmentRoot, ...stored.storage_key.split("/"));
  assert.equal(existsSync(storedPath), true);
  assert.deepEqual(readFileSync(storedPath), gzipBytes);

  const created = await request(baseUrl, "/v1/work/sessions", {
    method: "POST",
    idempotencyKey: "archive-session",
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
  const download = await fetch(
    `${baseUrl}/v1/runner/attachments/${uploaded.payload.id}?runnerId=runner-1`,
    { headers: { ...PROTOCOL, authorization: `Bearer ${RUNNER_TOKEN}` } },
  );
  assert.equal(download.status, 200);
  assert.deepEqual(Buffer.from(await download.arrayBuffer()), gzipBytes);

  await request(baseUrl, "/v1/runner/register", {
    token: "runner-2-token",
    method: "POST",
    body: {
      id: "runner-2",
      instanceId: "runner-2-instance",
      name: "Legacy runner",
      version: "old",
      capabilities: { codex: true, phoneLineProtocol: 1 },
      repos: [{
        id: "zhixing",
        name: "zhixing",
        models: ["gpt-5.6-sol"],
        reasoningEfforts: ["high"],
      }],
    },
  });
  const second = await uploadAttachment(baseUrl, Buffer.from("PK\u0003\u0004"), {
    fileName: "source.zip",
    mimeType: "application/zip",
  });
  assert.equal(second.response.status, 201);
  const rejected = await request(baseUrl, "/v1/work/sessions", {
    method: "POST",
    idempotencyKey: "legacy-file-session",
    body: {
      runnerId: "runner-2",
      repoId: "zhixing",
      model: "gpt-5.6-sol",
      reasoningEffort: "high",
      message: "inspect",
      attachmentIds: [second.payload.id],
    },
  });
  assert.equal(rejected.response.status, 409);
  assert.match(rejected.payload.message, /does not support file attachments/i);
});

test("expired unbound external attachments are removed from disk and SQLite together", async (t) => {
  const { baseUrl, store, attachmentRoot } = await fixture(t);
  const stale = await uploadAttachment(baseUrl, Buffer.from("1f8b08", "hex"), {
    fileName: "stale.gz",
    mimeType: "application/gzip",
  });
  assert.equal(stale.response.status, 201);
  const stored = store.db.prepare("SELECT storage_key FROM attachments WHERE id=?").get(stale.payload.id);
  const storedPath = join(attachmentRoot, ...stored.storage_key.split("/"));
  store.db.prepare("UPDATE attachments SET created_at='2000-01-01T00:00:00.000Z' WHERE id=?")
    .run(stale.payload.id);

  const trigger = await uploadAttachment(baseUrl, Buffer.from("504b0304", "hex"), {
    fileName: "next.zip",
    mimeType: "application/zip",
  });

  assert.equal(trigger.response.status, 201);
  assert.equal(store.db.prepare("SELECT id FROM attachments WHERE id=?").get(stale.payload.id), undefined);
  assert.equal(existsSync(storedPath), false);
});

test("attachment upload rejects spoofed archives and executable file types", async (t) => {
  const { baseUrl } = await fixture(t);
  const spoofed = await uploadAttachment(baseUrl, Buffer.from("not-a-zip"), {
    fileName: "source.zip",
    mimeType: "application/zip",
  });
  assert.equal(spoofed.response.status, 415);

  const executable = await uploadAttachment(baseUrl, Buffer.from("MZ"), {
    fileName: "setup.exe",
    mimeType: "application/octet-stream",
  });
  assert.equal(executable.response.status, 415);
});

test("ask timeout settles with the recommended options and a late answer is ignored", async (t) => {
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
        options: [{ id: "yes", label: "继续" }, { id: "no", label: "停止" }],
        recommendedOptionIds: ["yes"],
      }],
    },
  });
  assert.equal(timedOut.payload.status, "auto_answered");
  assert.deepEqual(timedOut.payload.answers, [{ questionId: "choice", selectedOptionIds: ["yes"], otherText: null }]);
  const afterTimeout = await request(baseUrl, `/v1/work/sessions/${session.id}/events?afterSeq=0`);
  const settled = afterTimeout.payload.events.find((event) => event.type === "ASK_ANSWERED");
  assert.equal(settled.payload.source, "timeout_default");
  const timedOutSession = (await request(baseUrl, "/v1/work/sessions")).payload.sessions.find((item) => item.id === session.id);
  assert.equal(timedOutSession.status, "RUNNING");

  const askId = settled.payload.askId;
  const answer = await request(baseUrl, `/v1/work/sessions/${session.id}/asks/${askId}/answer`, {
    method: "POST",
    idempotencyKey: "late-answer",
    body: { answers: [{ questionId: "choice", selectedOptionIds: [], otherText: "先补测试" }] },
  });
  assert.equal(answer.payload.status, "ANSWERED");
  assert.deepEqual(answer.payload.answers, [{ questionId: "choice", selectedOptionIds: ["yes"], otherText: null }]);
  const commands = await request(baseUrl, runnerCommandsPath(), { token: RUNNER_TOKEN });
  assert.equal(commands.payload.commands.filter((command) => command.kind === "RESUME").length, 0);
});

test("ask remains pending until the timeout window expires or the user answers", async (t) => {
  const { baseUrl } = await fixture(t, 600);
  const { session, sessionToken } = await registerAndCreate(baseUrl);
  const askPromise = request(baseUrl, `/v1/mcp/sessions/${session.id}/ask`, {
    token: sessionToken,
    method: "POST",
    body: {
      clientCallId: "ask-within-window",
      questions: [{
        id: "choice",
        header: "选择",
        question: "继续吗？",
        multiSelect: false,
        options: [{ id: "yes", label: "继续" }, { id: "no", label: "停止" }],
        recommendedOptionIds: ["yes"],
      }],
    },
  });

  await new Promise((resolve) => setTimeout(resolve, 150));
  const eventsBeforeAnswer = await request(baseUrl, `/v1/work/sessions/${session.id}/events?afterSeq=0`);
  const askEvent = eventsBeforeAnswer.payload.events.find((event) => event.type === "ASK");
  assert.ok(askEvent);
  assert.equal(eventsBeforeAnswer.payload.events.some((event) => event.type === "ASK_ANSWERED"), false);
  const waitingSession = (await request(baseUrl, "/v1/work/sessions")).payload.sessions
    .find((item) => item.id === session.id);
  assert.equal(waitingSession.status, "WAITING_FOR_USER");

  const answer = await request(baseUrl, `/v1/work/sessions/${session.id}/asks/${askEvent.payload.askId}/answer`, {
    method: "POST",
    idempotencyKey: "answer-within-window",
    body: { answers: [{ questionId: "choice", selectedOptionIds: ["no"], otherText: null }] },
  });
  assert.equal(answer.response.status, 200);
  const askResult = await askPromise;
  assert.equal(askResult.payload.status, "answered");
  assert.deepEqual(askResult.payload.answers, [{
    questionId: "choice",
    selectedOptionIds: ["no"],
    otherText: null,
  }]);
});

test("ask requires honest recommended options for the timeout fallback", async (t) => {
  const { baseUrl } = await fixture(t);
  const { session, sessionToken } = await registerAndCreate(baseUrl);
  const base = {
    id: "choice",
    header: "选择",
    question: "继续吗？",
    multiSelect: false,
    options: [{ id: "yes", label: "继续" }, { id: "no", label: "停止" }],
  };
  const missing = await request(baseUrl, `/v1/mcp/sessions/${session.id}/ask`, {
    token: sessionToken,
    method: "POST",
    body: { clientCallId: "ask-no-default", questions: [base] },
  });
  assert.equal(missing.response.status, 400);
  const unknown = await request(baseUrl, `/v1/mcp/sessions/${session.id}/ask`, {
    token: sessionToken,
    method: "POST",
    body: { clientCallId: "ask-bad-default", questions: [{ ...base, recommendedOptionIds: ["maybe"] }] },
  });
  assert.equal(unknown.response.status, 400);
  const ambiguous = await request(baseUrl, `/v1/mcp/sessions/${session.id}/ask`, {
    token: sessionToken,
    method: "POST",
    body: { clientCallId: "ask-two-defaults", questions: [{ ...base, recommendedOptionIds: ["yes", "no"] }] },
  });
  assert.equal(ambiguous.response.status, 400);
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
      recommendedOptionIds: ["yes"],
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

test("Core restart settles an interrupted ask with its recommended options and restores the session to idle", () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-core-restart-"));
  const filename = join(directory, "core.sqlite");
  const options = {
    filename,
    attachmentRoot: join(directory, "attachments"),
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
      recommendedOptionIds: ["yes"],
    }],
  });
  store.close();

  store = new WorkStore(options);
  try {
    const recovered = store.getAsk(ask.id);
    assert.equal(recovered.status, "ANSWERED");
    assert.deepEqual(recovered.answers, [{ questionId: "continue", selectedOptionIds: ["yes"], otherText: null }]);
    assert.equal(store.getSession(session.id).status, "IDLE");
    const settled = store.getEvents(session.id).find((event) => event.type === "ASK_ANSWERED");
    assert.equal(settled.payload.source, "timeout_default");
  } finally {
    store.close();
    rmSync(directory, { recursive: true, force: true });
  }
});

test("Core migrates legacy attachment blobs without losing existing images", () => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-core-attachment-migration-"));
  const filename = join(directory, "core.sqlite");
  const legacy = new DatabaseSync(filename);
  legacy.exec(`
    CREATE TABLE attachments (
      id TEXT PRIMARY KEY,
      session_id TEXT,
      file_name TEXT NOT NULL,
      mime_type TEXT NOT NULL,
      size INTEGER NOT NULL,
      sha256 TEXT NOT NULL,
      data BLOB NOT NULL,
      created_at TEXT NOT NULL
    );
  `);
  const bytes = Buffer.from("89504e470d0a1a0a", "hex");
  legacy.prepare(`
    INSERT INTO attachments(id, session_id, file_name, mime_type, size, sha256, data, created_at)
    VALUES ('att_legacy', NULL, 'legacy.png', 'image/png', ?, 'legacy-hash', ?, '2026-08-10T00:00:00Z')
  `).run(bytes.length, bytes);
  legacy.close();

  const store = new WorkStore({
    filename,
    attachmentRoot: join(directory, "attachments"),
    userToken: USER_TOKEN,
    runnerTokens: { "runner-1": RUNNER_TOKEN },
    sessionSecret: "test-session-secret-at-least-32-bytes",
  });
  try {
    const columns = store.db.prepare("PRAGMA table_info(attachments)").all();
    assert.equal(columns.find((column) => column.name === "data").notnull, 0);
    assert.ok(columns.some((column) => column.name === "storage_key"));
    const migrated = store.db.prepare("SELECT data, storage_key FROM attachments WHERE id='att_legacy'").get();
    assert.deepEqual(Buffer.from(migrated.data), bytes);
    assert.equal(migrated.storage_key, null);
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
