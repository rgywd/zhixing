import assert from "node:assert/strict";
import { createServer } from "node:http";
import test from "node:test";
import { CoreClient } from "./core-client.js";

test("attachment download retries transient Core failures", async (t) => {
  let requests = 0;
  const server = createServer((request, response) => {
    requests += 1;
    if (requests === 1) {
      response.writeHead(503, { "content-type": "application/json" });
      response.end(JSON.stringify({ message: "temporary outage" }));
      return;
    }
    response.writeHead(200, {
      "content-type": "image/png",
      "x-content-sha256": "hash",
    });
    response.end("image-bytes");
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  t.after(() => server.close());

  const address = server.address();
  const client = new CoreClient({
    baseUrl: `http://127.0.0.1:${address.port}`,
    token: "runner-token",
  });
  const attachment = await client.downloadAttachment("runner", "attachment");

  assert.equal(requests, 2);
  assert.equal(attachment.data.toString(), "image-bytes");
  assert.equal(attachment.mimeType, "image/png");
  assert.equal(attachment.sha256, "hash");
});
