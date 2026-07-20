import { join } from 'node:path'
import type { AgentHome } from '../config.js'
import { AGENT_VERSION } from '../config.js'
import { AppServerProcess } from './appServerProcess.js'
import { loadOrCreateToken } from './secrets.js'
import { SupervisorStatusServer } from './statusServer.js'
import { probeTailscale } from './tailscaleProbe.js'
import { AttachmentStore } from './attachmentStore.js'

export class CodexSupervisor {
  private readonly appServer: AppServerProcess
  private readonly supervisorToken: string
  private readonly statusServer: SupervisorStatusServer
  private readonly attachments: AttachmentStore
  private attachmentSweepTimer: NodeJS.Timeout | null = null

  constructor(
    home: AgentHome,
    logger: (message: string) => void = () => {},
    statusPort?: number,
  ) {
    this.appServer = new AppServerProcess(home, {}, logger)
    this.attachments = new AttachmentStore(join(home.dir, 'uploads'))
    this.supervisorToken = loadOrCreateToken(join(home.dir, 'supervisor-token'))
    this.statusServer = new SupervisorStatusServer(
      {
        status: async () => ({
          supervisorVersion: AGENT_VERSION,
          appServer: this.appServer.status(),
          tailscale: await probeTailscale(),
        }),
        restartAppServer: () => this.appServer.restart(),
        uploadAttachment: (input) => this.attachments.put(input.body, {
          fileName: input.fileName,
          mime: input.mime,
          expectedSha256: input.sha256,
        }),
        deleteAttachment: (attachmentId) => this.attachments.remove(attachmentId),
      },
      this.supervisorToken,
      statusPort,
    )
  }

  get credentials(): {
    appServerLoopbackUrl: string
    appServerToken: string
    supervisorLoopbackUrl: string
    supervisorToken: string
  } {
    return {
      appServerLoopbackUrl: this.appServer.loopbackUrl,
      appServerToken: this.appServer.bearerToken,
      supervisorLoopbackUrl: this.statusServer.configuredLoopbackOrigin,
      supervisorToken: this.supervisorToken,
    }
  }

  async start(): Promise<void> {
    await this.attachments.initialize()
    await this.appServer.start()
    try {
      await this.statusServer.start()
      this.attachmentSweepTimer = setInterval(() => void this.attachments.sweep(), 10 * 60_000)
    } catch (error) {
      await this.appServer.stop()
      throw error
    }
  }

  async stop(): Promise<void> {
    if (this.attachmentSweepTimer) clearInterval(this.attachmentSweepTimer)
    this.attachmentSweepTimer = null
    await this.statusServer.stop()
    await this.appServer.stop()
  }
}
