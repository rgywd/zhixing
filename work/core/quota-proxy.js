import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";
import { z } from "zod";

const SCHEMA_VERSION = "quota-monitor/v1";

const quotaWindowSchema = z.object({
  key: z.string().min(1),
  label: z.string().min(1),
  used: z.number().finite().nullable(),
  limit: z.number().finite().nullable(),
  remaining: z.number().finite().nullable(),
  unit: z.string(),
  used_percent: z.number().finite().min(0).max(100).nullable(),
  remaining_percent: z.number().finite().min(0).max(100).nullable(),
  reset_at: z.string().datetime({ offset: true }).nullable(),
  window_seconds: z.number().int().nonnegative().nullable(),
}).passthrough();

const quotaErrorSchema = z.object({
  code: z.string().min(1),
  message: z.string(),
  retriable: z.boolean(),
}).passthrough();

const quotaSnapshotSchema = z.object({
  credential_id: z.string().min(1),
  provider: z.string().min(1),
  label: z.string().min(1),
  state: z.enum(["ok", "disabled", "unavailable", "unsupported", "error"]),
  source_status: z.string(),
  plan: z.string().nullable(),
  windows: z.array(quotaWindowSchema),
  metadata: z.record(z.unknown()),
  checked_at: z.string().datetime({ offset: true }),
  error: quotaErrorSchema.nullable(),
}).passthrough();

const quotaEnvelopeSchema = z.object({
  schema_version: z.literal(SCHEMA_VERSION),
  generated_at: z.string().datetime({ offset: true }),
  stale_after_seconds: z.number().int().nonnegative(),
  items: z.array(quotaSnapshotSchema),
}).passthrough();

export function validateQuotaEnvelope(value) {
  return quotaEnvelopeSchema.parse(value);
}

export function createQuotaProxy({
  baseUrl,
  token,
  fetchImpl = globalThis.fetch,
  cacheFile,
  cacheTtlMs = 60_000,
  timeoutMs = 15_000,
  now = () => Date.now(),
} = {}) {
  const endpoint = normalizeEndpoint(baseUrl);
  let lastGood = readCache(cacheFile);
  let lastFetchAt = 0;
  let inFlight = null;

  async function getQuotas() {
    if (lastGood && now() - lastFetchAt < cacheTtlMs) {
      return decorate(lastGood, false, null, now());
    }
    if (inFlight) return inFlight;
    inFlight = load().finally(() => {
      inFlight = null;
    });
    return inFlight;
  }

  async function load() {
    if (!endpoint || !token) {
      return fallbackOrThrow(lastGood, "not_configured", "套餐余量服务尚未配置", 503, now());
    }
    try {
      const response = await fetchImpl(`${endpoint}/v1/quotas`, {
        headers: {
          accept: "application/json",
          authorization: `Bearer ${token}`,
        },
        signal: AbortSignal.timeout(timeoutMs),
      });
      if (!response.ok) {
        throw quotaError("upstream_http_error", `套餐余量服务返回 HTTP ${response.status}`, 502);
      }
      const envelope = validateQuotaEnvelope(await response.json());
      lastGood = envelope;
      lastFetchAt = now();
      writeCache(cacheFile, envelope);
      return decorate(envelope, false, null, now());
    } catch (error) {
      const safe = toSafeError(error);
      return fallbackOrThrow(lastGood, safe.code, safe.message, safe.statusCode, now());
    }
  }

  return { getQuotas };
}

function normalizeEndpoint(value) {
  if (!value) return null;
  try {
    const url = new URL(value);
    if (!new Set(["http:", "https:"]).has(url.protocol)) return null;
    if (url.username || url.password || url.search || url.hash) return null;
    url.pathname = url.pathname.replace(/\/+$/, "");
    return url.toString().replace(/\/$/, "");
  } catch {
    return null;
  }
}

function decorate(envelope, stale, error, timestamp) {
  return {
    ...envelope,
    served_at: new Date(timestamp).toISOString(),
    proxy_stale: stale,
    proxy_error: error,
  };
}

function fallbackOrThrow(lastGood, code, message, statusCode, timestamp) {
  if (lastGood) return decorate(lastGood, true, { code, message }, timestamp);
  throw quotaError(code, message, statusCode);
}

function quotaError(code, message, statusCode) {
  return Object.assign(new Error(message), { code, statusCode });
}

function toSafeError(error) {
  if (error?.name === "TimeoutError") {
    return { code: "upstream_timeout", message: "套餐余量服务连接超时", statusCode: 504 };
  }
  if (error instanceof z.ZodError || error instanceof SyntaxError) {
    return { code: "invalid_upstream_response", message: "套餐余量服务返回了无效数据", statusCode: 502 };
  }
  if (error?.code && error?.statusCode) {
    return { code: error.code, message: error.message, statusCode: error.statusCode };
  }
  return { code: "upstream_unavailable", message: "暂时无法连接套餐余量服务", statusCode: 502 };
}

function readCache(cacheFile) {
  if (!cacheFile) return null;
  try {
    return validateQuotaEnvelope(JSON.parse(readFileSync(cacheFile, "utf8")));
  } catch {
    return null;
  }
}

function writeCache(cacheFile, envelope) {
  if (!cacheFile) return;
  try {
    mkdirSync(dirname(cacheFile), { recursive: true });
    const temporary = `${cacheFile}.tmp`;
    writeFileSync(temporary, `${JSON.stringify(envelope)}\n`, { mode: 0o600 });
    renameSync(temporary, cacheFile);
  } catch {
    // Cache persistence must never turn a successful upstream response into a page failure.
  }
}
