# zhixing-agent 设计契约（P1：Codex 通道 / P2：Claude 通道）

状态：立项文档（2026-07-17），未开始编码
决策记录：进本仓 `agent/` 目录；P1 仅做 Codex（编码优先级最高）；Claude Code 通道（P2）
设计已于 2026-07-17 批准（见第 8 节，司南式"寻呼机"模型），编码在 P1 之后另行启动。

## 1. 动机

当前链路 `知行 App ⇄ Happy Relay ⇄ happy CLI ⇄ Codex` 有三个结构性问题：

1. **参数透传断层**：Codex 官方 app-server 协议支持每会话/每轮的 model、reasoning effort、
   sandbox、approval-policy 全量配置，但 happy CLI 的适配器只透传 model 与 permissionMode。
   手机端已具备的"思考深度"设置对 Codex 无法生效。
2. **上游停摆风险**：happy-cli / happy-server 独立仓已归档（2026-02），monorepo 虽活跃但为
   单维护者项目；托管中继（Happy 官方服务端）维护期无任何承诺。
3. **数据面依赖第三方**：消息内容虽为端到端加密（中继只见密文），但可用性、元数据
   （会话频率、在线时间、IP）与账户体系仍托管在他人基建上。

目标：**开发机侧适配器与服务端基建全部自主**，不再依赖他人微服务。

## 2. 目标架构

```text
知行 App（已实现 Session Protocol v2 客户端）
   ⇄ Relay（阶段甲：Happy 官方中继；阶段乙：自托管）
   ⇄ zhixing-agent（本仓 agent/，自研，替代 happy CLI）
   ⇄ codex app-server（官方 JSON-RPC 协议，全参数）
```

- **对 App 的协议不变**：zhixing-agent 对外说 Session Protocol v2（9 种事件）与既有
  machine RPC（spawn 等），App 侧只做增量扩展，不做破坏性改动。
- **对 Codex 说官方协议**：JSON-RPC（app-server / MCP 接口），newConversation 与逐轮
  参数完整映射。

### 2.1 服务端脱钩路线（数据安全主诉求）

| 阶段 | Relay | 说明 |
|---|---|---|
| 甲（P1 过渡） | Happy 官方中继 | 维持现状，E2E 密文，风险=可用性+元数据 |
| 乙（P1 内完成） | **自托管 happy server** | monorepo MIT 且自带 `Dockerfile.server`，部署到自己的 VPS；App 增加"中继服务器地址"设置项（当前 `HappyProtocol.SERVER_URL` 是常量，需改造为可配置）；账户/加密协议不变，数据面完全归自己 |
| 丙（远期可选） | 自研薄中继 | 仅当自托管 server 维护成本过高时考虑；Session Protocol v2 事件仅 9 种，中继只需盲转发密文 + 推送 |

阶段乙完成后即达成"第一不用依赖他人微服务"：中继在自己 VPS，适配器是自己代码，
Happy 上游停摆不再影响任何一环。

## 3. Codex 参数映射（P1 核心）

zhixing-agent 侧 Codex 会话建立与逐轮控制，全部走 codex app-server 协议：

| 手机端概念 | Session Protocol 载体 | codex app-server 参数 |
|---|---|---|
| 模型 | spawn 参数 / 消息 meta `model` | `model`（如 gpt-5.6-sol） |
| 思考深度 | spawn 参数 / 消息 meta `reasoningEffort`（**新增 meta 字段**） | `model_reasoning_effort`：none/minimal/low/medium/high/xhigh/max/ultra |
| 普通模式 | meta `permissionMode=default` | `approval-policy=on-request` + `sandbox=workspace-write` |
| 完全访问 | meta `permissionMode=bypassPermissions` | `approval-policy=on-failure` + `sandbox=danger-full-access` |
| 硬性限制 | meta `disallowedTools`（规则串） | **agent 层强制**：审批回调中对命令做规则匹配，命中即自动拒绝并上报 `service` 事件（解决 Codex 无 deny list 的空白——这是 happy 链路做不到的） |
| 审批 | 既有 permission RPC | app-server 审批回调 ⇄ 手机审批卡 |

注意：`reasoningEffort` 与 `disallowedTools` 的 meta 字段由 zhixing-agent 定义并消费，
happy CLI 会忽略它们——同一账户下两种 CLI 可并存，互不破坏。

## 4. 复用与自研边界

| 模块 | 来源 |
|---|---|
| 账户/E2E 加密（seed、data key、secretbox/AES-GCM）| 移植 slopus/happy monorepo（MIT），与 App 侧已有实现互操作 |
| Relay 连接（Socket.IO user/session scope、RPC 帧）| 同上移植 |
| Session Protocol v2 事件编码 | 按 `docs/session-protocol.md` 自研（App 侧解析器已是现成的对照实现） |
| Codex app-server 客户端 | 自研，参照 openai/codex 的 app-server-protocol 定义 |
| 守护进程/机器注册/目录管理 | 移植+裁剪 happy CLI daemon |

技术栈：TypeScript / Node 20+（与 codex、happy 生态一致），`agent/` 目录独立
package.json，不进 Android 构建图。

## 5. 安装与运行（Windows 优先）

- 分发：npm 包 `zhixing-agent`（`npx zhixing-agent daemon`），后续考虑单文件打包。
- 配置：`~/.zhixing-agent/`；账户凭据与 happy CLI 隔离存放，支持导入同一恢复密钥。
- 回退：happy CLI 可继续并存运行；App 端机器列表按 daemon 上报的 cliAvailability 区分。
- 自更新：P1 只做版本检查提示，不做静默自更新。

## 6. P1 交付与验收

