import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { fileURLToPath } from "node:url";
import { dirname, join, resolve } from "node:path";

const STAGING_PACKAGE = "dev.sundby.zhixing.staging";
const TEST_PACKAGE = `${STAGING_PACKAGE}.test`;
const TEST_RUNNER = "androidx.test.runner.AndroidJUnitRunner";
const RESULT_KEY = "zhixingResult";
const BOOTSTRAP_REMOTE_COMMAND =
  `run-as ${STAGING_PACKAGE} sh -c 'mkdir -p no_backup && cat > no_backup/staging-work-bootstrap.json'`;
const MAX_BODY_BYTES = 1_048_576;
const here = dirname(fileURLToPath(import.meta.url));
const defaultRepoRoot = resolve(here, "..");

export function createStagingDriver({
  token,
  commandRunner = runCommand,
  adbPath = resolveAdbPath(),
  gradlePath = process.platform === "win32" ? join(defaultRepoRoot, "gradlew.bat") : join(defaultRepoRoot, "gradlew"),
  repoRoot = defaultRepoRoot,
  platform = process.platform,
  comSpec = process.env.ComSpec ?? "cmd.exe",
} = {}) {
  if (!token) throw new Error("STAGING_DRIVER_TOKEN is required");

  return createServer(async (request, response) => {
    try {
      if (!authorized(request, token)) {
        return sendJson(response, 401, apiError("UNAUTHORIZED", "Bearer token is required"));
      }

      const url = new URL(request.url ?? "/", "http://127.0.0.1");
      if (request.method === "GET" && url.pathname === "/v1/status") {
        return sendJson(response, 200, await readStatus(commandRunner, adbPath));
      }
      if (request.method === "POST" && url.pathname === "/v1/installations") {
        const gradle = gradleInvocation(gradlePath, platform, comSpec);
        await commandRunner(
          gradle.command,
          [...gradle.args, ":app:installStaging", ":app:installStagingAndroidTest"],
          { cwd: repoRoot },
        );
        return sendJson(response, 201, { status: "installed", applicationId: STAGING_PACKAGE });
      }
      if (request.method === "POST" && url.pathname === "/v1/config/work") {
        const input = await readJson(request);
        validateWorkConfig(input);
        const bootstrap = JSON.stringify({ baseUrl: input.baseUrl, token: input.token });
        await commandRunner(
          adbPath,
          ["exec-in", BOOTSTRAP_REMOTE_COMMAND],
          { input: bootstrap },
        );
        const result = await runInstrumentation(
          commandRunner,
          adbPath,
          "me.rerere.rikkahub.staging.StagingWorkBootstrapTest",
        );
        return sendJson(response, result.ok ? 200 : 422, result);
      }
      if (request.method === "POST" && url.pathname === "/v1/runs/work-title") {
        const input = await readJson(request);
        if (typeof input.message !== "string" || !input.message.trim() || input.message.length > 2_000) {
          return sendJson(response, 400, apiError("VALIDATION_ERROR", "message must contain 1-2000 characters"));
        }
        const result = await runInstrumentation(
          commandRunner,
          adbPath,
          "me.rerere.rikkahub.staging.StagingWorkTitleScenarioTest",
          ["-e", "messageBase64", Buffer.from(input.message, "utf8").toString("base64")],
        );
        return sendJson(response, result.ok ? 201 : 422, result);
      }
      if (request.method === "GET" && url.pathname === "/v1/screenshots/current") {
        const screenshot = await commandRunner(adbPath, ["exec-out", "screencap", "-p"], { encoding: null });
        response.writeHead(200, {
          "content-type": "image/png",
          "content-length": screenshot.stdout.length,
          "cache-control": "no-store",
        });
        return response.end(screenshot.stdout);
      }
      return sendJson(response, 404, apiError("NOT_FOUND", "Unknown staging-driver resource"));
    } catch (error) {
      const status = error?.code === "BODY_TOO_LARGE" ? 413 : error?.code === "VALIDATION_ERROR" ? 400 : 500;
      return sendJson(response, status, apiError(error?.code ?? "DRIVER_ERROR", error?.message ?? "Driver request failed"));
    }
  });
}

function gradleInvocation(gradlePath, platform, comSpec) {
  if (platform === "win32") {
    return { command: comSpec, args: ["/d", "/s", "/c", gradlePath] };
  }
  return { command: gradlePath, args: [] };
}

