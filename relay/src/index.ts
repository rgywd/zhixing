import { loadConfig } from './config.js'
import { buildRelayServer } from './server.js'

const config = loadConfig()
const { app } = buildRelayServer({ config })

const shutdown = async () => {
  await app.close()
  process.exit(0)
}
process.once('SIGINT', () => void shutdown())
process.once('SIGTERM', () => void shutdown())

try {
  await app.listen({ host: config.host, port: config.port })
} catch (error) {
  app.log.error(error)
  process.exit(1)
}