1. `agent/` 骨架：账户登录（恢复密钥）、机器注册、relay 连接、加密收发。
2. Codex 会话闭环：spawn（含 model/effort/sandbox）、事件流（text/tool-call/turn）、
   审批回调、abort。验收：手机设 effort=xhigh，开发机 codex 实际以 xhigh 运行（
   以 codex 会话日志为证）。
3. 硬性限制 agent 层强制：预设内规则命中时自动拒绝并回传 service 事件。
4. [x] App 侧配套：spawn/meta 增加 reasoningEffort 字段；中继服务器地址设置项；
   预设页 Codex 思考深度解禁。中继切换会清除旧 origin 的 token 与本地缓存，防止凭据串站。
5. [x] 自托管 relay 已部署到独立 VPS，HTTPS、持久卷、备份、机器注册、会话、消息、审批 RPC
   与断线重连均通过生产端点验证。

## 7. 非目标（P1）

- P1 不实现 Claude Code 通道（设计已定稿于第 8 节，编码在 P1 之后启动）。
- 不做多账户/团队权限。
- 不做 agent 静默自更新。
- 不迁移历史会话数据（自托管中继启用后从零开始积累，旧会话仍可在官方中继读取）。

## 8. P2：Claude 通道（司南式"寻呼机"模型）

状态：已完成（2026-07-18）；P2-1（Claude 短进程、resume、固定策略和 daemon 路由）、
P2-2（Agent SDK 审批、本机 bridge、MCP 电话线）、P2-3（App 原生 ask/HTML/搜索边界）与
P2-4（自托管中继和生产协议闭环）均已实现并验证。
方向定调："对 Claude Code 改造而非复用"——彻底甩开 happy 对 Claude 的包裹层，
只使用官方稳定面：Claude Agent SDK（驱动 Claude Code、resume 本机 transcript）+ MCP。
参考来源：司南「会话」交互机制（同事方案），采纳其进程模型与电话线设计，
权限部分做强化改良（见 8.3）。

### 8.1 产品形态（与 Codex 通道刻意不同）

Claude 会话是"寻呼机"而非"镜像"：agent 在开发机上自主干活，只在关键节点
主动汇报/提问；**不提供**实时工具调用直播、完整日志页与工具活动级 FTS
（内容搜索仅覆盖汇报/提问文本）。这是有意识的产品取舍，换取零协议耦合与
进程级鲁棒性。

### 8.2 进程模型（用完即走 + resume）

- 开发机常驻一个小 runner（systemd 级守护，zhixing-agent 的 Claude 适配器）。
- 每条用户消息触发一轮 Agent SDK `query({ resume: sessionId })`，底层 Claude Code 干完即退；
  无长活 claude 进程，不怕崩、关机重启不丢。
- 会话真身 = Claude 磁盘 transcript（`~/.claude/projects/…/<sid>.jsonl`）；
  "接着聊" = resume 同一 id，"新会话" = 不带 resume。
- 韧性：runner 与核心之间断连 fail-open，不把活卡死。

### 8.3 权限两档（建会话时一锤定音，无法切换）

| 档位 | 起进程参数 | 效果 |
|---|---|---|
| 普通 | Agent SDK `permissionMode=default` + `canUseTool` | 敏感操作弹手机审批卡，SDK 强制阻塞；超时默认拒绝 |
| 完全访问 | `--dangerously-skip-permissions` | 与 Codex 完全访问档对齐 |

- 档位在**新建会话时**选定，会话内、跨轮均**不提供切换**；要换档 = 新建会话。
  （用户拍板 2026-07-17；也与"每轮新进程、参数起时定"的模型天然一致。）
- 相比司南原案（直接 skip-permissions，HITL 靠 agent 自觉调 ask）是强化：
  普通档的审批是 CLI 强制的，不是君子协定。
- 2026-07-18 联调确认：Claude Code 2.1.212 已不提供早期设计引用的
  `--permission-prompt-tool` 参数；且非交互 `claude -p` 在需要审批时直接拒绝，CLI
  `PermissionRequest` Hook 没有机会完成远程交互。P2 因此使用官方 Agent SDK `canUseTool`
  回调，真实 deny 烟测已通过。

### 8.4 电话线（中继侧 MCP server，三个工具）

- `report(text)`：即时汇报 → 一条助手消息；立即返回，**返回值捎带用户在
  agent 埋头期间积压的消息**（借"工具调用必有返回值"做反向信道）。
- `ask(question)`：结构化提问（1–4 题、可多选、永远有"其他"自由输入），
  短挂 2–3 分钟等秒回；超时先收工，用户答复随下一轮 resume 带入。
  不死挂长连接，过反代不脆。
- `report_html(html, title)`：大段内容出 HTML 报告卡，App 内 WebView 渲染。

### 8.5 状态与数据面

- 状态靠硬信号，不从事件流推断：*跑着* = runner 持有进程；*等你* = 有挂起
  的 ask；*完成* = 进程退出且无挂起 ask；*在线* = runner 心跳。
- 服务端组件（MCP endpoint + 会话/消息表 + WS 推送）与自托管中继（2.1 阶段乙）
  同机部署。Claude 通道消息为明文落自己服务器（非 E2E），安全模型 = 服务器
  归属自己；与 Codex 通道的 E2E 密文链路并存互不影响。
- App 侧映射（不新增页面）：report → 助手文本消息（走既有渲染管线）；
  ask → 交互卡片（ApprovalCard 改造）；report_html → WebView 报告卡（小增量）。

### 8.6 明确不做（P2 范围内）

- 不做 transcript tail 全保真回传（曾作为可选增强提出，用户拍板先不做；
  如未来需要再另行评估——进程模型不堵死该路径）。
- 不做档位会话内切换（见 8.3）。
- 不做逐工具直播、Claude 会话完整日志页、工具活动级 FTS（见 8.1）。
