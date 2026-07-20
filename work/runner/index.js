import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import { WorkRunner } from "./runner.js";

const configFile = resolve(process.env.WORK_RUNNER_CONFIG ?? "./work-runner.json");
const config = JSON.parse(readFileSync(configFile, "utf8"));
config.stateFile = resolve(config.stateFile ?? "./data/runner-state.json");
config.codexHome = resolve(config.codexHome ?? "./data/codex-home");

const runner = WorkRunner.fromConfig(config);
process.on("SIGINT", () => runner.stop());
process.on("SIGTERM", () => runner.stop());

runner.start().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exit(1);
});
