import { z } from "zod";

const SCHEMA_VERSION = "information-monitor/v1";
const ACTIONS = new Set(["status", "items", "digest"]);
const CHANNELS = new Set(["email", "feishu"]);
const IMPORTANCE = new Set(["low", "normal", "high", "urgent"]);
const FORBIDDEN_KEYS = new Set([
  "accesstoken",
  "appsecret",
  "attachment",
  "attachments",
  "authorization",
  "body",
  "clientsecret",
  "content",
  "cookie",
  "credentials",
  "cursor",
  "historyid",
  "html",
  "messageid",
  "mime",
  "password",
  "payload",
  "provideritemid",
  "raw",
  "refreshtoken",
  "secret",
  "secrets",
  "token",
  "uid",
  "uidvalidity",
]);
const QUERY_KEYS = ["channel", "hours", "limit", "minImportance"];
const ACTION_QUERY_KEYS = {
  status: new Set(["channel"]),
  items: new Set(QUERY_KEYS),
  digest: new Set(["channel", "hours", "minImportance"]),
};

const utcInstantSchema = z.string().datetime({ offset: false });
const countSchema = z.number().int().nonnegative().max(2_147_483_647);
const safeTextSchema = (maximum) => z.string()
  .max(maximum)
  .refine((value) => value.trim().length > 0 && !value.includes("\u0000"));
const optionalTextSchema = (maximum) => safeTextSchema(maximum).nullable().optional();
const errorCodeSchema = z.string()
  .min(1)
  .max(120)
  .regex(/^[a-zA-Z0-9._-]+$/)
  .refine((value) => !value.includes("\u0000"));
const sourceSchema = z.object({
  sourceLabel: safeTextSchema(120),
  kind: z.enum(["email", "feishu"]),
  state: z.enum(["ok", "degraded", "unavailable", "disabled"]),
  lastSucceededAt: utcInstantSchema.nullable().optional(),
  lastErrorCode: errorCodeSchema.nullable().optional(),
  itemCount24h: countSchema,
}).strip();

const itemSchema = z.object({
  id: safeTextSchema(200),
  sourceLabel: safeTextSchema(120),
  channel: z.enum(["email", "feishu"]),
  occurredAt: utcInstantSchema,
  sender: optionalTextSchema(500),
  title: optionalTextSchema(500),
  summary: safeTextSchema(4_000),
  actionItems: z.array(safeTextSchema(1_000)).max(20),
  importance: z.enum(["low", "normal", "high", "urgent"]),
}).strip();

const responseSchemas = {
  status: z.object({
    schema: z.literal(SCHEMA_VERSION),
    sources: z.array(sourceSchema).max(100),
  }).strip(),
  items: z.object({
    schema: z.literal(SCHEMA_VERSION),
    items: z.array(itemSchema).max(50),
  }).strip(),
  digest: z.object({
    schema: z.literal(SCHEMA_VERSION),
    total: countSchema,
    highPriority: countSchema,
    channels: z.array(z.object({
      channel: z.enum(["email", "feishu"]),
      count: countSchema,
      topItems: z.array(itemSchema).max(50),
    }).strip()).max(2),
  }).strip().superRefine((value, context) => {
    if (value.highPriority > value.total) {
      context.addIssue({ code: z.ZodIssueCode.custom, message: "highPriority exceeds total" });
    }
    if (new Set(value.channels.map((channel) => channel.channel)).size !== value.channels.length) {
      context.addIssue({ code: z.ZodIssueCode.custom, message: "digest channels must be unique" });
    }
    if (value.channels.reduce((sum, channel) => sum + channel.count, 0) !== value.total) {
      context.addIssue({ code: z.ZodIssueCode.custom, message: "digest channel counts do not match total" });
    }
    for (const group of value.channels) {
      if (group.topItems.length > group.count) {
        context.addIssue({ code: z.ZodIssueCode.custom, message: "digest top items exceed channel count" });
      }
      if (group.topItems.some((item) => item.channel !== group.channel)) {
        context.addIssue({ code: z.ZodIssueCode.custom, message: "digest item channel mismatch" });
      }
    }
  }),
};

