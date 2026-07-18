# zhixing-agent 协议笔记（研读产出）

来源：slopus/happy monorepo（MIT，本地参考克隆 `zhixing-refs/happy`）+ openai/codex
（`zhixing-refs/codex`）+ App 侧 `ui/pages/workflow/happy/` 对照实现。
本文是编码期间的**事实清单**，与 `docs/zhixing/AGENT_DESIGN.md`（契约）配合使用。

## 1. 账户与认证

- 恢复密钥 = 32 字节账户 secret；编码：base64url 或分组 base32
  （字母表 `A-Z2-7`，纠错映射 0→O/1→I/8→B/9→G）。
- HTTP 认证：`POST /v1/auth`，body `{challenge, publicKey, signature}`（均 base64）。
  challenge 是客户端自选的 32 随机字节；Ed25519 密钥对 = `sign.keyPair.fromSeed(secret)`；
  detached 签名。响应 `{success, token}`（Bearer）。
  Header `X-Happy-Client: <client>/<version>`（zhixing-agent 用 `zhixing-agent/<ver>`）。
- happy CLI 正式版走手机扫码授权（`/v1/auth/request`），CLI 只拿到
  `{publicKey, machineKey}` 不持有账户 secret。**zhixing-agent P1 简化**：直接导入
  恢复密钥（与 App 同源），账户 secret 在本机可用——内容公钥可自行派生。

## 2. 加密（与 App 侧逐字节一致）

- 派生链（HMAC-SHA512 树）：root = HMAC(key="Happy EnCoder Master Seed", data=secret)；
  child = HMAC(key=root[32..64], data=0x00||"content")；contentSeed=child[0..32]；
  boxSecret = SHA512(contentSeed)[0..32]；内容公钥 = X25519 from boxSecret。
- dataKey 打包：`0x00 || ephemeralPub(32) || nonce(24) || nacl.box 密文`，base64。
  会话：随机 32 字节内容密钥；机器：稳定 machineKey（我们同样随机生成并持久化）。
- 记录加密变体：
  - `dataKey`：AES-256-GCM，`0x00 || nonce(12) || cipher || tag(16)`
  - `legacy`：secretbox，`nonce(24) || cipher+tag`
- 权威文档：happy `docs/encryption.md`。

## 3. Socket.IO（机器侧）

- 连接：`path=/v1/updates`，仅 websocket 传输，
  auth `{token, clientType: 'machine-scoped', machineId, happyClient}`。
- RPC：服务端→agent 事件 `rpc-request` `{method, params}`，回调返回字符串；
  agent 注册：emit `rpc-register` `{method: '<machineId>:<名字>'}`（重连后需重发）。
  params/result 均为 base64(加密(JSON))，用机器 dataKey。
- 心跳：`machine-alive` `{machineId, time}` 每 20s。
  其他：`machine-update-metadata`、`machine-update-state`、服务端 `update`。
- 会话 scope 类似：`clientType:'session-scoped'` + sessionId；事件
  `session-alive`/`session-end`/`update-metadata`/`update-state`/`ping`。

## 4. HTTP API

- `POST /v1/machines` `{id, metadata(密文b64), daemonState(密文b64), dataEncryptionKey(b64)}`
  → `{machine}`。machineId 本地生成（UUID）并持久化。
- `POST /v1/sessions` `{tag(随机UUID), metadata(密文), agentState(密文|null), dataEncryptionKey}`
  → `{session:{id, seq, ...}}`。
- 消息上行：`POST /v3/sessions/:id/messages` `{messages:[密文b64…]}`，批量 ≤50。
- 消息下行：`GET /v3/sessions/:id/messages?after_seq=&limit=100` + socket `update` 触发。

## 5. Session Protocol v2 上行封装

- 外层记录：`{role:'session', content:<envelope>, meta:{sentFrom:'cli'}}`，整体加密。
- envelope：`{id(cuid2), time, role:'user'|'agent', turn, subagent?, ev, usage?}`；
  ev.t ∈ text / service / tool-call-start / tool-call-end / file / turn-start / turn-end / start / stop。
- 参考：happy `packages/happy-wire/src/sessionProtocol.ts` 与 `docs/session-protocol.md`。

## 6. spawn RPC 契约（新版 happy CLI，App P1-4 可对齐）

`spawn-happy-session` params：`{directory, sessionId?, machineId, approvedNewDirectoryCreation,
agent, permissionMode?, modelMode?, effortLevel?, environmentVariables?, token?,
resumeCodexThreadId?, parentSessionId?, forkedFromMessageId?}`
返回 `{type:'success', sessionId}` | `{type:'requestToApproveDirectoryCreation', directory}` | 错误。

## 7. Codex 集成（happy 实测映射）

- 启动：`codex app-server`（stdio，逐行 JSON-RPC 2.0）。
- 执行策略映射（happy `codex/executionPolicy.ts`，我们两档取其子集）：
  - 普通 `default` → approvalPolicy=`untrusted`, sandbox=`workspace-write`
  - 完全访问 `bypassPermissions` → approvalPolicy=`never`, sandbox=`danger-full-access`，
    审批自动通过（不会再有回调打给手机）
- happy 侧 effort 校验集：none/minimal/low/medium/high/xhigh（codex 本体枚举更全，
  以 app-server 协议探查为准）。
- （app-server JSON-RPC 方法/事件明细见第 8 节，由 codex 仓库探查补全。）

## 8. codex app-server 协议明细（v2 Thread/Turn/Item，源码 commit 3151954）

