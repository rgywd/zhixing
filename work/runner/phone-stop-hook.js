import { randomUUID } from "node:crypto";
import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";
import { join } from "node:path";

try {
  const outbox = process.env.ZHIXING_WORK_HOOK_OUTBOX;
  const workSessionId = process.env.ZHIXING_WORK_SESSION_ID;
  const input = JSON.parse(readFileSync(0, "utf8") || "{}");
  if (outbox && workSessionId && input.hook_event_name === "Stop") {
    mkdirSync(outbox, { recursive: true });
    const marker = {
      version: 1,
      event: "Stop",
      workSessionId,
      codexSessionId: input.session_id ?? null,
      turnId: input.turn_id ?? null,
    };
    const name = `${Date.now()}-${randomUUID()}.json`;
    const target = join(outbox, name);
    const temporary = `${target}.tmp`;
    writeFileSync(temporary, JSON.stringify(marker), { mode: 0o600 });
    renameSync(temporary, target);
  }
} catch {
  // Phone hooks are observational only. They must never block Codex.
}
