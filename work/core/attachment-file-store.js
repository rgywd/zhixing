import { randomUUID } from "node:crypto";
import { existsSync, mkdirSync, renameSync, rmSync, writeFileSync } from "node:fs";
import { dirname, isAbsolute, relative, resolve } from "node:path";

export class AttachmentFileStore {
  constructor(root) {
    this.root = resolve(root);
    mkdirSync(this.root, { recursive: true });
  }

  put(attachmentId, data) {
    const shard = attachmentId.slice(4, 6) || "00";
    const storageKey = `objects/${shard}/${attachmentId}.bin`;
    const finalPath = this.pathFor(storageKey);
    const temporaryPath = `${finalPath}.${randomUUID()}.part`;
    mkdirSync(dirname(finalPath), { recursive: true });
    try {
      writeFileSync(temporaryPath, data, { flag: "wx", mode: 0o600 });
      renameSync(temporaryPath, finalPath);
      return storageKey;
    } catch (error) {
      rmSync(temporaryPath, { force: true });
      throw error;
    }
  }

  pathFor(storageKey) {
    if (!storageKey || isAbsolute(storageKey) || storageKey.includes("\\")) {
      throw new Error("Invalid attachment storage key");
    }
    const path = resolve(this.root, ...storageKey.split("/"));
    const relation = relative(this.root, path);
    if (!relation || relation.startsWith("..") || isAbsolute(relation)) {
      throw new Error("Attachment storage key escapes its root");
    }
    return path;
  }

  existingPath(storageKey) {
    const path = this.pathFor(storageKey);
    return existsSync(path) ? path : null;
  }

  remove(storageKey) {
    if (storageKey) rmSync(this.pathFor(storageKey), { force: true });
  }
}