async function readStatus(commandRunner, adbPath) {
  const packages = await commandRunner(adbPath, ["shell", "dumpsys", "package", STAGING_PACKAGE]);
  const output = String(packages.stdout);
  const versionName = output.match(/versionName=([^\s]+)/)?.[1] ?? null;
  const versionCode = output.match(/versionCode=(\d+)/)?.[1] ?? null;
  let processId = null;
  try {
    const process = await commandRunner(adbPath, ["shell", "pidof", STAGING_PACKAGE]);
    processId = String(process.stdout).trim() || null;
  } catch (error) {
    if (error?.code !== "COMMAND_FAILED" || error?.exitCode !== 1 || error?.stderr) throw error;
  }
  return {
    applicationId: STAGING_PACKAGE,
    channel: "staging",
    installed: versionName !== null,
    running: processId !== null,
    processId,
    versionName,
    versionCode,
  };
}

async function runInstrumentation(commandRunner, adbPath, className, extraArgs = []) {
  const command = await commandRunner(
    adbPath,
    [
      "shell",
      "am",
      "instrument",
      "-w",
      "-r",
      "-e",
      "class",
      className,
      ...extraArgs,
      `${TEST_PACKAGE}/${TEST_RUNNER}`,
    ],
  );
  const output = `${String(command.stdout)}\n${String(command.stderr ?? "")}`;
  const encoded = output.match(new RegExp(`^INSTRUMENTATION_STATUS: ${RESULT_KEY}=(.+)$`, "m"))?.[1]?.trim();
  if (!encoded) throw Object.assign(new Error("Instrumentation did not return a structured Zhixing result"), { code: "MISSING_RESULT" });
  return JSON.parse(Buffer.from(encoded, "base64").toString("utf8"));
}

function validateWorkConfig(input) {
  if (typeof input?.baseUrl !== "string" || typeof input?.token !== "string" || !input.token.trim()) {
    throw Object.assign(new Error("baseUrl and token are required"), { code: "VALIDATION_ERROR" });
  }
  let url;
  try {
    url = new URL(input.baseUrl);
  } catch {
    throw Object.assign(new Error("baseUrl must be a valid HTTPS origin"), { code: "VALIDATION_ERROR" });
  }
  if (url.protocol !== "https:" || url.pathname !== "/" || url.search || url.hash) {
    throw Object.assign(new Error("baseUrl must be an HTTPS origin without a path"), { code: "VALIDATION_ERROR" });
  }
  if (url.username || url.password) {
    throw Object.assign(new Error("baseUrl must not contain credentials"), { code: "VALIDATION_ERROR" });
  }
}

function authorized(request, token) {
  return request.headers.authorization === `Bearer ${token}`;
}

async function readJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > MAX_BODY_BYTES) throw Object.assign(new Error("Request body is too large"), { code: "BODY_TOO_LARGE" });
    chunks.push(chunk);
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
  } catch {
    throw Object.assign(new Error("Request body must be valid JSON"), { code: "VALIDATION_ERROR" });
  }
}

function sendJson(response, status, body) {
  const payload = Buffer.from(JSON.stringify(body));
  response.writeHead(status, {
    "content-type": "application/json; charset=utf-8",
    "content-length": payload.length,
    "cache-control": "no-store",
  });
  response.end(payload);
}

function apiError(code, message) {
  return { error: { code, message } };
}

export function runCommand(command, args, { cwd, input, encoding = "utf8" } = {}) {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(command, args, { cwd, windowsHide: true, shell: false });
    const stdout = [];
    const stderr = [];
    child.stdout.on("data", (chunk) => stdout.push(chunk));
    child.stderr.on("data", (chunk) => stderr.push(chunk));
    child.on("error", rejectPromise);
    child.on("close", (code) => {
      const output = Buffer.concat(stdout);
      const errors = Buffer.concat(stderr).toString("utf8").trim();
      if (code !== 0) {
        return rejectPromise(Object.assign(
          new Error(errors || `${command} exited with ${code}`),
          { code: "COMMAND_FAILED", exitCode: code, stderr: errors },
        ));
      }
      resolvePromise({
        stdout: encoding === null ? output : output.toString(encoding),
        stderr: errors,
      });
    });
    if (input !== undefined) child.stdin.end(input);
    else child.stdin.end();
  });
}

function resolveAdbPath() {
  if (process.env.ADB_PATH) return process.env.ADB_PATH;
  if (process.platform === "win32" && process.env.LOCALAPPDATA) {
    return join(process.env.LOCALAPPDATA, "Android", "Sdk", "platform-tools", "adb.exe");
  }
  return "adb";
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const token = process.env.STAGING_DRIVER_TOKEN;
  if (!token) {
    console.error("STAGING_DRIVER_TOKEN is required");
    process.exit(1);
  }
  const port = Number.parseInt(process.env.STAGING_DRIVER_PORT ?? "18787", 10);
  createStagingDriver({ token }).listen(port, "127.0.0.1", () => {
    console.log(`Zhixing Staging Driver listening on http://127.0.0.1:${port}`);
  });
}