**版本要点**：`newConversation`/`sendUserMessage`/`sendUserTurn` 已从最新协议移除；
目标 API 是 v2 线程模型。权威文档 = codex 仓库 `codex-rs/app-server/README.md`
（无独立 docs/app-server.md）；机器可读 schema 在
`codex-rs/app-server-protocol/schema/json/`。随 codex 版本快速演进——
**联调时用 `codex app-server generate-ts` 对齐所装版本**，不要硬编码假设。

- **传输**：`codex app-server`（默认 stdio），逐行 JSON（JSONL），**无** Content-Length
  帧、**无** `jsonrpc` 字段。请求 `{id, method, params}`，通知无 id，响应 `{id, result}`，
  错误 `{id, error:{code,message}}`。过载 = `-32001`。
- **握手**：每连接先发一次 `initialize`（`clientInfo{name,title,version}` +
  `capabilities{experimentalApi:false}`），收响应后发 `initialized` 通知。
- **建线程**：`thread/start` `{model?, cwd?, approvalPolicy?, sandbox?, config?{...}}`
  → `{thread, reasoningEffort?, ...}` + `thread/started` 通知并自动订阅事件。
  首轮 effort 走 `config.model_reasoning_effort` 或逐轮 `turn/start.effort`。
- **发消息**：`turn/start` `{threadId, input:[{type:'text',text}], model?, effort?,
  approvalPolicy?, sandboxPolicy?(完整对象), permissions?}`——逐轮覆盖并成为线程新默认。
  `turn/steer` 往进行中的轮注入输入（需 expectedTurnId）。
- **事件流**（通知，节选）：`turn/started`、`item/started`、`item/completed`
  （item 类型：userMessage/agentMessage/reasoning/commandExecution/fileChange/
  mcpToolCall/webSearch/plan…）、`item/agentMessage/delta`、
  `item/reasoning/summaryTextDelta|textDelta`、`item/commandExecution/outputDelta`、
  `item/fileChange/patchUpdated`、`thread/tokenUsage/updated`、`turn/completed`
  （turn.status ∈ completed/interrupted/failed/inProgress）、`error`。
  注意 turn/completed 的 items 数组为空，item 清单以 item/* 通知为准。
- **审批**（服务端→客户端 JSON-RPC **请求**，客户端回响应）：
  - `item/commandExecution/requestApproval` `{threadId,turnId,itemId,command?,cwd?,reason?}`
    → `{decision}`，decision ∈ `accept` / `acceptForSession` / `decline`（拒但轮继续）/
    `cancel`（拒并中断轮）
  - `item/fileChange/requestApproval` `{...,grantRoot?}` → 同上四值
  - 应答后服务端发 `serverRequest/resolved`。
- **停止**：`turn/interrupt` `{threadId, turnId}` → 轮以 `interrupted` 收尾。
- **恢复**：`thread/resume` `{threadId, ...同 start 覆盖}`；默认沿用持久化的
  model/reasoningEffort。分叉：`thread/fork`。
- **枚举 wire 值**（以 serde 源码为准，README 散文有出入）：
  - AskForApproval（kebab-case）：`untrusted` / `on-request` / `never`
  - SandboxMode（kebab-case）：`read-only` / `workspace-write` / `danger-full-access`
  - SandboxPolicy（对象，type camelCase）：`dangerFullAccess` / `readOnly` /
    `workspaceWrite{writableRoots?,networkAccess?}` / `externalSandbox`
  - TurnStatus：`completed` / `interrupted` / `failed` / `inProgress`
  - **ReasoningEffort：自由非空字符串**（不再是封闭枚举）；各模型经 `model/list` 的
    `supportedReasoningEfforts` 广播支持档位。客户端应透传 + 用广播值做 UI 校验。
- **登录**：app-server 可先起服务后登录；`account/read` 查状态，
  `account/login/start`（type: apiKey/chatgpt/chatgptDeviceCode）。P1 假定开发机已
  `codex login`，agent 只在 `account/read` 显示未认证时上报 service 事件提示。

### 我们两档 → v2 参数映射（P1 最终版）

| 档位 | approvalPolicy | sandbox | 审批处理 |
|---|---|---|---|
| 普通 `default` | `untrusted` | `workspace-write` | requestApproval 转手机审批卡 |
| 完全访问 `bypassPermissions` | `never` | `danger-full-access` | 不会产生审批回调 |

## 9. Claude Code P2 协议基线（2.1.212）

- **短进程**：官方 Claude Agent SDK `query()`；新会话用 `sessionId`，后续轮用 `resume`。
  SDK 仍驱动本机 Claude Code、持久化相同 transcript；P2 不依赖 TUI 或私有事件流。
- **模型与 effort**：分别使用 SDK `model`、`effort`；未知或空值不下发，沿用 Claude 默认。
- **完全访问**：显式 `permissionMode=bypassPermissions`，只允许在新建会话时选定。
- **普通审批**：2.1.212 已没有 `--permission-prompt-tool`；非交互 `claude -p` 需要审批时会
  直接拒绝，不能靠 `PermissionRequest` Hook 做远程交互。使用 Agent SDK `canUseTool`，把
  tool name 与完整 input 映射到 SessionRuntime 审批；超时和 bridge 异常默认 deny。
- **硬限制**：通过 SDK `disallowedTools` 传给 Claude Code，deny 规则不可被手机批准覆盖。
- **MCP**：SDK `mcpServers` 注入 P2 电话线，并保留用户/项目已有 MCP 和设置，确保开发机原生
  context 不丢。电话 MCP server 只暴露 `report`、`ask`、`report_html`。
- **配置隔离**：P2 运行时设置与 token 都放在内存/环境中，不修改用户或项目
  `.claude/settings*.json`。
