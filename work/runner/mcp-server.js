#!/usr/bin/env node
import { randomUUID } from "node:crypto";
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const coreUrl = required("WORK_CORE_URL").replace(/\/$/, "");
const sessionId = required("WORK_SESSION_ID");
const token = required("WORK_SESSION_TOKEN");
const cursorFile = required("WORK_CURSOR_FILE");
const storedCursor = readCursor(cursorFile);
let acknowledgedCursor = Math.max(storedCursor.acknowledgedCursor, Number(process.env.WORK_INITIAL_INBOX_CURSOR ?? 0));
let pendingCursor = Math.max(storedCursor.pendingCursor, acknowledgedCursor);
let canAcknowledgePending = pendingCursor === acknowledgedCursor;

const questionSchema = z.object({
  id: z.string().min(1),
  header: z.string().min(1).max(40),
  question: z.string().min(1).max(500),
  multiSelect: z.boolean().default(false),
  options: z.array(z.object({
    id: z.string().min(1),
    label: z.string().min(1).max(80),
    description: z.string().max(240).optional(),
  })).min(1).max(8),
  recommendedOptionIds: z.array(z.string().min(1)).min(1)
    .describe("你真诚判断的最优选项（单选恰好 1 个，多选 1 个或多个）。手机端预填为可一键确认的默认选择；3 分钟未回答时自动采用并继续。"),
});

const server = new McpServer({ name: "zhixing-phone-line", version: "1.0.0" });

server.registerTool("report", {
  title: "即时汇报",
  description: "向手机即时汇报一条 Markdown 消息。工具会立即返回，并捎回用户在你工作期间积压的补充消息。",
  inputSchema: { text: z.string().min(1).max(20_000) },
}, async ({ text }) => {
  acknowledgePendingCursor();
  const result = await post("report", {
    text,
    clientCallId: randomUUID(),
    inboxAfter: acknowledgedCursor,
  });
  stageCursor(result.nextInboxCursor);
  return toolResult(result);
});

server.registerTool("ask", {
  title: "向用户提问",
  description: "在用户的偏好会改变你做法的决策点，向手机发 1-4 道结构化选择题。每题必须带 recommendedOptionIds（你真诚判断的最优选项），手机端预填推荐、可一键确认或改选，每题也提供其他自由输入。最多等待 3 分钟；未回答时自动采用推荐答案返回（status=auto_answered），你永远不会被卡住。能自己安全决定的事不要问。",
  inputSchema: { questions: z.array(questionSchema).min(1).max(4) },
}, async ({ questions }) => {
  acknowledgePendingCursor();
  const result = await post("ask", { questions, clientCallId: randomUUID(), inboxAfter: acknowledgedCursor });
  stageCursor(result.nextInboxCursor);
  return toolResult(result);
});

server.registerTool("report_html", {
  title: "发送长报告",
  description: "把大段结构化结果作为 HTML 报告卡发到手机，手机端在只读 WebView 中打开。服务端会清洗 HTML 并套用内置只读模板：仅保留 h1-h4、p、br、hr、strong、em、s、blockquote、ul/ol/li、table/thead/tbody/tr/th/td、pre、code、details、summary、a、img、figure、figcaption、mark 这些语义标签；所有 class、style、script、div/span 布局一律丢弃。a 的 href 仅允许 http/https/mailto，img 的 src 仅允许 data: 内联图片。模板自带排版与深色模式，请直接写简洁的语义化 HTML，不要写任何 CSS 或外部资源。",
  inputSchema: {
    title: z.string().min(1).max(120),
    html: z.string().min(1).max(1024 * 1024),
  },
}, async ({ title, html }) => {
  acknowledgePendingCursor();
  return toolResult(await post("report-html", {
    title,
    html,
    clientCallId: randomUUID(),
  }));
});

const transport = new StdioServerTransport();
await server.connect(transport);

function required(name) {
  const value = process.env[name];
  if (!value) throw new Error(`${name} is required`);
  return value;
}

async function post(path, body) {
  let lastError;
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      const response = await fetch(`${coreUrl}/v1/mcp/sessions/${encodeURIComponent(sessionId)}/${path}`, {
        method: "POST",
        signal: AbortSignal.timeout(200_000),
        headers: {
          "x-zhixing-work-protocol": "1",
          authorization: `Bearer ${token}`,
          "content-type": "application/json",
        },
        body: JSON.stringify(body),
      });
      const payload = await response.json().catch(() => ({}));
      if (!response.ok) throw new Error(payload.message ?? `Work Core returned ${response.status}`);
      return payload;
    } catch (error) {
      lastError = error;
      if (attempt < 2) await new Promise((resolve) => setTimeout(resolve, 500 * (2 ** attempt)));
    }
  }
  throw lastError;
}

function toolResult(value) {
  return {
    content: [{ type: "text", text: JSON.stringify(value, null, 2) }],
    structuredContent: value,
  };
}

function readCursor(filename) {
  try {
    if (!existsSync(filename)) return { acknowledgedCursor: 0, pendingCursor: 0 };
    const value = JSON.parse(readFileSync(filename, "utf8"));
    const acknowledgedCursor = Number(value.acknowledgedCursor ?? value.inboxCursor ?? 0);
    return { acknowledgedCursor, pendingCursor: Number(value.pendingCursor ?? acknowledgedCursor) };
  } catch {
    return { acknowledgedCursor: 0, pendingCursor: 0 };
  }
}

function acknowledgePendingCursor() {
  if (!canAcknowledgePending) {
    canAcknowledgePending = true;
    return;
  }
  if (pendingCursor <= acknowledgedCursor) return;
  acknowledgedCursor = pendingCursor;
  persistCursor();
}

function stageCursor(value) {
  if (!Number.isFinite(Number(value)) || Number(value) <= pendingCursor) return;
  pendingCursor = Number(value);
  persistCursor();
}

function persistCursor() {
  mkdirSync(dirname(cursorFile), { recursive: true });
  const temporary = `${cursorFile}.tmp`;
  writeFileSync(temporary, `${JSON.stringify({ acknowledgedCursor, pendingCursor })}\n`, { mode: 0o600 });
  renameSync(temporary, cursorFile);
}
