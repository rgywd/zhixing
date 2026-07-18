import { readFileSync, writeFileSync } from 'node:fs'

const files = [
  '/opt/happy/node_modules/pglite-prisma-adapter/dist/index.mjs',
  '/opt/happy/node_modules/pglite-prisma-adapter/dist/index.cjs',
]
const before = 'function convertBytes(serializedBytes) {\n\treturn parsePgBytes(serializedBytes);\n}'
const after = `function convertBytes(serializedBytes) {
\tif (typeof serializedBytes === "string") return Array.from(parsePgBytes(serializedBytes));
\tif (ArrayBuffer.isView(serializedBytes)) {
\t\treturn Array.from(new Uint8Array(serializedBytes.buffer, serializedBytes.byteOffset, serializedBytes.byteLength));
\t}
\tif (Array.isArray(serializedBytes)) return serializedBytes;
\tif (serializedBytes && typeof serializedBytes === "object") {
\t\tif (Array.isArray(serializedBytes.data)) return serializedBytes.data;
\t\treturn Object.keys(serializedBytes)
\t\t\t.filter((key) => /^\\d+$/.test(key))
\t\t\t.sort((left, right) => Number(left) - Number(right))
\t\t\t.map((key) => serializedBytes[key]);
\t}
\treturn [];
}`

for (const file of files) {
  const source = readFileSync(file, 'utf8')
  const matches = source.split(before).length - 1
  if (matches !== 1) {
    throw new Error(`Expected one BYTEA converter in ${file}, found ${matches}; audit the pinned package before upgrading`)
  }
  writeFileSync(file, source.replace(before, after))
}

console.log('Patched pglite-prisma-adapter BYTEA results for the Prisma JSON boundary')