export function validateInformationMonitorResponse(action, value) {
  const schema = responseSchemas[action];
  if (!schema) throw monitorError("unsupported_action", "不支持的信息监控操作", 500);
  if (containsForbiddenKey(value)) {
    throw monitorError("invalid_upstream_response", "信息监控服务返回了不允许的数据", 502);
  }
  return schema.parse(value);
}

export function parseInformationMonitorQuery(
  searchParams = new URLSearchParams(),
  allowedKeys = new Set(QUERY_KEYS),
) {
  for (const key of searchParams.keys()) {
    if (!allowedKeys.has(key)) {
      throw monitorError("invalid_query", "包含不支持的查询参数", 400);
    }
    if (searchParams.getAll(key).length !== 1) {
      throw monitorError("invalid_query", `查询参数 ${key} 不能重复`, 400);
    }
  }

  const normalized = new URLSearchParams();
  const channel = searchParams.get("channel");
  if (channel !== null) {
    if (!CHANNELS.has(channel)) throw monitorError("invalid_query", "channel 必须是 email 或 feishu", 400);
    normalized.set("channel", channel);
  }
  const hours = parseBoundedInteger(searchParams.get("hours"), "hours", 1, 168);
  if (hours !== null) normalized.set("hours", String(hours));
  const limit = parseBoundedInteger(searchParams.get("limit"), "limit", 1, 50);
  if (limit !== null) normalized.set("limit", String(limit));
  const minImportance = searchParams.get("minImportance");
  if (minImportance !== null) {
    if (!IMPORTANCE.has(minImportance)) {
      throw monitorError("invalid_query", "minImportance 必须是 low、normal、high 或 urgent", 400);
    }
    normalized.set("minImportance", minImportance);
  }
  return normalized;
}

export function createInformationMonitorProxy({
  baseUrl,
  token,
  fetchImpl = globalThis.fetch,
  cacheTtlMs = 30_000,
  maxStaleMs = 5 * 60_000,
  timeoutMs = 15_000,
  maxResponseBytes = 512 * 1024,
  maxCacheEntries = 32,
  now = () => Date.now(),
} = {}) {
  const endpoint = normalizeEndpoint(baseUrl);
  const cache = new Map();
  const inFlight = new Map();

  async function get(action, searchParams) {
    if (!ACTIONS.has(action)) throw monitorError("unsupported_action", "不支持的信息监控操作", 500);
    const query = parseInformationMonitorQuery(searchParams, ACTION_QUERY_KEYS[action]);
    const queryString = query.toString();
    const key = `${action}?${queryString}`;
    const cached = readCacheEntry(cache, key);
    const timestamp = now();
    if (cached && timestamp - cached.fetchedAt <= cacheTtlMs) {
      return { body: cached.body, isStale: false, errorCode: null };
    }
    if (inFlight.has(key)) return inFlight.get(key);
    const pending = load({ action, queryString, key, timestamp }).finally(() => inFlight.delete(key));
    inFlight.set(key, pending);
    return pending;
  }

  async function load({ action, queryString, key, timestamp }) {
    const cached = readCacheEntry(cache, key);
    if (!endpoint || !token) {
      return fallbackOrThrow(cached, timestamp, maxStaleMs, "not_configured", "信息监控服务尚未配置", 503);
    }

    try {
      const suffix = queryString ? `?${queryString}` : "";
      const response = await fetchImpl(`${endpoint}/api/v1/monitor/${action}${suffix}`, {
        method: "GET",
        redirect: "error",
        headers: {
          accept: "application/json",
          authorization: `Bearer ${token}`,
        },
        signal: AbortSignal.timeout(timeoutMs),
      });
      if (!response.ok) {
        throw monitorError("upstream_http_error", `信息监控服务返回 HTTP ${response.status}`, 502);
      }
      const contentType = response.headers.get("content-type") ?? "";
      if (!isJsonContentType(contentType)) {
        throw monitorError("invalid_upstream_response", "信息监控服务返回了无效数据", 502);
      }
      const body = validateInformationMonitorResponse(
        action,
        await readJsonWithLimit(response, maxResponseBytes),
      );
      writeCacheEntry(cache, key, { body, fetchedAt: timestamp }, maxCacheEntries);
      return { body, isStale: false, errorCode: null };
    } catch (error) {
      const safe = toSafeError(error);
      return fallbackOrThrow(cached, timestamp, maxStaleMs, safe.code, safe.message, safe.statusCode);
    }
  }

  return { get };
}

