import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { WorkStore } from "./store.js";
import { createWorkServer } from "./server.js";
import { createQuotaProxy } from "./quota-proxy.js";
import { createInformationMonitorProxy } from "./information-monitor-proxy.js";

const here = dirname(fileURLToPath(import.meta.url));
const dataFile = resolve(process.env.WORK_CORE_DB ?? `${here}/../data/work-core.sqlite`);
const attachmentRoot = resolve(process.env.WORK_CORE_ATTACHMENT_DIR ?? resolve(dirname(dataFile), "attachments"));
mkdirSync(dirname(dataFile), { recursive: true });

const userToken = process.env.WORK_USER_TOKEN;
const runnerTokens = runCatchingJson(process.env.WORK_RUNNER_TOKENS);
const sessionSecret = process.env.WORK_SESSION_SECRET;
if (!userToken || !runnerTokens || !Object.keys(runnerTokens).length || !sessionSecret) {
  console.error("WORK_USER_TOKEN, WORK_RUNNER_TOKENS and WORK_SESSION_SECRET are required");
  process.exit(1);
}

const store = new WorkStore({ filename: dataFile, attachmentRoot, userToken, runnerTokens, sessionSecret });
const quotaProxy = createQuotaProxy({
  baseUrl: process.env.CPA_QUOTA_BASE_URL,
  token: process.env.CPA_QUOTA_TOKEN,
  cacheFile: process.env.CPA_QUOTA_CACHE ?? resolve(dirname(dataFile), "quota-cache.json"),
});
const informationMonitorProxy = createInformationMonitorProxy({
  baseUrl: process.env.LIFE_GATEWAY_BASE_URL,
  token: process.env.LIFE_GATEWAY_TOKEN,
});
const server = createWorkServer({ store, quotaProxy, informationMonitorProxy });
const port = Number(process.env.PORT ?? 8787);
const host = process.env.HOST ?? "127.0.0.1";

server.listen(port, host, () => {
  console.log(`Zhixing Work Core listening on http://${host}:${port}`);
});

function shutdown() {
  server.close(() => {
    store.close();
    process.exit(0);
  });
}

process.on("SIGINT", shutdown);
process.on("SIGTERM", shutdown);

function runCatchingJson(value) {
  try {
    return JSON.parse(value ?? "");
  } catch {
    return null;
  }
}
