import { mkdirSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { WorkStore } from "./store.js";
import { createWorkServer } from "./server.js";

const here = dirname(fileURLToPath(import.meta.url));
const dataFile = resolve(process.env.WORK_CORE_DB ?? `${here}/../data/work-core.sqlite`);
mkdirSync(dirname(dataFile), { recursive: true });

const userToken = process.env.WORK_USER_TOKEN;
const runnerToken = process.env.WORK_RUNNER_TOKEN;
const sessionSecret = process.env.WORK_SESSION_SECRET;
if (!userToken || !runnerToken || !sessionSecret) {
  console.error("WORK_USER_TOKEN, WORK_RUNNER_TOKEN and WORK_SESSION_SECRET are required");
  process.exit(1);
}

const store = new WorkStore({ filename: dataFile, userToken, runnerToken, sessionSecret });
const server = createWorkServer({ store });
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
