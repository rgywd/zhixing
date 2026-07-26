import assert from "node:assert/strict";
import test from "node:test";
import {
  createInformationMonitorProxy,
  parseInformationMonitorQuery,
  validateInformationMonitorResponse,
} from "./information-monitor-proxy.js";

const ITEM = {
  id: "event_01",
  sourceLabel: "工作邮箱",
  channel: "email",
  occurredAt: "2026-07-26T03:04:05Z",
  sender: "项目组",
  title: "本周安排",
  summary: "项目组更新了本周安排。",
  actionItems: ["确认周三会议时间"],
  importance: "high",
};

function responseFor(action) {
  if (action === "status") {
    return {
      schema: "information-monitor/v1",
      sources: [{
        sourceLabel: "工作邮箱",
        kind: "email",
        state: "ok",
        lastSucceededAt: "2026-07-26T03:04:05Z",
        lastErrorCode: null,
        itemCount24h: 3,
      }],
    };
  }
  if (action === "items") {
    return { schema: "information-monitor/v1", items: [ITEM] };
  }
  return {
    schema: "information-monitor/v1",
    total: 3,
    highPriority: 1,
    channels: [{ channel: "email", count: 3, topItems: [ITEM] }],
  };
}

test("information monitor schemas validate all actions and strip additive fields", () => {
  for (const action of ["status", "items", "digest"]) {
    const input = { ...responseFor(action), futureField: "not forwarded" };
    const parsed = validateInformationMonitorResponse(action, input);
    assert.equal(parsed.schema, "information-monitor/v1");
    assert.equal("futureField" in parsed, false);
  }

  const parsedItems = validateInformationMonitorResponse("items", {
    schema: "information-monitor/v1",
    items: [{ ...ITEM, sender: null, title: null, futureItemField: true }],
  });
  assert.equal(parsedItems.items[0].sender, null);
  assert.equal(parsedItems.items[0].title, null);
  assert.equal("futureItemField" in parsedItems.items[0], false);
});

test("information monitor schemas reject forbidden keys at any depth and non-UTC timestamps", () => {
  assert.throws(
    () => validateInformationMonitorResponse("items", {
      ...responseFor("items"),
      metadata: { nested: { provider_item_id: "provider-secret" } },
    }),
    (error) => error.code === "invalid_upstream_response",
  );
  for (const forbidden of [
    { refreshToken: "oauth-secret" },
    { payload: { attachment: "must-not-cross" } },
    { cursor: { historyId: "internal-cursor" } },
  ]) {
    assert.throws(
      () => validateInformationMonitorResponse("items", {
        ...responseFor("items"),
        metadata: forbidden,
      }),
      (error) => error.code === "invalid_upstream_response",
    );
  }
  assert.throws(
    () => validateInformationMonitorResponse("items", {
      schema: "information-monitor/v1",
      items: [{ ...ITEM, occurredAt: "2026-07-26T11:04:05+08:00" }],
    }),
  );
  assert.throws(
    () => validateInformationMonitorResponse("items", {
      schema: "information-monitor/v1",
      items: [{ ...ITEM, summary: " \t" }],
    }),
  );
  assert.throws(
    () => validateInformationMonitorResponse("digest", {
      ...responseFor("digest"),
      total: 1,
      highPriority: 2,
    }),
  );
  assert.throws(
    () => validateInformationMonitorResponse("digest", {
      ...responseFor("digest"),
      channels: [{
        channel: "feishu",
        count: 1,
        topItems: [ITEM],
      }],
    }),
  );
  assert.throws(
    () => validateInformationMonitorResponse("digest", {
      ...responseFor("digest"),
      total: 4,
    }),
  );
  assert.throws(
    () => validateInformationMonitorResponse("digest", {
      ...responseFor("digest"),
      total: 0,
      highPriority: 0,
      channels: [{ channel: "email", count: 0, topItems: [ITEM] }],
    }),
  );
});

test("information monitor query parser allows only the frozen bounded query contract", () => {
  const parsed = parseInformationMonitorQuery(new URLSearchParams(
    "limit=50&channel=feishu&minImportance=urgent&hours=168",
  ));
  assert.equal(parsed.toString(), "channel=feishu&hours=168&limit=50&minImportance=urgent");

  for (const query of [
    "unknown=1",
    "channel=calendar",
    "hours=0",
    "hours=01",
    "hours=169",
    "limit=0",
    "limit=51",
    "minImportance=critical",
    "channel=email&channel=feishu",
  ]) {
    assert.throws(
      () => parseInformationMonitorQuery(new URLSearchParams(query)),
      (error) => error.code === "invalid_query" && error.statusCode === 400,
      query,
    );
  }
});

