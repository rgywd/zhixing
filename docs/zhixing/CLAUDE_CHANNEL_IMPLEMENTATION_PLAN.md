# Claude Code 通道 P2 历史实施记录

状态：Historical / read-only（2026-07-19）；P2-1 至 P2-4 曾在 0.1.x 交付，0.2.x 不再维护写入通道。

> 当前 Codex-only 方向不要求兼容 Claude Code。本文只用于理解旧 Happy 数据和回滚，不得把 Claude
> “寻呼机”交互复制到当前 Codex Thread 页。active 合同见 `CODEX_NATIVE_ARCHITECTURE.md`。
对应需求：[Issue #18](https://github.com/rgywd/zhixing/issues/18)  
产品契约：[`AGENT_DESIGN.md`](./AGENT_DESIGN.md) 第 8 节

## 1. 目标与验收

P2 把 Claude Code 作为“寻呼机式”远端会话接入知行：开发机常驻 `zhixing-agent`，每条消息
通过官方 Claude Agent SDK 启动一轮 Claude Code，同一会话通过 Claude transcript 的 session id 恢复。App 只接收关键汇报、
结构化提问和 HTML 报告，不复制完整终端和逐工具事件流。

完整闭环必须满足：

1. 新建 Claude 会话时固定 session id、权限档、模型、effort 和硬限制；跨轮只允许 resume，
   不允许改变权限档。
2. 普通档的敏感工具调用由 Claude Agent SDK `canUseTool` 强制等待手机批准；超时默认拒绝。
   完全访问档显式使用 `--dangerously-skip-permissions`。
3. `report`、`ask`、`report_html` 三个 MCP 工具可用；断网时本地 bridge fail-open，Claude 进程不因
   上报链路永久阻塞。
4. App 复用工作会话页：文本汇报进入现有消息流，ask 使用交互卡，HTML 使用受限 WebView 卡片。
5. runner 心跳、进程持有和挂起 ask 分别产生在线、运行中、等待我和完成这些硬状态。

## 2. 当前协议事实

- 本机验收基线为 Claude Code `2.1.212`。
- 执行入口为官方 [`@anthropic-ai/claude-agent-sdk`](https://code.claude.com/docs/en/agent-sdk/typescript)
  `query()`；恢复使用 `resume`，新会话使用 `sessionId`，仍由 Claude Code 保存本机 transcript。
- 当前 CLI 不存在旧设计中的 `--permission-prompt-tool`。真机验证还确认：非交互 `claude -p`
  遇到审批会直接拒绝，不能用 CLI `PermissionRequest` Hook 构造远程交互闭环。官方程序化入口是
  Agent SDK `canUseTool`；P2-2 已切换到该入口。
- MCP 通过 SDK 的 `mcpServers` 注入；P2 不写入用户全局 Claude 配置。桥接 bearer 只通过子进程
  环境变量传入，不进入命令行、仓库或中继。
- permission mode 在会话创建时锁定。手机后续消息里的模式字段只用于一致性校验，不能改变已存在会话。

## 3. 分阶段交付

| 阶段 | 状态 | 范围 | RED / GREEN 验收 |
|---|---|---|---|
| P2-1 | 已完成 | Claude CLI adapter、短进程队列、session-id/resume、固定权限档、daemon 路由 | 参数契约单测；真实 CLI 新建/恢复；agent build/typecheck/test；App Happy 同步测试 |
| P2-2 | 已完成 | Agent SDK `canUseTool`、本机 bridge、MCP `report/ask/report_html`、断线降级 | 真 SDK deny；积压消息反向捎带；MCP 真调用；同 session resume |
| P2-3 | 已完成 | App ask/HTML/状态适配、搜索只索引汇报与提问文本 | parser/Room/UI 单测；HTML 安全策略；不展示逐工具日志 |
| P2-4 | 已完成 | 自托管中继部署、开发机真 CLI、Android 协议全链路 | 新建→report→ask→回复→resume→完成；普通/完全访问各一条；断线恢复 |

## 4. 文件地图

- `agent/src/claude/`：命令参数、子进程 adapter、runner、本机 HTTP bridge、MCP stdio server。
- `agent/src/daemon.ts`：Codex/Claude 复合 manager 与能力探测。
- `agent/src/types.ts`：Claude transcript id 与会话固定策略字段。
- `app/.../workflow/happy/`：Claude metadata、resume 参数、消息解析。
- `app/.../workflow/`：ask/HTML 数据类型和 Compose 展示。
- `deploy/happy-relay/`：固定版本的自托管中继镜像、兼容补丁与反向代理模板。

## 5. 兼容、回滚与非目标

- Codex P1 路径保持不变；Claude adapter 不可用时机器仍注册，但 `cliAvailability.claude=false`。
- Claude 通道默认向 App 宣告可用；紧急回滚时可在开发机设置 `ZHIXING_ENABLE_CLAUDE_P2=0`，
  Codex 通道不受影响。
- 已有 Happy/Claude 会话仍可读；缺少 `claudeSessionId` 的旧会话不能原生 resume 时，明确降级为新会话。
- 每个阶段通过独立 commit/PR 验收；任一阶段可回滚，不迁移或删除现有会话数据。
- P2 不做 transcript tail、逐工具直播、Claude 完整日志页、工具活动级 FTS、多 Agent 协作。

## 6. 验证命令

```bash
cd agent
npm test
npm run typecheck
npm run build

./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

P2-4 另执行真机 smoke，并记录 Claude Code 版本、自托管中继 URL、权限档、session id、退出状态和
断网恢复结果；恢复密钥、Bearer token、MCP bridge token 不进入日志或 Git。

### P2-1 实际验证（2026-07-18）

- Claude Code `2.1.212`：同一 UUID 首轮 `--session-id` 返回 `P2-NEW`，第二轮
  `--resume` 返回 `P2-RESUME`。
- 编译后的 `ClaudeCliProcess` 带 `--disallowedTools` 真实启动，返回 `P2-ADAPTER`；确认 prompt
  必须紧跟 `-p`，否则 variadic deny 参数会吞掉 prompt。
- `agent`：22 tests passed；`npm run typecheck`、`npm run build` 通过。
- Android：`HappySyncApiTest` 通过；Gradle `BUILD SUCCESSFUL`。

### P2-2 实际验证（2026-07-18）

- `agent`：29 tests passed；`npm run typecheck`、`npm run build` 通过。
- 真 Claude Agent SDK 权限烟测：`Bash(git push --dry-run)` 的 tool/input 到达 `canUseTool`，远程
  回调拒绝后未执行，Claude 收到拒绝结果。
- 真 MCP 烟测：Claude 调用 `mcp__zhixing__report("SDK-MCP-SMOKE")`，取得 bridge 返回的唯一积压词；
  第二轮用同一 session id resume，能准确复述该词。
- 设计修正：原计划的 CLI `PermissionRequest` Hook 在 `claude -p` 下无法形成交互审批，已删除该
  假闭环，改用官方 SDK 回调；完全访问档仍显式映射为 `bypassPermissions`。

### P2-3 实际验证（2026-07-18）

- `claude-ask` 作为原生交互卡进入既有工作会话流，支持单选、多选、明确提交和底部输入自由答复；
  不增加配置页或独立会话体系。
- `claude-report-html` 作为原生报告卡进入消息流，打开独立只读页面；WebView 关闭 JavaScript、
  DOM/文件/内容/网络访问，并注入限制 CSP，仅允许内联样式与 `data:` 图片。
- 工作会话 FTS 只收录用户/助手文本、提问文本与报告标题，排除思考、工具、原始事件和 HTML 正文。
- `agent`：29 tests passed，typecheck/build 通过；Android parser/UI mapper/HTML/FTS 定向测试通过。

### P2-4 实际验证（2026-07-18）

- 自托管中继 `https://happy.8-208-118-119.sslip.io` 运行
  `happy-server-self-host@1.1.11`，公网 `/health` 和容器健康检查通过；部署前已备份持久卷。
- Claude Code `2.1.212` 在最终部署镜像上完成生产协议烟测，普通档会话 `cmrpu3jri0002nb0xa24hdtia`、
  完全访问档会话 `cmrpu5nj20010nb0xsg1tm58s`；标记 `P2-MRPU3F5R`。恢复密钥、Bearer 和 bridge token
  均未输出或入库。
- 同一普通档会话完成 `report → ask → 选择 A → report_html → turn-end → resume`；第二个进程准确
  恢复前一轮选择。普通档 `Write` 在 `agentState.requests` 等待，经加密 RPC 批准后真实落盘；完全访问档
  同类 `Write` 无审批落盘。探针内容均由脚本读回验证并自动清理。
- 普通档完成后主动断开机器 Socket，再连接并通过同一机器 RPC 新建完全访问会话，证明重连会重新注册
  handler，断线不会要求重启 daemon。
- `npm run smoke:p2` 使用与生产一致的编译产物。App 侧使用同一恢复密钥、加密记录和 Happy API 契约；
  自动化环境无 ADB 设备，因此实体手机的最终触控/视觉验收由安装 release 后执行，不把它伪报为自动化结果。
