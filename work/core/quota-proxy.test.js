import assert from "node:assert/strict";
import { mkdtempSync, rmSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { createQuotaProxy, validateQuotaEnvelope } from "./quota-proxy.js";

function envelope(overrides = {}) {
  return {
    schema_version: "quota-monitor/v1",
    generated_at: "2026-07-22T06:40:00Z",
    stale_after_seconds: 1200,
    items: [{
      credential_id: "codex-1",
      provider: "codex",
      label: "Codex Pro",
      state: "ok",
      source_status: "active",
      plan: "pro",
      windows: [{
        key: "weekly",
        label: "Weekly limit",
        used: 6,
        limit: 100,
        remaining: 94,
        unit: "quota",
        used_percent: 6,
        remaining_percent: 94,
        reset_at: "2026-07-27T01:38:25Z",
        window_seconds: 604800,
      }],
      metadata: { tier: "pro" },
      checked_at: "2026-07-22T06:37:18Z",
      error: null,
    }],
    ...overrides,
  };
}

test("quota proxy authenticates upstream, validates the contract and caches the last good response", async (t) => {
  const directory = mkdtempSync(join(tmpdir(), "zhixing-quota-"));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const cacheFile = join(directory, "quota-cache.json");
  let timestamp = Date.parse("2026-07-22T06:40:01Z");
  let responseBody = envelope();
  const headers = [];
  const proxy = createQuotaProxy({
    baseUrl: "http://quota.example.test/",
    token: "server-only-token",
    cacheFile,
    cacheTtlMs: 10,
    now: () => timestamp,
    fetchImpl: async (_url, options) => {
      headers.push(options.headers);
      return new Response(JSON.stringify(responseBody), {
        status: 200,
        headers: { "content-type": "application/json" },
      });
    },
  });

  const first = await proxy.getQuotas();
  assert.equal(headers[0].authorization, "Bearer server-only-token");
  assert.equal(first.items[0].windows[0].remaining_percent, 94);
  assert.equal(first.proxy_stale, false);
  assert.equal(first.proxy_error, null);

  timestamp += 20;
  responseBody = envelope({
    items: [{ ...envelope().items[0], windows: [{ ...envelope().items[0].windows[0], remaining_percent: 101 }] }],
  });
  const fallback = await proxy.getQuotas();
  assert.equal(fallback.items[0].windows[0].remaining_percent, 94);
  assert.equal(fallback.proxy_stale, true);
  assert.equal(fallback.proxy_error.code, "invalid_upstream_response");

  const restarted = createQuotaProxy({ cacheFile, now: () => timestamp + 1 });
  const persistedFallback = await restarted.getQuotas();
  assert.equal(persistedFallback.items[0].credential_id, "codex-1");
  assert.equal(persistedFallback.proxy_stale, true);
  assert.equal(persistedFallback.proxy_error.code, "not_configured");
});

test("quota proxy never manufactures zero when no valid snapshot exists", async () => {
  const proxy = createQuotaProxy({
    baseUrl: "https://quota.example.test",
    token: "token",
    fetchImpl: async () => new Response(JSON.stringify(envelope({
      items: [{ ...envelope().items[0], windows: [{ ...envelope().items[0].windows[0], remaining_percent: -1 }] }],
    }))),
  });

  await assert.rejects(
    proxy.getQuotas(),
    (error) => error.statusCode === 502 && error.code === "invalid_upstream_response",
  );
});

test("quota schema accepts future providers and additive fields", () => {
  const parsed = validateQuotaEnvelope(envelope({
    future_top_level: true,
    items: [{ ...envelope().items[0], provider: "future-provider", future_item_field: "kept" }],
  }));
  assert.equal(parsed.future_top_level, true);
  assert.equal(parsed.items[0].future_item_field, "kept");
});