test("proxy rejects action-specific query fields that the gateway would otherwise ignore", async () => {
  const proxy = createInformationMonitorProxy({
    baseUrl: "https://life.example.test",
    token: "token",
    fetchImpl: async () => assert.fail("invalid query must not reach the gateway"),
  });
  await assert.rejects(
    proxy.get("status", new URLSearchParams("hours=24")),
    (error) => error.code === "invalid_query" && error.statusCode === 400,
  );
  await assert.rejects(
    proxy.get("digest", new URLSearchParams("limit=10")),
    (error) => error.code === "invalid_query" && error.statusCode === 400,
  );
});

test("proxy forwards only normalized queries and server-only authorization", async () => {
  const requests = [];
  const proxy = createInformationMonitorProxy({
    baseUrl: "https://life.example.test/gateway/",
    token: "server-only-secret",
    fetchImpl: async (url, options) => {
      requests.push({ url, options });
      return Response.json(responseFor("items"));
    },
  });

  const result = await proxy.get(
    "items",
    new URLSearchParams("limit=5&minImportance=high&channel=email&hours=24"),
  );
  assert.equal(result.isStale, false);
  assert.equal(result.body.items[0].id, "event_01");
  assert.equal(
    requests[0].url,
    "https://life.example.test/gateway/api/v1/monitor/items?channel=email&hours=24&limit=5&minImportance=high",
  );
  assert.equal(requests[0].options.headers.authorization, "Bearer server-only-secret");
  assert.equal(requests[0].options.redirect, "error");
});

test("proxy permits loopback HTTP but rejects public plaintext HTTP and credentialed URLs", async () => {
  let loopbackCalls = 0;
  const loopback = createInformationMonitorProxy({
    baseUrl: "http://127.0.0.2:8788",
    token: "token",
    fetchImpl: async () => {
      loopbackCalls += 1;
      return Response.json(responseFor("status"));
    },
  });
  assert.equal((await loopback.get("status")).body.sources.length, 1);
  assert.equal(loopbackCalls, 1);

  for (const baseUrl of [
    "http://life.example.test",
    "https://user:password@life.example.test",
    "https://life.example.test?token=secret",
    "file:///tmp/gateway",
  ]) {
    const proxy = createInformationMonitorProxy({
      baseUrl,
      token: "secret-token",
      fetchImpl: async () => assert.fail("invalid endpoint must not be fetched"),
    });
    await assert.rejects(
      proxy.get("status"),
      (error) => error.code === "not_configured"
        && error.statusCode === 503
        && !error.message.includes("secret-token")
        && !error.message.includes(baseUrl),
      baseUrl,
    );
  }
});

test("proxy uses a bounded same-query last-good cache and never forwards forbidden fallback data", async () => {
  let timestamp = Date.parse("2026-07-26T03:04:06Z");
  let responseBody = responseFor("items");
  const proxy = createInformationMonitorProxy({
    baseUrl: "https://life.example.test",
    token: "token",
    cacheTtlMs: 10,
    maxStaleMs: 100,
    now: () => timestamp,
    fetchImpl: async () => Response.json(responseBody),
  });
  const query = new URLSearchParams("channel=email&hours=24&limit=10");

  const first = await proxy.get("items", query);
  assert.equal(first.isStale, false);
  timestamp += 20;
  responseBody = {
    ...responseFor("items"),
    items: [{ ...ITEM, raw: { fullMessage: "must never cross the proxy" } }],
  };
  const stale = await proxy.get("items", query);
  assert.equal(stale.isStale, true);
  assert.equal(stale.errorCode, "invalid_upstream_response");
  assert.equal("raw" in stale.body.items[0], false);

  await assert.rejects(
    proxy.get("items", new URLSearchParams("channel=feishu&hours=24&limit=10")),
    (error) => error.code === "invalid_upstream_response" && error.statusCode === 502,
  );

  timestamp += 101;
  await assert.rejects(
    proxy.get("items", query),
    (error) => error.code === "invalid_upstream_response" && error.statusCode === 502,
  );
});

test("proxy rejects oversized or non-JSON upstream responses with safe errors", async () => {
  for (const response of [
    new Response("not json", { headers: { "content-type": "text/plain" } }),
    new Response(JSON.stringify(responseFor("status")), {
      headers: { "content-type": "application/json", "content-length": "999999" },
    }),
  ]) {
    const proxy = createInformationMonitorProxy({
      baseUrl: "https://life.example.test",
      token: "never-log-this-token",
      maxResponseBytes: 1_024,
      fetchImpl: async () => response,
    });
    await assert.rejects(
      proxy.get("status"),
      (error) => error.code === "invalid_upstream_response"
        && error.statusCode === 502
        && !error.message.includes("never-log-this-token"),
    );
  }
});
