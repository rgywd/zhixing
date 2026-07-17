# Claude Code 通道 P2 实施计划

状态：进行中（2026-07-18）；P2-1 已完成，下一阶段 P2-2。
对应需求：[Issue #18](https://github.com/rgywd/zhixing/issues/18)  
产品契约：[`AGENT_DESIGN.md`](./AGENT_DESIGN.md) 第 8 节

## 1. 目标与验收

P2 把 Claude Code 作为“寻呼机式”远端会话接入知行：开发机常驻 `zhixing-agent`，每条消息
启动一次 `claude -p`，同一会话通过 Claude transcript 的 session id 恢复。App 只接收关键汇报、
结构化提问和 HTML 报告，不复制完整终端和逐工具事件流。

完整闭环必须满足：

1. 新建 Claude 会话时固定 session id、权限档、模型、effort 和硬限制；跨轮只允许 resume，
   不允许改变权限档。
2. 普通档的敏感工具调用由 Claude Code `PermissionRequest` Hook 强制等待手机批准；超时默认拒绝。
   完全访问档显式使用 `--dangerously-skip-permissions`。
3. `report`、`ask`、`report_html` 三个 MCP 工具可用；断网时本地 bridge fail-open，Claude 进程不因
   上报链路永久阻塞。
4. App 复用工作会话页：文本汇报进入现有消息流，ask 使用交互卡，HTML 使用受限 WebView 卡片。
5. runner 心跳、进程持有和挂起 ask 分别产生在线、运行中、等待我和完成这些硬状态。

## 2. 当前协议事实

- 本机验收基线为 Claude Code `2.1.212`。
- headless：`claude -p`；恢复：`--resume <session-id>`；新会话可用
  `--session-id <uuid>`；结构化结果使用 `--output-format json`。
- 当前 CLI 不存在旧设计中的 `--permission-prompt-tool`。官方受支持替代是
  [`PermissionRequest` Hook](https://code.claude.com/docs/en/hooks)：Hook 可同步返回
  `hookSpecificOutput.decision.behavior=allow|deny`。
- MCP 通过 `--mcp-config` 注入；P2 不写入用户全局 Claude 配置。
- permission mode 在会话创建时锁定。手机后续消息里的模式字段只用于一致性校验，不能改变已存在会话。

## 3. 分阶段交付

| 阶段 | 状态 | 范围 | RED / GREEN 验收 |
|---|---|---|---|
| P2-1 | 已完成 | Claude CLI adapter、短进程队列、session-id/resume、固定权限档、daemon 路由 | 参数契约单测；真实 CLI 新建/恢复；agent build/typecheck/test；App Happy 同步测试 |
| P2-2 | 待开始 | 本机 bridge、PermissionRequest Hook、MCP `report/ask/report_html`、断线降级 | allow/deny/超时；积压消息反向捎带；MCP JSON-RPC；断网不死锁 |
| P2-3 | 待开始 | App ask/HTML/状态适配、搜索只索引汇报与提问文本 | parser/Room/UI 单测；HTML 安全策略；不展示逐工具日志 |
| P2-4 | 待开始 | 自托管中继部署、开发机真 CLI、Android 真机全链路 | 新建→report→ask→回复→resume→完成；普通/完全访问各一条；断网恢复 |

## 4. 文件地图

- `agent/src/claude/`：命令参数、子进程 adapter、runner、本机 HTTP bridge、MCP stdio server。
- `agent/src/daemon.ts`：Codex/Claude 复合 manager 与能力探测。
- `agent/src/types.ts`：Claude transcript id 与会话固定策略字段。
- `app/.../workflow/happy/`：Claude metadata、resume 参数、消息解析。
- `app/.../workflow/`：ask/HTML 数据类型和 Compose 展示。
- `deploy/happy-relay/`：P2-4 才加入中继侧组件；P2-1 不改变生产部署。

## 5. 兼容、回滚与非目标

- Codex P1 路径保持不变；Claude adapter 不可用时机器仍注册，但 `cliAvailability.claude=false`。
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
