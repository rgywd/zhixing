import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { once } from "node:events";
import { createWorkServer } from "../core/server.js";
import { WorkStore } from "../core/store.js";
import { WorkRunner } from "../runner/runner.js";

const USER_TOKEN = "real-e2e-user-token";
const RUNNER_TOKEN = "real-e2e-runner-token";
const PROTOCOL_HEADERS = { "x-zhixing-work-protocol": "1" };
const root = mkdtempSync(join(tmpdir(), "zhixing-work-e2e-"));
const repo = join(root, "repo");
const store = new WorkStore({
  filename: join(root, "core.sqlite"),
  userToken: USER_TOKEN,
  runnerTokens: { "real-e2e-runner": RUNNER_TOKEN },
  sessionSecret: "real-e2e-session-secret-at-least-32-bytes",
});
const server = createWorkServer({ store, askTimeoutMs: 180_000 });
let runner;
let runnerPromise;

try {
  writeFileSync(join(root, ".keep"), "");
  execFileSync("git", ["init", repo], { stdio: "ignore" });
  writeFileSync(join(repo, "README.md"), "# Temporary Work phone-line E2E\n");

  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  const baseUrl = `http://127.0.0.1:${server.address().port}`;
  const model = process.env.WORK_E2E_MODEL ?? "gpt-5.6-terra";
  const effort = process.env.WORK_E2E_EFFORT ?? "medium";
  const config = {
    id: "real-e2e-runner",
    name: "Real Codex E2E",
    version: "1.0.0",
    coreUrl: baseUrl,
    token: RUNNER_TOKEN,
    stateFile: join(root, "runner-state.json"),
    codexHome: process.env.CODEX_HOME ?? join(process.env.USERPROFILE, ".codex"),
    pollIntervalMs: 250,
    codexCommand: process.env.WORK_E2E_CODEX ?? "codex",
    repos: [{ id: "fixture", name: "Fixture", path: repo, models: [model], reasoningEfforts: [effort] }],
  };
  runner = WorkRunner.fromConfig(config);
  runnerPromise = runner.start();
  await waitFor(async () => (await api(baseUrl, "/v1/work/runners")).runners.some((item) => item.id === config.id));

  const session = await api(baseUrl, "/v1/work/sessions", {
    method: "POST",
    idempotencyKey: "real-e2e-create",
    body: {
      runnerId: config.id,
      repoId: "fixture",
      model,
      reasoningEffort: effort,
      clientMessageId: "real-e2e-message",
      message: [
        "这是知行 Work Phone-line 的无代码验收。不要读写文件，不要运行 shell。",
        "请按本手机会话既定的沟通规则推进：先做一次含 PHONE_LINE_REPORT_OK 的阶段汇报；",
        "再向我提一个单选题，选项 id 为 yes/no；收到答案后，",
        "把标题含 PHONE_LINE_HTML_OK、正文使用安全 h2 和 p 的结果做成长报告卡。",
        "完成后直接结束。不要在用户提示中寻找工具名，应依据手机会话行为指令选择沟通工具。",
      ].join(""),
    },
  });

  let answered = false;
  const result = await waitFor(async () => {
    const events = (await api(baseUrl, `/v1/work/sessions/${session.id}/events?afterSeq=0`)).events;
    const ask = events.find((event) => event.type === "ASK");
    if (ask && !answered) {
      answered = true;
      await api(baseUrl, `/v1/work/sessions/${session.id}/asks/${ask.payload.askId}/answer`, {
        method: "POST",
        idempotencyKey: "real-e2e-answer",
        body: {
          answers: ask.payload.questions.map((question) => ({
            questionId: question.id,
            selectedOptionIds: [question.options[0].id],
            otherText: null,
          })),
        },
      });
    }
    const current = (await api(baseUrl, "/v1/work/sessions")).sessions.find((item) => item.id === session.id);
    const complete = events.some((event) => event.type === "REPORT" && event.payload.text.includes("PHONE_LINE_REPORT_OK"))
      && events.some((event) => event.type === "ASK_ANSWERED")
      && events.some((event) => event.type === "HTML_REPORT" && event.payload.title.includes("PHONE_LINE_HTML_OK"))
      && events.some((event) => event.type === "ASSISTANT_MESSAGE")
      && current?.status === "IDLE";
    return complete ? { events, current } : null;
  }, 300_000, 500);

  assert.equal(result.current.status, "IDLE");
  assert.ok(result.current.codexSessionId);
  console.log(`Real Codex phone-line E2E passed for ${result.current.codexSessionId}`);
} finally {
  runner?.stop();
  await Promise.race([runnerPromise?.catch(() => {}), delay(3_000)]);
  if (server.listening) await new Promise((resolve) => server.close(resolve));
  store.close();
  rmSync(root, { recursive: true, force: true });
}

async function api(baseUrl, path, { method = "GET", body, idempotencyKey } = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: {
      ...PROTOCOL_HEADERS,
      authorization: `Bearer ${USER_TOKEN}`,
      ...(body ? { "content-type": "application/json" } : {}),
      ...(idempotencyKey ? { "idempotency-key": idempotencyKey } : {}),
    },
    body: body ? JSON.stringify(body) : undefined,
  });
  const payload = await response.json().catch(() => ({}));
  if (!response.ok) throw new Error(payload.message ?? `HTTP ${response.status}`);
  return payload;
}

async function waitFor(probe, timeoutMs = 20_000, intervalMs = 100) {
  const deadline = Date.now() + timeoutMs;
  let lastError;
  while (Date.now() < deadline) {
    try {
      const value = await probe();
      if (value) return value;
    } catch (error) {
      lastError = error;
    }
    await delay(intervalMs);
  }
  throw lastError ?? new Error(`Timed out after ${timeoutMs}ms`);
}

function delay(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}
