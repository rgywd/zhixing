import { createServer } from "node:http";
import { createReadStream } from "node:fs";
import sanitizeHtml from "sanitize-html";
import { inspectAttachment, MAX_ATTACHMENT_BYTES } from "../attachments.js";

const PROTOCOL_VERSION = "1";

function sendJson(response, statusCode, body, headers = {}) {
  response.writeHead(statusCode, {
    "content-type": "application/json; charset=utf-8",
    "cache-control": "no-store",
    ...headers,
  });
  response.end(JSON.stringify(body));
}

async function readJson(request, limit = 1024 * 1024 + 4096) {
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > limit) throw Object.assign(new Error("Request body is too large"), { statusCode: 413 });
    chunks.push(chunk);
  }
  if (!chunks.length) return {};
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw Object.assign(new Error("Request body must be valid JSON"), { statusCode: 400 });
  }
}

async function readBytes(request, limit) {
  const chunks = [];
  let length = 0;
  for await (const chunk of request) {
    length += chunk.length;
    if (length > limit) throw Object.assign(new Error("Request body is too large"), { statusCode: 413 });
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

function bearer(request) {
  const value = request.headers.authorization ?? "";
  return value.startsWith("Bearer ") ? value.slice(7) : null;
}

function requireProtocol(request) {
  if (request.headers["x-zhixing-work-protocol"] !== PROTOCOL_VERSION) {
    throw Object.assign(new Error("Unsupported Work protocol version"), { statusCode: 426 });
  }
}

function requireUser(store, request) {
  if (!store.authenticateUser(bearer(request))) throw Object.assign(new Error("Unauthorized"), { statusCode: 401 });
}

function requireRunner(store, request, runnerId) {
  if (!runnerId || !store.authenticateRunner(bearer(request), runnerId)) {
    throw Object.assign(new Error("Unauthorized"), { statusCode: 401 });
  }
}

function requireSession(store, request, sessionId) {
  if (!store.verifySessionToken(bearer(request), sessionId)) throw Object.assign(new Error("Unauthorized"), { statusCode: 401 });
}

const REPORT_STYLE = `
:root{color-scheme:light dark}
body{font:16px/1.7 system-ui,-apple-system,"Segoe UI",sans-serif;max-width:760px;margin:auto;padding:20px 16px 48px;color:#1f2328;background:#fff;overflow-wrap:break-word}
h1{font-size:1.5em}h2{font-size:1.25em;margin-top:1.6em;border-bottom:1px solid #e3e6ea;padding-bottom:.3em}h3{font-size:1.1em}h4{font-size:1em}
h1,h2,h3,h4{line-height:1.35}
a{color:#0969da;text-decoration:none}
blockquote{margin:1em 0;padding:.4em 1em;border-left:4px solid #d0d7de;color:#57606a}
pre{white-space:pre-wrap;word-break:break-word;background:#f4f4f5;padding:12px;border-radius:8px;overflow:auto;font-size:.88em}
code{font-family:ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;font-size:.92em}
p code,li code,td code{background:#eff1f3;padding:.15em .35em;border-radius:4px}
table{border-collapse:collapse;margin:1em 0;font-size:.92em}
thead th{background:#f4f4f5}
td,th{border:1px solid #d8dce1;padding:6px 10px;text-align:left}
details{margin:1em 0;border:1px solid #d8dce1;border-radius:8px;padding:8px 12px}
summary{font-weight:600;cursor:pointer}
hr{border:none;border-top:1px solid #d8dce1;margin:2em 0}
img{max-width:100%;height:auto;border-radius:8px}
figure{margin:1em 0}
figcaption{font-size:.85em;color:#57606a;text-align:center}
mark{background:#fff3bf;padding:0 .2em;border-radius:2px}
@media(max-width:600px){table{display:block;overflow-x:auto}}
@media(prefers-color-scheme:dark){
body{color:#e6e8eb;background:#111318}
a{color:#58a6ff}
blockquote{border-left-color:#3d434b;color:#9aa1a9}
pre{background:#1c1f24}
p code,li code,td code{background:#26292f}
thead th{background:#1c1f24}
td,th{border-color:#31363d}
h2{border-bottom-color:#2b2f35}
details{border-color:#31363d}
hr{border-top-color:#31363d}
figcaption{color:#9aa1a9}
mark{background:#5a4a12;color:#e6e8eb}
}`;

function sanitizeReport(html, title) {
  const body = sanitizeHtml(html, {
    allowedTags: [
      "h1", "h2", "h3", "h4", "p", "br", "hr", "strong", "em", "s", "blockquote",
      "ul", "ol", "li", "table", "thead", "tbody", "tr", "th", "td", "pre", "code", "details", "summary",
      "a", "img", "figure", "figcaption", "mark",
    ],
    allowedAttributes: {
      a: ["href"],
      img: ["src", "alt", "title"],
    },
    allowedSchemes: ["https", "http", "mailto"],
    allowedSchemesByTag: { img: ["data"] },
    disallowedTagsMode: "discard",
    enforceHtmlBoundary: true,
  });
  const safeTitle = sanitizeHtml(title, { allowedTags: [], allowedAttributes: {} });
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:"><title>${safeTitle}</title><style>${REPORT_STYLE}</style></head><body>${body}</body></html>`;
}

export function createWorkServer({
  store,
  askTimeoutMs = 180_000,
  quotaProxy = null,
  informationMonitorProxy = null,
}) {
  const server = createServer(async (request, response) => {
    try {
      const url = new URL(request.url, "http://localhost");
      if (url.pathname === "/healthz") return sendJson(response, 200, { ok: true, protocol: 1 });
      requireProtocol(request);

      if (request.method === "GET" && url.pathname === "/v1/life/quotas") {
        requireUser(store, request);
        if (!quotaProxy) {
          throw Object.assign(new Error("套餐余量服务尚未配置"), { statusCode: 503 });
        }
        return sendJson(response, 200, await quotaProxy.getQuotas());
      }
      const informationMonitorMatch = url.pathname.match(/^\/v1\/life\/inbox\/(status|items|digest)$/);
      if (request.method === "GET" && informationMonitorMatch) {
        requireUser(store, request);
        if (!informationMonitorProxy) {
          throw Object.assign(new Error("信息监控服务尚未配置"), { statusCode: 503 });
        }
        const result = await informationMonitorProxy.get(informationMonitorMatch[1], url.searchParams);
        const headers = {
          "x-zhixing-life-cache": result.isStale ? "stale" : "fresh",
          ...(result.isStale ? {
            warning: '110 - "Response is stale"',
            "x-zhixing-life-error": result.errorCode,
          } : {}),
        };
        return sendJson(response, 200, result.body, headers);
      }

      if (request.method === "POST" && url.pathname === "/v1/runner/register") {
        const input = await readJson(request);
        requireRunner(store, request, input.id);
        return sendJson(response, 200, store.registerRunner(input));
      }
      if (request.method === "POST" && url.pathname === "/v1/runner/heartbeat") {
        const input = await readJson(request);
        requireRunner(store, request, input.runnerId);
        return sendJson(response, 200, store.heartbeatRunner(input.runnerId, input.instanceId));
      }
      if (request.method === "GET" && url.pathname === "/v1/runner/commands") {
        const runnerId = url.searchParams.get("runnerId");
        const instanceId = url.searchParams.get("instanceId");
        if (!runnerId) throw Object.assign(new Error("runnerId is required"), { statusCode: 400 });
        requireRunner(store, request, runnerId);
        return sendJson(response, 200, { commands: store.listCommands(runnerId, instanceId, url.searchParams.get("after") ?? "") });
      }
      let match = url.pathname.match(/^\/v1\/runner\/commands\/([^/]+)\/ack$/);
      if (request.method === "POST" && match) {
        const runnerId = store.commandRunnerId(match[1]);
        requireRunner(store, request, runnerId);
        const input = await readJson(request);
        if (!store.isRunnerInstance(runnerId, input.instanceId)) throw Object.assign(new Error("Runner instance is stale"), { statusCode: 409 });
        return sendJson(response, 200, store.ackCommand(match[1], input, runnerId, input.instanceId));
      }
      match = url.pathname.match(/^\/v1\/runner\/sessions\/([^/]+)\/state$/);
      if (request.method === "POST" && match) {
        const runnerId = store.sessionRunnerId(match[1]);
        requireRunner(store, request, runnerId);
        const input = await readJson(request);
        if (!store.isRunnerInstance(runnerId, input.instanceId)) throw Object.assign(new Error("Runner instance is stale"), { statusCode: 409 });
        return sendJson(response, 200, store.updateSessionState(match[1], input));
      }
      match = url.pathname.match(/^\/v1\/runner\/sessions\/([^/]+)\/events$/);
      if (request.method === "POST" && match) {
        const runnerId = store.sessionRunnerId(match[1]);
        requireRunner(store, request, runnerId);
        const input = await readJson(request);
        if (!store.isRunnerInstance(runnerId, input.instanceId)) throw Object.assign(new Error("Runner instance is stale"), { statusCode: 409 });
        return sendJson(response, 201, store.appendRunnerEvent(match[1], input, runnerId));
      }
      match = url.pathname.match(/^\/v1\/runner\/attachments\/([^/]+)$/);
      if (request.method === "GET" && match) {
        const runnerId = url.searchParams.get("runnerId");
        requireRunner(store, request, runnerId);
        const attachment = store.getAttachmentForRunner(match[1], runnerId);
        if (!attachment) throw Object.assign(new Error("Attachment not found"), { statusCode: 404 });
        response.writeHead(200, {
          "content-type": attachment.mimeType,
          "content-length": attachment.size,
          "x-content-sha256": attachment.sha256,
          "cache-control": "private, no-store",
          "x-content-type-options": "nosniff",
        });
        if (attachment.data) return response.end(attachment.data);
        const stream = createReadStream(attachment.filePath);
        stream.on("error", () => response.destroy());
        stream.pipe(response);
        return;
      }

      if (request.method === "GET" && url.pathname === "/v1/work/runners") {
        requireUser(store, request);
        return sendJson(response, 200, { runners: store.listRunners() });
      }
      if (request.method === "GET" && url.pathname === "/v1/work/repos") {
        requireUser(store, request);
        const runnerId = url.searchParams.get("runnerId");
        if (!runnerId) throw Object.assign(new Error("runnerId is required"), { statusCode: 400 });
        const repos = store.listRepos(runnerId);
        const etag = `W/\"${Buffer.from(JSON.stringify(repos)).toString("base64url").slice(0, 24)}\"`;
        if (request.headers["if-none-match"] === etag) {
          response.writeHead(304, { etag });
          return response.end();
        }
        return sendJson(response, 200, { repos }, { etag });
      }
      if (request.method === "POST" && url.pathname === "/v1/work/attachments") {
        requireUser(store, request);
        const mimeType = String(request.headers["content-type"] ?? "application/octet-stream");
        const data = await readBytes(request, MAX_ATTACHMENT_BYTES);
        let fileName = "attachment";
        try {
          fileName = decodeURIComponent(String(request.headers["x-file-name"] ?? "attachment"));
        } catch {
          throw Object.assign(new Error("Invalid attachment file name"), { statusCode: 400 });
        }
        const inspected = inspectAttachment({ fileName, mimeType, data });
        return sendJson(response, 201, store.createAttachment({
          ...inspected,
          data,
          storeOnDisk: inspected.kind === "file",
        }));
      }
      if (request.method === "POST" && url.pathname === "/v1/work/sessions") {
        requireUser(store, request);
        return sendJson(response, 201, store.createSession(await readJson(request), request.headers["idempotency-key"]));
      }
      if (request.method === "GET" && url.pathname === "/v1/work/sessions") {
        requireUser(store, request);
        return sendJson(response, 200, { sessions: store.listSessions({ archived: url.searchParams.get("archived") === "true" }) });
      }
      match = url.pathname.match(/^\/v1\/work\/sessions\/([^/]+)\/events$/);
      if (request.method === "GET" && match) {
        requireUser(store, request);
        return sendJson(response, 200, { events: store.getEvents(match[1], url.searchParams.get("afterSeq") ?? 0) });
      }
      match = url.pathname.match(/^\/v1\/work\/sessions\/([^/]+)\/stream$/);
      if (request.method === "GET" && match) {
        requireUser(store, request);
        return streamEvents(store, request, response, match[1], Number(url.searchParams.get("afterSeq") ?? 0));
      }
      match = url.pathname.match(/^\/v1\/work\/sessions\/([^/]+)\/messages$/);
      if (request.method === "POST" && match) {
        requireUser(store, request);
        return sendJson(response, 202, store.postUserMessage(match[1], await readJson(request), request.headers["idempotency-key"]));
      }
      match = url.pathname.match(/^\/v1\/work\/sessions\/([^/]+)\/asks\/([^/]+)\/answer$/);
      if (request.method === "POST" && match) {
        requireUser(store, request);
        return sendJson(response, 200, store.answerAsk(
          match[1],
          match[2],
          await readJson(request),
          request.headers["idempotency-key"],
        ));
      }
      match = url.pathname.match(/^\/v1\/work\/sessions\/([^/]+)\/(stop|complete)$/);
      if (request.method === "POST" && match) {
        requireUser(store, request);
        return sendJson(response, 202, match[2] === "stop" ? store.stopSession(match[1]) : store.completeSession(match[1]));
      }
      match = url.pathname.match(/^\/v1\/work\/sessions\/([^/]+)\/(archive|unarchive)$/);
      if (request.method === "POST" && match) {
        requireUser(store, request);
        return sendJson(response, 200, store.setSessionArchived(match[1], match[2] === "archive"));
      }
      match = url.pathname.match(/^\/v1\/work\/sessions\/([^/]+)\/revoke-tokens$/);
      if (request.method === "POST" && match) {
        requireUser(store, request);
        return sendJson(response, 200, store.revokeSessionTokens(match[1]));
      }
      match = url.pathname.match(/^\/v1\/work\/reports\/([^/]+)$/);
      if (request.method === "GET" && match) {
        requireUser(store, request);
        const report = store.getReport(match[1]);
        if (!report) throw Object.assign(new Error("Report not found"), { statusCode: 404 });
        response.writeHead(200, {
          "content-type": "text/html; charset=utf-8",
          "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; img-src data:",
          "x-content-type-options": "nosniff",
          "cache-control": "private, no-store",
        });
        return response.end(report.html);
      }

      match = url.pathname.match(/^\/v1\/mcp\/sessions\/([^/]+)\/report$/);
      if (request.method === "POST" && match) {
        requireSession(store, request, match[1]);
        return sendJson(response, 200, store.report(match[1], await readJson(request)));
      }
      match = url.pathname.match(/^\/v1\/mcp\/sessions\/([^/]+)\/ask$/);
      if (request.method === "POST" && match) {
        requireSession(store, request, match[1]);
        const input = await readJson(request);
        const ask = store.createAsk(match[1], input, askTimeoutMs);
        const answer = await waitForAnswer(store, ask.id, askTimeoutMs, response);
        if (!answer) {
          const settled = store.timeoutAsk(ask.id, "RUNNING");
          return sendJson(response, 200, {
            status: "auto_answered",
            answers: settled.answers,
            answeredAt: settled.answeredAt,
            message: `No answer within ${Math.round(askTimeoutMs / 1000)} seconds; the recommended options were applied. Mention that the user did not explicitly confirm if it matters.`,
            ...store.readInbox(match[1], input.inboxAfter ?? 0),
          });
        }
        return sendJson(response, 200, {
          status: "answered",
          answers: answer.answers,
          answeredAt: answer.answeredAt,
          ...store.readInbox(match[1], input.inboxAfter ?? 0),
        });
      }
      match = url.pathname.match(/^\/v1\/mcp\/sessions\/([^/]+)\/report-html$/);
      if (request.method === "POST" && match) {
        requireSession(store, request, match[1]);
        const input = await readJson(request);
        return sendJson(response, 200, store.reportHtml(match[1], input, sanitizeReport(input.html, input.title)));
      }

      throw Object.assign(new Error("Not found"), { statusCode: 404 });
    } catch (error) {
      if (!response.headersSent) {
        sendJson(response, error.statusCode ?? 500, {
          error: error.statusCode ? "request_failed" : "internal_error",
          message: error.statusCode ? error.message : "Internal server error",
        });
      } else {
        response.end();
      }
    }
  });
  return server;
}

function waitForAnswer(store, askId, timeoutMs, response) {
  return new Promise((resolve) => {
    const deadline = Date.now() + timeoutMs;
    const timer = setInterval(() => {
      const ask = store.getAsk(askId);
      if (ask?.status === "ANSWERED") finish(ask);
      else if (Date.now() >= deadline) finish(null);
    }, Math.min(100, Math.max(10, timeoutMs / 10)));
    const onClose = () => finish(null);
    const finish = (value) => {
      clearInterval(timer);
      response.off("close", onClose);
      resolve(value);
    };
    response.once("close", onClose);
    if (response.destroyed) finish(null);
  });
}

function streamEvents(store, request, response, sessionId, afterSeq) {
  store.getEvents(sessionId, afterSeq);
  response.writeHead(200, {
    "content-type": "text/event-stream",
    "cache-control": "no-cache, no-transform",
    connection: "keep-alive",
  });
  let cursor = afterSeq;
  const writeNew = () => {
    for (const event of store.getEvents(sessionId, cursor)) {
      response.write(`id: ${event.seq}\nevent: ${event.type}\ndata: ${JSON.stringify(event)}\n\n`);
      cursor = event.seq;
    }
  };
  writeNew();
  const poll = setInterval(writeNew, 500);
  const heartbeat = setInterval(() => response.write(": heartbeat\n\n"), 15_000);
  request.on("close", () => {
    clearInterval(poll);
    clearInterval(heartbeat);
  });
}
