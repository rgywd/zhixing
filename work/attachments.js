const IMAGE_MIME_TYPES = new Set(["image/png", "image/jpeg", "image/webp", "image/gif"]);

const TEXT_EXTENSIONS = new Set([
  "txt", "md", "markdown", "mdx", "csv", "json", "js", "jsx", "mjs", "cjs",
  "html", "css", "vue", "svelte", "xml", "py", "rb", "lua", "sql", "java", "kt",
  "ts", "tsx", "dart", "php", "swift", "go", "bat", "cmd", "ps1", "psm1", "sh",
  "bash", "zsh", "fish", "c", "h", "cpp", "cc", "cxx", "hpp", "hh", "hxx", "rs",
  "cs", "toml", "ini", "env", "gradle", "kts", "properties", "proto", "graphql", "gql",
  "yml", "yaml",
]);

const TEXT_MIME_TYPES = new Set([
  "application/json",
  "application/javascript",
  "application/xml",
  "application/yaml",
  "application/x-yaml",
  "application/toml",
]);

const ZIP_MIME_ALIASES = new Set(["application/zip", "application/x-zip-compressed"]);
const GZIP_MIME_ALIASES = new Set(["application/gzip", "application/x-gzip"]);

const ZIP_DOCUMENTS = new Map([
  ["docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"],
  ["xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"],
  ["pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"],
  ["epub", "application/epub+zip"],
]);

const LEGACY_OFFICE_DOCUMENTS = new Map([
  ["doc", "application/msword"],
  ["xls", "application/vnd.ms-excel"],
  ["ppt", "application/vnd.ms-powerpoint"],
]);

export const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024;
export const MAX_ATTACHMENTS_PER_MESSAGE = 4;

export function inspectAttachment({ fileName, mimeType, data }) {
  const safeName = sanitizeAttachmentFileName(fileName);
  const incomingMime = normalizeMimeType(mimeType);
  const bytes = Buffer.from(data ?? []);
  const extension = fileExtension(safeName);

  if (!bytes.length) throw invalidAttachment("Attachment is empty", 400);

  if (IMAGE_MIME_TYPES.has(incomingMime)) {
    if (!hasImageSignature(bytes, incomingMime)) {
      throw invalidAttachment("Image content does not match its content type");
    }
    return { fileName: safeName, mimeType: incomingMime, kind: "image" };
  }

  if (extension === "zip" || ZIP_MIME_ALIASES.has(incomingMime)) {
    if (!hasZipSignature(bytes)) throw invalidAttachment("ZIP content does not match its file type");
    return { fileName: safeName, mimeType: "application/zip", kind: "file" };
  }
  if (extension === "7z" || incomingMime === "application/x-7z-compressed") {
    if (!bytes.subarray(0, 6).equals(Buffer.from("377abcaf271c", "hex"))) {
      throw invalidAttachment("7z content does not match its file type");
    }
    return { fileName: safeName, mimeType: "application/x-7z-compressed", kind: "file" };
  }
  if (extension === "gz" || GZIP_MIME_ALIASES.has(incomingMime)) {
    if (!bytes.subarray(0, 2).equals(Buffer.from("1f8b", "hex"))) {
      throw invalidAttachment("GZip content does not match its file type");
    }
    return { fileName: safeName, mimeType: "application/gzip", kind: "file" };
  }

  if (extension === "pdf" || incomingMime === "application/pdf") {
    if (bytes.subarray(0, 5).toString("ascii") !== "%PDF-") {
      throw invalidAttachment("PDF content does not match its file type");
    }
    return { fileName: safeName, mimeType: "application/pdf", kind: "file" };
  }

  const zipDocumentMime = ZIP_DOCUMENTS.get(extension)
    ?? [...ZIP_DOCUMENTS.values()].find((candidate) => candidate === incomingMime);
  if (zipDocumentMime) {
    if (!hasZipSignature(bytes)) throw invalidAttachment("Document content does not match its file type");
    return { fileName: safeName, mimeType: zipDocumentMime, kind: "file" };
  }

  const legacyDocumentMime = LEGACY_OFFICE_DOCUMENTS.get(extension)
    ?? [...LEGACY_OFFICE_DOCUMENTS.values()].find((candidate) => candidate === incomingMime);
  if (legacyDocumentMime) {
    if (!bytes.subarray(0, 8).equals(Buffer.from("d0cf11e0a1b11ae1", "hex"))) {
      throw invalidAttachment("Document content does not match its file type");
    }
    return { fileName: safeName, mimeType: legacyDocumentMime, kind: "file" };
  }

  const textType = incomingMime.startsWith("text/") || TEXT_MIME_TYPES.has(incomingMime);
  if ((extension && TEXT_EXTENSIONS.has(extension)) || (!extension && textType)) {
    if (bytes.includes(0)) throw invalidAttachment("Text attachment contains binary data");
    try {
      new TextDecoder("utf-8", { fatal: true }).decode(bytes);
    } catch {
      throw invalidAttachment("Text attachment must use UTF-8 encoding");
    }
    return {
      fileName: safeName,
      mimeType: textType ? incomingMime : "text/plain",
      kind: "file",
    };
  }

  throw invalidAttachment("Unsupported attachment file type");
}

