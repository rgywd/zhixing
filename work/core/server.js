import { createServer } from "node:http";
import sanitizeHtml from "sanitize-html";

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

function sanitizeReport(html, title) {
  const body = sanitizeHtml(html, {
    allowedTags: [
      "h1", "h2", "h3", "h4", "p", "br", "hr", "strong", "em", "s", "blockquote",
      "ul", "ol", "li", "table", "thead", "tbody", "tr", "th", "td", "pre", "code", "details", "summary",
    ],
    allowedAttributes: {},
    disallowedTagsMode: "discard",
    enforceHtmlBoundary: true,
  });
  const safeTitle = sanitizeHtml(title, { allowedTags: [], allowedAttributes: {} });
  return `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:"><title>${safeTitle}</title><style>body{font:16px/1.65 system-ui,sans-serif;max-width:760px;margin:auto;padding:24px;color:#202124;background:#fff}pre{white-space:pre-wrap;background:#f4f4f5;padding:12px;border-radius:8px;overflow:auto}table{border-collapse:collapse;width:100%}td,th{border:1px solid #ddd;padding:8px}@media(prefers-color-scheme:dark){body{color:#e8eaed;background:#111318}pre{background:#202124}}</style></head><body>${body}</body></html>`;
}

export function createWorkServer({ store, askTimeoutMs = 180_000 }) {
  const waitingAsks = new Set();
  const server = createServer(async (request, response) => {
    try {
      const url = new URL(request.url, "http://localhost");
      if (url.pathname === "/healthz") return sendJson(response, 200, { ok: true, protocol: 1 });
      requireProtocol(request);

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
      if (request.method === "POST" && url.pathname === "/v1/work/sessions") {
        requireUser(store, request);
        return sendJson(response, 201, store.createSession(await readJson(request), request.headers["idempotency-key"]));
      }
      if (request.method === "GET" && url.pathname === "/v1/work/sessions") {
        requireUser(store, request);
        return sendJson(response, 200, { sessions: store.listSessions() });
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
          !waitingAsks.has(match[2]),
        ));
      }
      match = url.pathname.match(/^\/v1\/work\/sessions\/([^/]+)\/(stop|complete)$/);
      if (request.method === "POST" && match) {
        requireUser(store, request);
        return sendJson(response, 202, match[2] === "stop" ? store.stopSession(match[1]) : store.completeSession(match[1]));
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
        const ask = store.createAsk(match[1], input);
        waitingAsks.add(ask.id);
        const answer = await waitForAnswer(store, ask.id, askTimeoutMs, request.signal);
        waitingAsks.delete(ask.id);
        if (!answer) {
          store.timeoutAsk(ask.id);
          return sendJson(response, 200, {
            status: "timeout",
            questionSetId: ask.id,
            message: `No answer within ${Math.round(askTimeoutMs / 1000)} seconds; stop or continue with safe assumptions.`,
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

function waitForAnswer(store, askId, timeoutMs, signal) {
  return new Promise((resolve) => {
    const deadline = Date.now() + timeoutMs;
    const timer = setInterval(() => {
      const ask = store.getAsk(askId);
      if (ask?.status === "ANSWERED") finish(ask);
      else if (Date.now() >= deadline || signal?.aborted) finish(null);
    }, Math.min(100, Math.max(10, timeoutMs / 10)));
    const finish = (value) => {
      clearInterval(timer);
      resolve(value);
    };
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
