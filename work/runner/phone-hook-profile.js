import { mkdirSync, renameSync, writeFileSync } from "node:fs";
import { join } from "node:path";

function tomlString(value) {
  return JSON.stringify(String(value));
}

export function ensurePhoneHookProfile({
  codexHome,
  nodePath,
  hookScriptPath,
  profileName = "zhixing-phone",
}) {
  mkdirSync(codexHome, { recursive: true });
  const profileFile = join(codexHome, `${profileName}.config.toml`);
  const command = `${quoteCommandPart(nodePath)} ${quoteCommandPart(hookScriptPath)}`;
  const contents = [
    "[features]",
    "hooks = true",
    "",
    "[[hooks.Stop]]",
    "",
    "[[hooks.Stop.hooks]]",
    'type = "command"',
    `command = ${tomlString(command)}`,
    `command_windows = ${tomlString(command)}`,
    "timeout = 1",
    "",
  ].join("\n");
  const temporary = `${profileFile}.tmp`;
  writeFileSync(temporary, contents, { mode: 0o600 });
  renameSync(temporary, profileFile);
  return { profileName, profileFile };
}

function quoteCommandPart(value) {
  return `"${String(value).replaceAll('"', '\\"')}"`;
}
