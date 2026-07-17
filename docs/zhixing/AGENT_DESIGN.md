# zhixing-agent 设计契约（P1：Codex 通道）

状态：立项文档（2026-07-17），未开始编码
决策记录：进本仓 `agent/` 目录；P1 仅做 Codex；Claude Code 通道（P2）暂缓，待产品侧进一步研究后另行立项。

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
4. App 侧配套（小改动）：spawn/meta 增加 reasoningEffort 字段；中继服务器地址设置项；
   预设页 Codex 思考深度解禁。
5. 自托管 relay 部署文档与一键 Docker Compose；真实链路验收（自托管中继 + zhixing-agent
   + 手机全链路）。

## 7. 非目标（P1）

- 不做 Claude Code 通道（P2 另行立项，方向预研：Claude Agent SDK + hooks 硬限制 +
  per-session settings，"改造而非复用"）。
- 不做多账户/团队权限。
- 不做 agent 静默自更新。
- 不迁移历史会话数据（自托管中继启用后从零开始积累，旧会话仍可在官方中继读取）。
