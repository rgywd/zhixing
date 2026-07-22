import assert from "node:assert/strict";
import { once } from "node:events";
import test from "node:test";
import { createStagingDriver } from "./server.js";

test("requires bearer authentication", async (t) => {
  const fixture = await startFixture(t, async () => ({ stdout: "" }));
  const response = await fetch(`${fixture.baseUrl}/v1/status`);
  assert.equal(response.status, 401);
  assert.equal((await response.json()).error.code, "UNAUTHORIZED");
});

test("reports installed staging identity", async (t) => {
  const fixture = await startFixture(t, async (_command, args) => {
    if (args.includes("dumpsys")) return { stdout: "versionCode=24 minSdk=26 targetSdk=37\nversionName=0.3.6-staging" };
    if (args.includes("pidof")) return { stdout: "4321\n" };
    throw new Error(`Unexpected args: ${args.join(" ")}`);
  });
  const response = await fixture.request("/v1/status");
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), {
    applicationId: "dev.sundby.zhixing.staging",
    channel: "staging",
    installed: true,
    running: true,
    processId: "4321",
    versionName: "0.3.6-staging",
    versionCode: "24",
  });
});

test("reports an installed but stopped staging app without failing", async (t) => {
  const fixture = await startFixture(t, async (_command, args) => {
    if (args.includes("dumpsys")) return { stdout: "versionCode=25\nversionName=0.3.7-staging" };
    if (args.includes("pidof")) {
      throw Object.assign(new Error("adb exited with 1"), {
        code: "COMMAND_FAILED",
        exitCode: 1,
        stderr: "",
      });
    }
    throw new Error(`Unexpected args: ${args.join(" ")}`);
  });
  const response = await fixture.request("/v1/status");
  assert.equal(response.status, 200);
  assert.equal((await response.json()).running, false);
});

test("work credentials travel through stdin and never command arguments", async (t) => {
  const calls = [];
  const result = encodedResult({ ok: true, scenario: "work-bootstrap" });
  const fixture = await startFixture(t, async (command, args, options = {}) => {
    calls.push({ command, args, options });
    if (args.includes("instrument")) return { stdout: `INSTRUMENTATION_STATUS: zhixingResult=${result}\n` };
    return { stdout: "" };
  });
  const response = await fixture.request("/v1/config/work", {
    method: "POST",
    body: JSON.stringify({ baseUrl: "https://work.example.com", token: "secret-token" }),
  });
  assert.equal(response.status, 200);
  assert.equal(calls.some((call) => call.args.join(" ").includes("secret-token")), false);
  const stdinCall = calls.find((call) => call.options.input);
  assert.equal(stdinCall.options.input.includes("secret-token"), true);
  assert.deepEqual(stdinCall.args, [
    "exec-in",
    "run-as dev.sundby.zhixing.staging sh -c 'mkdir -p no_backup && cat > no_backup/staging-work-bootstrap.json'",
  ]);
});

test("runs only the allowlisted work title instrumentation", async (t) => {
  const calls = [];
  const result = encodedResult({ ok: true, scenario: "work-title", title: "修复标题" });
  const fixture = await startFixture(t, async (command, args, options) => {
    calls.push({ command, args, options });
    return { stdout: `INSTRUMENTATION_STATUS: zhixingResult=${result}\n` };
  });
  const response = await fixture.request("/v1/runs/work-title", {
    method: "POST",
    body: JSON.stringify({ message: "请修复 Work 标题" }),
  });
  assert.equal(response.status, 201);
  assert.equal((await response.json()).title, "修复标题");
  assert.equal(calls.length, 1);
  assert.ok(calls[0].args.includes("me.rerere.rikkahub.staging.StagingWorkTitleScenarioTest"));
  assert.equal(calls[0].args.includes("请修复 Work 标题"), false);
});

test("rejects invalid work origins as client errors", async (t) => {
  const fixture = await startFixture(t, async () => {
    throw new Error("command runner must not be called");
  });
  const response = await fixture.request("/v1/config/work", {
    method: "POST",
    body: JSON.stringify({ baseUrl: "not-a-url", token: "secret-token" }),
  });
  assert.equal(response.status, 400);
  assert.equal((await response.json()).error.code, "VALIDATION_ERROR");
});

test("uses a fixed Windows wrapper invocation for staging installation", async (t) => {
  const calls = [];
  const server = createStagingDriver({
    token: "test-token",
    platform: "win32",
    comSpec: "cmd.exe",
    gradlePath: "C:\\repo\\gradlew.bat",
    commandRunner: async (command, args, options) => {
      calls.push({ command, args, options });
      return { stdout: "" };
    },
  });
  const fixture = await listenFixture(t, server);
  const response = await fixture.request("/v1/installations", { method: "POST", body: "{}" });
  assert.equal(response.status, 201);
  assert.deepEqual(calls[0].args, [
    "/d",
    "/s",
    "/c",
    "C:\\repo\\gradlew.bat",
    ":app:installStaging",
    ":app:installStagingAndroidTest",
  ]);
});

async function startFixture(t, commandRunner) {
  const server = createStagingDriver({ token: "test-token", commandRunner, adbPath: "adb" });
  return listenFixture(t, server);
}

async function listenFixture(t, server) {
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => server.close());
  const { port } = server.address();
  return {
    baseUrl: `http://127.0.0.1:${port}`,
    request(path, init = {}) {
      return fetch(`http://127.0.0.1:${port}${path}`, {
        ...init,
        headers: {
          authorization: "Bearer test-token",
          "content-type": "application/json",
          ...init.headers,
        },
      });
    },
  };
}

function encodedResult(value) {
  return Buffer.from(JSON.stringify(value), "utf8").toString("base64");
}