function parseBoundedInteger(value, name, minimum, maximum) {
  if (value === null) return null;
  if (!/^(0|[1-9]\d*)$/.test(value)) {
    throw monitorError("invalid_query", `${name} 必须是整数`, 400);
  }
  const parsed = Number(value);
  if (!Number.isSafeInteger(parsed) || parsed < minimum || parsed > maximum) {
    throw monitorError("invalid_query", `${name} 必须在 ${minimum} 到 ${maximum} 之间`, 400);
  }
  return parsed;
}

function containsForbiddenKey(value, seen = new WeakSet()) {
  if (!value || typeof value !== "object") return false;
  if (seen.has(value)) return false;
  seen.add(value);
  for (const [key, nested] of Object.entries(value)) {
    const normalized = key.replaceAll("_", "").replaceAll("-", "").toLowerCase();
    if (FORBIDDEN_KEYS.has(normalized) || containsForbiddenKey(nested, seen)) return true;
  }
  return false;
}

function normalizeEndpoint(value) {
  if (!value) return null;
  try {
    const url = new URL(value);
    if (!new Set(["http:", "https:"]).has(url.protocol)) return null;
    if (url.protocol === "http:" && !isLoopbackHost(url.hostname)) return null;
    if (url.username || url.password || url.search || url.hash) return null;
    url.pathname = url.pathname.replace(/\/+$/, "");
    return url.toString().replace(/\/$/, "");
  } catch {
    return null;
  }
}

function isLoopbackHost(hostname) {
  const normalized = hostname.toLowerCase().replace(/\.$/, "");
  if (normalized === "localhost" || normalized === "[::1]" || normalized === "::1") return true;
  const match = normalized.match(/^127\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/);
  return Boolean(match && match.slice(1).every((part) => Number(part) <= 255));
}

function isJsonContentType(value) {
  const mediaType = value.split(";", 1)[0].trim().toLowerCase();
  return mediaType === "application/json" || mediaType.endsWith("+json");
}

async function readJsonWithLimit(response, maxBytes) {
  const declaredLength = Number(response.headers.get("content-length"));
  if (Number.isFinite(declaredLength) && declaredLength > maxBytes) {
    throw monitorError("invalid_upstream_response", "信息监控服务返回的数据过大", 502);
  }
  const chunks = [];
  let length = 0;
  if (response.body) {
    for await (const chunk of response.body) {
      const bytes = Buffer.from(chunk);
      length += bytes.length;
      if (length > maxBytes) {
        throw monitorError("invalid_upstream_response", "信息监控服务返回的数据过大", 502);
      }
      chunks.push(bytes);
    }
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw monitorError("invalid_upstream_response", "信息监控服务返回了无效数据", 502);
  }
}

function readCacheEntry(cache, key) {
  const entry = cache.get(key);
  if (!entry) return null;
  cache.delete(key);
  cache.set(key, entry);
  return entry;
}

function writeCacheEntry(cache, key, entry, maximumEntries) {
  cache.delete(key);
  cache.set(key, entry);
  while (cache.size > maximumEntries) cache.delete(cache.keys().next().value);
}

function fallbackOrThrow(cached, timestamp, maxStaleMs, code, message, statusCode) {
  if (cached && timestamp - cached.fetchedAt <= maxStaleMs) {
    return { body: cached.body, isStale: true, errorCode: code };
  }
  throw monitorError(code, message, statusCode);
}

function monitorError(code, message, statusCode) {
  return Object.assign(new Error(message), { code, statusCode });
}

function toSafeError(error) {
  if (error?.name === "TimeoutError" || error?.name === "AbortError") {
    return { code: "upstream_timeout", message: "信息监控服务连接超时", statusCode: 504 };
  }
  if (error instanceof z.ZodError || error instanceof SyntaxError) {
    return { code: "invalid_upstream_response", message: "信息监控服务返回了无效数据", statusCode: 502 };
  }
  if (error?.code && error?.statusCode) {
    return { code: error.code, message: error.message, statusCode: error.statusCode };
  }
  return { code: "upstream_unavailable", message: "暂时无法连接信息监控服务", statusCode: 502 };
}