export function isImageMimeType(mimeType) {
  return IMAGE_MIME_TYPES.has(normalizeMimeType(mimeType));
}

export function safeAttachmentExtension(fileName, mimeType) {
  const extension = fileExtension(sanitizeAttachmentFileName(fileName));
  if (extension && (
    TEXT_EXTENSIONS.has(extension)
    || ZIP_DOCUMENTS.has(extension)
    || LEGACY_OFFICE_DOCUMENTS.has(extension)
    || ["zip", "7z", "gz", "pdf", "png", "jpg", "jpeg", "webp", "gif"].includes(extension)
  )) return `.${extension}`;
  const normalized = normalizeMimeType(mimeType);
  return {
    "image/png": ".png",
    "image/jpeg": ".jpg",
    "image/webp": ".webp",
    "image/gif": ".gif",
    "application/zip": ".zip",
    "application/x-7z-compressed": ".7z",
    "application/gzip": ".gz",
    "application/pdf": ".pdf",
    "text/plain": ".txt",
    "text/markdown": ".md",
    "application/json": ".json",
  }[normalized] ?? (normalized.startsWith("text/") ? ".txt" : null);
}

export function sanitizeAttachmentFileName(fileName) {
  const sanitized = String(fileName ?? "attachment")
    .replace(/[<>:"|?*\\/\0-\x1f\x7f]/g, "_")
    .trim()
    .slice(0, 160);
  return sanitized && sanitized !== "." && sanitized !== ".." ? sanitized : "attachment";
}

function normalizeMimeType(mimeType) {
  return String(mimeType ?? "application/octet-stream").split(";", 1)[0].trim().toLowerCase();
}

function fileExtension(fileName) {
  const index = fileName.lastIndexOf(".");
  return index >= 0 && index < fileName.length - 1 ? fileName.slice(index + 1).toLowerCase() : "";
}

function hasZipSignature(data) {
  const signature = data.subarray(0, 4);
  return ["504b0304", "504b0506", "504b0708"].includes(signature.toString("hex"));
}

function hasImageSignature(data, mimeType) {
  if (mimeType === "image/png") return data.subarray(0, 8).equals(Buffer.from("89504e470d0a1a0a", "hex"));
  if (mimeType === "image/jpeg") return data.length >= 3 && data[0] === 0xff && data[1] === 0xd8 && data[2] === 0xff;
  if (mimeType === "image/gif") return ["GIF87a", "GIF89a"].includes(data.subarray(0, 6).toString("ascii"));
  if (mimeType === "image/webp") {
    return data.subarray(0, 4).toString("ascii") === "RIFF" && data.subarray(8, 12).toString("ascii") === "WEBP";
  }
  return false;
}

function invalidAttachment(message, statusCode = 415) {
  return Object.assign(new Error(message), { statusCode });
}
