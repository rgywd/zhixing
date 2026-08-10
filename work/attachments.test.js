import assert from "node:assert/strict";
import test from "node:test";
import { inspectAttachment, safeAttachmentExtension, sanitizeAttachmentFileName } from "./attachments.js";

test("attachment policy accepts zip, 7z and gzip by content signature", () => {
  assert.equal(inspectAttachment({
    fileName: "source.zip",
    mimeType: "application/octet-stream",
    data: Buffer.from("504b0304", "hex"),
  }).mimeType, "application/zip");
  assert.equal(inspectAttachment({
    fileName: "source.7z",
    mimeType: "application/octet-stream",
    data: Buffer.from("377abcaf271c", "hex"),
  }).mimeType, "application/x-7z-compressed");
  assert.equal(inspectAttachment({
    fileName: "logs.tar.gz",
    mimeType: "application/x-gzip",
    data: Buffer.from("1f8b08", "hex"),
  }).mimeType, "application/gzip");
});

test("attachment policy rejects archive extension spoofing", () => {
  assert.throws(
    () => inspectAttachment({
      fileName: "source.7z",
      mimeType: "application/x-7z-compressed",
      data: Buffer.from("not-7z"),
    }),
    /does not match/i,
  );
});

test("runner extension policy preserves supported source and archive suffixes", () => {
  assert.equal(safeAttachmentExtension("build.gradle.kts", "text/plain"), ".kts");
  assert.equal(safeAttachmentExtension("source.7z", "application/x-7z-compressed"), ".7z");
  assert.equal(safeAttachmentExtension("unknown.bin", "application/octet-stream"), null);
  assert.equal(sanitizeAttachmentFileName("report:before?.txt"), "report_before_.txt");
});
