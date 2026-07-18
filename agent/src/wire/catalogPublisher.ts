import { join } from 'node:path'
import { createHash, randomUUID } from 'node:crypto'
import type { AgentHome } from '../config.js'
import { AGENT_VERSION } from '../config.js'
import { CodexCatalogService } from '../catalog/codexCatalog.js'
import { ProjectRegistry } from '../catalog/projectRegistry.js'
import { CodexAppServerClient } from '../codex/appServerClient.js'
import { generateCodexSchemaHash } from '../codex/schemaHash.js'
import { WireRelayAgentClient } from './relayClient.js'
import type { CatalogProject, CatalogThreadSummary, CodexCatalogSnapshot } from '../catalog/types.js'

const REPUBLISH_INTERVAL_MS = 5 * 60_000

export async function publishCatalogOnce(
  home: AgentHome,
  force = false,
  relay = new WireRelayAgentClient(home),
): Promise<{
  revision: number
  projects: number
  threads: number
  recipients: number
  skipped: boolean
}> {
  const settings = home.loadSettings()
  const client = new CodexAppServerClient(undefined, undefined, () => {})
  try {
    await client.start()
    const schemaHash = await generateCodexSchemaHash(client.binaryPath)
    const service = new CodexCatalogService(
      client,
      new ProjectRegistry(join(home.dir, 'codex-projects.json')),
      { machineId: settings.machineId, agentVersion: AGENT_VERSION, schemaHash },
    )
    const snapshot = await service.snapshot()
    const previous = relay.catalogPublishState()
    const freshEnough = previous.lastPublishedAt !== null && Date.now() - previous.lastPublishedAt < REPUBLISH_INTERVAL_MS
    if (!force && previous.lastCatalogRevision === snapshot.revision && freshEnough) {
      return {
        revision: snapshot.revision,
        projects: snapshot.projects.length,
        threads: snapshot.threads.length,
        recipients: 0,
        skipped: true,
      }
    }
    const recipients = await publishSnapshot(relay, snapshot, settings.machineId)
    if (recipients > 0) relay.markCatalogPublished(snapshot.revision)
    return {
      revision: snapshot.revision,
      projects: snapshot.projects.length,
      threads: snapshot.threads.length,
      recipients,
      skipped: false,
    }
  } finally {
    client.stop()
  }
}

async function publishSnapshot(
  relay: WireRelayAgentClient,
  snapshot: CodexCatalogSnapshot,
  machineId: string,
): Promise<number> {
  const serialized = Buffer.from(JSON.stringify(snapshot), 'utf8')
  if (serialized.length <= DIRECT_SNAPSHOT_LIMIT_BYTES) {
    return await relay.publish('catalog.snapshot', snapshot, `catalog_${machineId}`)
  }
  const snapshotId = randomUUID().replaceAll('-', '_')
  const contents: Array<{ projects: CatalogProject[]; threads: CatalogThreadSummary[] }> = []
  for (let offset = 0; offset < snapshot.threads.length; offset += THREADS_PER_CHUNK) {
    contents.push({
      projects: offset === 0 ? snapshot.projects : [],
      threads: snapshot.threads.slice(offset, offset + THREADS_PER_CHUNK),
    })
  }
  if (contents.length === 0) contents.push({ projects: snapshot.projects, threads: [] })
  const encoded = contents.map((content) => Buffer.from(JSON.stringify(content), 'utf8'))
  const contentHash = createHash('sha256').update(Buffer.concat(encoded)).digest('hex')
  let recipients = 0
  for (let index = 0; index < encoded.length; index += 1) {
    const content = encoded[index]!
    recipients = Math.max(recipients, await relay.publish('catalog.snapshot.chunk', {
      snapshotId,
      revision: snapshot.revision,
      generatedAt: snapshot.generatedAt,
      machine: snapshot.machine,
      chunkIndex: index,
      chunkCount: encoded.length,
      contentHash,
      chunkHash: createHash('sha256').update(content).digest('hex'),
      contentBase64: content.toString('base64url'),
    }, `catalog_${machineId}_${snapshotId}_${index}`))
  }
  return recipients
}

const DIRECT_SNAPSHOT_LIMIT_BYTES = 600_000
const THREADS_PER_CHUNK = 128
