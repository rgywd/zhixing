# 原生远程工作流契约

状态：数据层重构进行中（2026-07-16 起，按 Issue #18 重构为移动 Coding Session 客户端）

关联需求：[GitHub Issue #10](https://github.com/rgywd/zhixing/issues/10)、[GitHub Issue #18](https://github.com/rgywd/zhixing/issues/18)

## 1. 目标

知行在自身 Compose 界面中管理开发机上的 Coding Agent。手机只连接中继服务，不直接访问开发机、SSH、终端端口或 Codex app-server。

第一条接入链路复用 Happy 的开放协议和现有 `happy codex` 电脑端：

```text
知行 Compose UI
  -> Happy Kotlin Client Core
  -> HTTPS + Socket.IO + 端到端加密
  -> Happy Relay
  -> 开发机 Happy CLI
  -> Codex app-server
```

Happy Web 页面只保留为开发期故障回退，不作为正式产品界面或账户数据源。

## 2. P0 行为

1. 用户可导入已有 Happy 恢复密钥，或通过另一台已登录设备批准新设备连接。
2. 身份密钥仅以 Android Keystore 保护的密文持久化；日志、崩溃信息和诊断包不得包含恢复密钥、Bearer token 或解密后的敏感消息。
3. “工作”首页只展示按 `machineId + 工作目录` 聚合的 Codex 项目；连接凭据、机器与协议诊断属于设置，不占用工作界面。
4. 项目页展示完整历史对话，不以 15 分钟活跃窗口作为可见性或保留条件；用户可从手机直接在项目目录创建任务，并恢复已结束对话。
5. 会话页原生显示用户消息、Agent 文本、思考状态、工具调用和错误；Socket 断线后通过 HTTP 增量读取补齐事件。
6. 用户可发送补充要求、中断任务，并对需要人工确认的操作执行允许、拒绝或取消。
7. 所有有副作用的远程操作必须显示目标机器、项目、会话和操作摘要，不提供全局静默自动批准。

## 3. 协议边界

首个兼容目标固定为 2026-07-17 的 `slopus/happy` 主干提交
`3f161de70541b1cedaf0b7547ed70889d8dae22d`，并在代码中声明客户端协议版本。升级 Happy CLI 或 Relay
前必须先运行契约测试。

### 3.1 认证

- 恢复密钥是 32 字节 seed，可用 Happy 的 Base32 分组格式或 Base64URL 导入。
- token 获取：从 seed 生成 Ed25519 keypair，签名随机 32 字节 challenge，调用 `POST /v1/auth`。
- 设备连接：生成 Curve25519 box keypair，调用 `/v1/auth/account/request`，由已有设备批准后解密返回的账户 seed。
- HTTP 与 Socket.IO 均使用同一 Bearer token；客户端标识使用 `android/<知行版本>`。

### 3.2 同步

- HTTP 用于初始机器、会话和历史消息读取。
- Socket.IO 路径固定为 `/v1/updates`，连接类型为 `user-scoped`。
- 持久事件依赖用户级单调 `seq` 排序；断线或发现序列缺口时重新增量读取，不猜测缺失状态。
- `metadataVersion`、`agentStateVersion` 等版本字段必须参与乐观并发控制。

### 3.3 加密

- 兼容旧数据的 XSalsa20-Poly1305 secretbox。
- 当前数据使用每会话/每机器 AES-256-GCM data key。
- data key 通过 NaCl box 封装；解密失败只隔离对应对象，不清空其他会话，也不回退为明文。
- token 用于鉴权，seed/data key 用于内容加密；两者不得互相替代或写入普通偏好设置。

### 3.4 控制 RPC

- 会话 RPC 方法名为 `<sessionId>:<method>`，参数和结果均使用该会话密钥加密。
- 机器 RPC 方法名为 `<machineId>:<method>`，用于创建或恢复会话。
- `spawn-happy-session` 支持 `agent: codex | claude | gemini`；新版 happy-cli 已移除
  `resume-happy-session`，恢复统一为 `spawn-happy-session` 携带 `sessionId`。客户端先调旧方法，
  被拒绝后回退新协议。
- 超时、目标离线、版本冲突和解密失败必须是不同错误类型，UI 不得统一显示为“连接失败”。

### 3.4.1 Session Protocol v2 记录格式

生产链路的消息记录为外层 `role=session`、content 为 `{id,time,role,turn,subagent,ev}` 信封，
`ev.t` 共 9 种事件（slopus/happy `docs/session-protocol.md`）：`text`（正文，markdown，
`thinking:true` 为思考）、`service`、`tool-call-start/end`、`file`、`turn-start/end`、
`start/stop`（子代理）。客户端解析必须以该词表为准；未知事件带文本时降级展示，不得丢弃。

### 3.5 消息 meta 与执行策略

- 执行模式、模型与工具限制不是 spawn 参数，而是随每条用户消息的 `meta` 下发并由开发机 CLI 强制：
  `permissionMode`、`model`、`disallowedTools`。
- 知行产品层只暴露两档执行策略：普通（`default`）与完全访问（`bypassPermissions`，两个 agent
  共用同一取值；Codex 侧由 happy-cli 映射为 on-failure 审批 + danger-full-access 沙箱）。
- CLI 侧对 permissionMode/model 按会话粘滞（未携带 meta 的消息沿用上一次的值）；客户端仍然
  每条消息显式下发当前模式，并把最近一条用户消息的 permissionMode 回填为会话级状态。
- 硬性限制通过 `disallowedTools` 下发，仅 Claude Code 侧远端强制；Codex 侧协议无 deny list，
  完全访问模式下为尽力而为，UI 必须如实标注。
- 思考深度（2026-07 核实）：Claude Code 档位 low/medium/high/xhigh/max（xhigh 仅
  Fable 5 / Sonnet 5 / Opus 4.7+），经 spawn environmentVariables 的
  `CLAUDE_CODE_EFFORT_LEVEL` 下发（新模型忽略 MAX_THINKING_TOKENS）；Codex 档位以
  openai/codex 主干 ReasoningEffort 枚举为准（none/minimal/low/medium/high/xhigh/max/ultra，
  UI 暴露 low~ultra 六档，对应 5.6 系列客户端的 轻度/中/高/极高/最大/Ultra），
  官方无环境变量通道、happy-cli meta 不透传，暂无法远程下发，仅保存偏好并在 UI 标注。

## 4. 分阶段交付

| 阶段 | 范围 | 验收证据 |
| --- | --- | --- |
| A 身份核心 | 恢复密钥解析、token、Keystore、协议模型 | Happy 官方测试向量 + 本地单测 |
| B 只读闭环 | 机器/会话/历史消息、Socket 增量同步 | 真实账户可看到现有 Codex 会话 |
| C 可写闭环 | 发送、中断、审批 | 手机上的操作在开发机实时生效 |
| D 产品化 | 后台恢复、通知、诊断、WebView 移除 | 真机弱网、重启、升级验证 |
| E 自托管 | CPA/VPS Happy Server、迁移与回滚 | 手机仍不直接接触开发机 |

### 4.1 当前进度

- [x] 阶段 A：恢复密钥、Ed25519 登录、Keystore 凭据存储、NaCl/AES-GCM 固定向量测试。
- [x] 阶段 B：机器与 `/v2/sessions` 完整分页历史的 HTTP 初始快照；活跃接口只用于在线态语义，不再充当项目数据源。
- [x] 阶段 B：历史消息、Socket.IO 增量、断线补偿与真实账户验收。
- [x] 阶段 C：手机指定开发机/目录新建 Codex 任务、恢复历史对话、补充消息、中断与审批 RPC 的原生读写闭环。
- [x] Issue #18 重构阶段 1：WorkRepository + Room 缓存成为单一数据源（会话/消息/机器/仓库预设四表）；
      消息解析改为结构化 parts（文本/思考/工具调用/工具结果/文件修改/事件）；移除 3 秒全量轮询，
      改为 Socket 增量信号驱动的节流同步；去除 Codex 过滤，Claude Code 会话纳入列表；
      resume 兼容新旧 happy-cli 协议。
- [x] Issue #18 重构阶段 2：仓库预设（机器/目录/默认分支/默认 Agent/模型/思考深度/完全访问/硬性限制）
      与预设编辑页；新建任务从对话框升级为整页（预设预填 + Agent/模型/思考深度/完全访问 + 首条指令，
      开启完全访问需显式确认）；首条指令随 meta 下发 permissionMode/model/disallowedTools，思考深度经
      spawn environmentVariables（Claude MAX_THINKING_TOKENS）下发；工作首页重构为
      等待我处理/进行中/仓库/最近 四区。
- [x] Issue #18 重构阶段 3：会话级执行模式闭环——会话页两态模式 chip（切完全访问需确认，
      对下一条消息即时生效）；每条补充消息显式携带 permissionMode 与仓库级 disallowedTools；
      会话模式本地持久化（DB v26）并从消息 meta 回填（兼容其他客户端切换）；列表 ⚡ 完全访问
      标识；思考深度按官方档位重做（Claude 经 CLAUDE_CODE_EFFORT_LEVEL 下发，Codex 如实标注
      暂不可远程设置）。
- [x] Issue #18 重构阶段 4：会话页信息架构（连续工具活动折叠为活动卡、思考可展开、
      成功结果与终端输出只进完整日志、失败必在聊天流可见、事件降噪；目标卡挂
      工具调用/文件修改统计）；新增完整日志页（全部/工具/文件/终端/事件过滤）；
      工作首页顶栏搜索（会话标题/仓库路径/消息内容的本地缓存检索）。
- [x] Issue #18 阶段 5：关键通知——仅在任务结束或出现待审批/待决策时通知（普通命令执行不打扰，
      App 前台静默）；进程存活期由 Socket 增量近实时驱动，WorkManager 15 分钟周期同步兜底；
      通知深链直达会话页并保留工作首页返回栈。连接中断通知与可操作通知按钮（通知栏直接
      允许/拒绝）留待后续。
- [ ] 阶段 D 及以后。

### 4.2 真实链路验收记录

2026-07-17 使用当前 Happy 生产中继和本机 `happy codex` 完成以下验证，诊断输出未记录 token、
恢复密钥或会话 data key：

- Bearer token 可读取 1 台在线开发机和 1 个活跃 Codex 会话。
- 以 `sentFrom=android` 加密发送“知行原生闭环验证”消息，HTTP 增量历史收到 Codex 返回的
  “闭环已确认”。
- `user-scoped` Socket.IO 连接成功，`<sessionId>:abort` 返回成功回执。
- `permissionMode=default` 触发真实 `CodexBash` 审批请求；`permission` 的“允许一次”RPC 返回成功，
  请求从 `requests` 转入 `completedRequests`，状态为 `approved`。
- 临时审批验证文件已清理；测试进程未把 Happy 凭据写入仓库或构建日志。

同日产品模型复核发现 `/v2/sessions/active` 只返回最近 15 分钟活跃会话，不能作为项目入口。修正后，
真实账户在“0 个活跃会话”的同时仍从 `/v2/sessions` 读回 2 个历史会话（含已结束会话）；工作界面改为
项目与对话层级，连接/机器信息迁入设置，并接入 `spawn-happy-session` 与 `resume-happy-session`。

当前发布闭环以恢复密钥导入为账户入口。由已有设备批准新设备的免密配对仍属于后续产品化工作，
不影响已登录设备上的原生读写闭环。

## 5. 非目标与延期项

- ~~P0 不接入 Claude Code~~（已被 Issue #18 推翻：Codex 与 Claude Code 均为一等 Agent）；
  仍不实现多租户团队权限。
- Issue #18 验收标准 7 的三档执行模式在产品上收敛为两档：普通与完全访问；
  “自动执行”（acceptEdits）档协议已支持，暂不暴露 UI。
- 不实现远程桌面、通用 SSH 终端或开发机文件系统任意浏览。
- 不把恢复密钥上传到知行自有服务端。
- Push 服务和 Happy Relay 自托管在原生读写闭环稳定后实施。
- CC Pocket、HAPI、Remodex 仅作为交互和 Codex app-server 映射参考，不混用身份与传输协议。

## 6. 回滚

原生页面在开发阶段可通过内部开关回退到 Happy Web 页面。正式替换前保留原有路由，但默认不加载 WebView；若协议升级导致核心功能不可用，只禁用原生远程工作流，不影响知行本地聊天、知识库和 Provider。
