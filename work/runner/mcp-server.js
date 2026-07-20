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
  description: "在关键决策点向手机发 1-4 道结构化选择题，短挂最多三分钟等待回答。每题客户端都提供其他自由输入。",
  inputSchema: { questions: z.array(questionSchema).min(1).max(4) },
}, async ({ questions }) => {
  acknowledgePendingCursor();
  const result = await post("ask", { questions, clientCallId: randomUUID(), inboxAfter: acknowledgedCursor });
  stageCursor(result.nextInboxCursor);
  return toolResult(result);
});

server.registerTool("report_html", {
  title: "发送长报告",
  description: "把大段结构化结果作为 HTML 报告卡发到手机。服务端会清洗并套用只读模板。",
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
