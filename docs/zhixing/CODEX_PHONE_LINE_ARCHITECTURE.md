# Work Phone-line v1：Codex 手机电话线架构

状态：实施契约（2026-07-20）

## 1. 问题与设计原则

目标不是把 Codex Desktop 搬进手机，而是让开发机上的 Codex 在用户离开电脑后仍有一条可靠、克制的沟通线。
产品体验以“普通聊天”为表面，以“远端自主执行”为运行方式。

四条硬边界：

- 只管理从知行手机端创建的会话，不扫描或同步桌面历史。
- 模型与思考深度在创建时选择；权限固定 `danger-full-access + never`。
- Codex 只看见三个专用 MCP 工具；手机不消费 App Server JSON-RPC、原始终端流、推理或工具参数。
- Core 是耐久消息箱，Runner 是本地执行器；二者都不翻译 Codex 私有协议。

## 2. 目标拓扑

```text
Android App
  | HTTPS / SSE（用户消息、提问答案、状态、报告）
  v
Work Core（公网、自建）
  | 耐久队列 + 会话状态 + 认证
  ^ HTTPS 长轮询/出站连接
Work Runner（Windows 开发机）
  | spawn / resume + public JSONL event bridge
  v
Codex CLI（专用 profile）
  | stdio MCP
  v
Phone-line MCP（report / ask / report_html）
  | HTTPS
  +-----------------------> Work Core
```

Core 可以部署在 VPS，但不持有 OpenAI 登录态、Codex 凭据、仓库文件或 shell 能力。Core 可以耐久保存用户主动发送的
受限图片附件；附件不等同于仓库文件，只能由所属会话的 Runner 凭据下载。开发机永远主动出站，不暴露端口，也不依赖
手机与 Tailscale/VPN 共存。

## 3. 组件职责

### Android

- 保存 Core 地址和用户 token；秘密由 Android Keystore 保护。
- 展示手机创建的会话、消息、待答问题、HTML 报告与 Runner 在线状态。
- 新建时选择 Runner、仓库、模型、思考深度；第一条消息就是普通聊天输入。
- 使用普通聊天页的视觉骨架和 Markdown 渲染；Work 使用独立数据源，不写入普通 `Conversation`。
- SSE 断开时按游标增量补拉；缓存已有消息，弱网不锁死模型/思考深度控件。
- 只要存在 `QUEUED/RUNNING/WAITING_FOR_USER` 的未归档会话，就以低打扰常驻通知持续显示任务状态并在后台增量补拉。
- `REPORT/HTML_REPORT/ASK`、任务完成和失败属于关键时刻：后台或未停留在对应会话时发送可点击通知；`ASK` 使用高优先级提醒。
- 归档只改变会话在首页的可见性，不删除事件、报告、Codex session 映射或恢复能力；恢复后仍对同一个会话继续发送。
- Work 消息必须具备普通聊天的基础操作：用户与 AI 文本均可选择和复制全文，代码块保留独立复制入口，消息可引用到输入框；
  用户消息可载入输入框修改后作为新消息发送，不改写已执行历史。
- 文本草稿按 Work 会话保存在本机，发送被 Core 耐久接受后才清除；失败时保留正文和本轮附件并提供原地重试。
- 打开会话仍定位最新进展；用户上滑阅读历史时，新事件不得强制抢回底部，而应显示“新消息/跳到最新”入口。

### Work Core

- 是 Work 会话、消息、问题、答案、报告和命令的事实来源。
- 保存用户图片附件及摘要，并只向所属 Runner 提供鉴权下载。
- 对 Android、Runner 和单个 Codex session 使用不同作用域的 token。
- 为所有写请求提供客户端 ID/幂等键，保证重试不重复创建消息或答案。
- 只存仓库显示名与 Runner 内部 repo ID，不接收真实路径和源码。
- 清理过期 session token，保留可配置的会话与报告生命周期。

### Work Runner

- 登记本机允许使用的仓库白名单，向 Core 上报稳定 repo ID、显示名和可用状态。
- 拉取启动、继续、停止命令；在仓库目录启动 Codex CLI。
- 把会话图片下载到单轮临时目录，校验摘要后通过 Codex CLI `--image` 传入，退出即清理。
- 保存 Work session 与 Codex session ID 映射，并在重启后恢复。
- 消费 `codex exec --json` 的公开 JSONL 事件，只把完成的 `agent_message` 映射成普通 AI 消息；不上传 reasoning、
  命令正文、工具参数或原始工具输出。
- 观察子进程开始、退出、错误；终态先写本地 outbox，再向 Core 原子提交并在断网/重启后重放。
- JSONL 消息也先写本地 outbox，再异步上传；Core 通过客户端事件 ID 幂等去重，保证消息先于本轮终态落库。
- 接收 Core 为每次 START/RESUME 签发的 24 小时 session token，并写入专用 MCP 配置，不污染用户的普通 Codex 配置。

### 手机专属 Stop Hook

- Hook 只安装到 Runner 的隔离 `CODEX_HOME` profile，普通 `~/.codex` 与仓库 `.codex/` 均不写入，因此电脑端
  Codex Desktop/CLI 不加载。
- 只启用 `Stop`，不启用可能位于工具执行前的 `PreToolUse`、权限 Hook 或逐工具 `PostToolUse`。
- Hook 不联网、不持有 Core/Runner/session token，只把小于 1 KiB 的结束标记原子写入单轮本地 outbox。
- Hook 超时为 1 秒，脚本无论错误与否都静默 `exit 0`；Runner 仍以 JSONL、子进程退出码和耐久 transition
  为事实来源，Hook 缺失、超时或损坏不能阻断 Codex。

### Phone-line MCP

- 与当前 Work session 绑定；不能列出或访问其他会话。
- 工具调用写入 Core 后尽快返回；`ask` 例外，可短挂最多 180 秒。
- `report` 返回从上次 inbox cursor 之后积压的手机消息，使 Codex 必然读到补充内容。
- 本地日志只记录请求 ID、状态码和耗时，不记录消息正文或 token。

### 手机会话行为指令

- Runner 在每次手机 START/RESUME 的命令行中显式注入 `developer_instructions`，让 Codex 知道三个工具以及调用时机；
  该指令不写入普通 `~/.codex`，电脑端会话不会继承。
- `report` 至少用于有意义的阶段完成、准备进入较长无人值守等待，以及本轮结束前的最终结果；不能退化为逐工具刷屏。
- `ask` 只用于确实阻断安全推进的用户选择，保持 1–4 个简短选择题；`report_html` 只承载适合独立阅读的长结构化交付物。
- 工具结果中的 inbox 是模型可见输入，Codex 必须阅读并响应；普通 assistant 输出仍然保留，不能把工具调用当成唯一记录。
- 自动 JSONL 消息桥是可靠性兜底，不依赖模型是否遵守工具指令；MCP 负责主动沟通语义，JSONL 负责避免正常回复丢失。

## 4. Codex 启动契约

新会话等价于：在白名单仓库中，用用户选择的 model/effort 启动一次非交互 `codex exec`。Runner 写入专用临时
`CODEX_HOME` 或 profile，只注册 Phone-line MCP，并固定：

```toml
sandbox_mode = "danger-full-access"
approval_policy = "never"
model_reasoning_effort = "<selected>"
```

继续消息使用 `codex exec resume <codexSessionId>`。model 与 effort 是 Work 会话的运行快照，首期不允许运行中切换；
需要切换时新建会话。Runner 不读取 ChatGPT Desktop 的 SQLite、缓存或任务目录。

Runner 使用隔离 `CODEX_HOME` 的显式 phone profile 加载上述 Stop Hook，并继续忽略普通用户配置。该 profile 仅由
Runner 生成和维护；日常电脑会话不会携带 profile 参数，也不会使用 Work 的 hook outbox。

三个 Phone-line 工具的行为约定通过同一次手机专属启动命令注入 `developer_instructions`。它必须明确要求里程碑汇报、
阻断式提问和结束前最终汇报，确保“工具已注册”同时也“模型知道何时该用”；该指令仅作用于 Runner 发起的会话。

## 5. 状态机

```text
CREATED -> QUEUED -> RUNNING -> WAITING_FOR_USER -> RUNNING
                        |              |             |
                        +-----------> IDLE <---------+
                                       |
                         RUNNING ------+-----> COMPLETED
                           |                   |
                           +-----> FAILED <----+
```

- `CREATED/QUEUED`：Core 已接收，等待 Runner。
- `RUNNING`：Codex 子进程存活。
- `WAITING_FOR_USER`：存在尚未回答的 `ask`；180 秒超时后进入 `IDLE`，不宣告失败。
- `IDLE`：本轮 Codex 已退出，但会话可用下一条手机消息 resume。
- `COMPLETED`：用户主动结束；不可再发送。
- `FAILED`：启动或 resume 失败，可重试；已有消息与报告仍可读。

归档不是运行状态。活跃任务不能归档，Core 对 `QUEUED/RUNNING/WAITING_FOR_USER` 返回 409；`IDLE/FAILED/COMPLETED`
可归档并从默认列表隐藏。恢复归档会话后，`IDLE/FAILED` 可继续原 Codex session，`COMPLETED` 仍只读。

Runner 离线是连接状态，不改写会话状态。手机允许排队发送，并明确显示“等待开发机上线”。

## 6. 安全与内容边界

- Core 强制 HTTPS；token 仅存哈希，按 user/runner/session 分 scope 并可撤销。
- Runner 只接受预登记 repo ID，不允许手机提交任意路径、命令、环境变量或 MCP 配置。
- HTML 由 Core 使用固定模板封装并清洗；脚本、表单、外链资源、文件 URL 和任意导航默认禁用。
- Android WebView 使用独立只读页，关闭文件访问和跨源能力；报告正文不获得 App bridge。
- 所有消息和答案带递增序号；服务端校验 session 所属关系，避免跨会话注入。

## 7. 故障与降级

- Core 不可达：Android 展示本地缓存并允许草稿，不伪装成已发送；Runner 指数退避重连。
- Runner 离线：消息耐久排队；上线后顺序处理，同一会话同时最多一个 Codex 进程。
- `ask` 超时：工具返回明确 timeout；用户之后回答会形成 inbox 消息并触发一次 resume。
- Codex 未调用 `report`：Runner 自动转发公开 JSONL 中的普通 `agent_message`；进程退出时仍提交终态，手机不会
  因模型忘记调用 MCP 而空白或无限显示“运行中”。
- SSE 丢失：客户端用 `afterSeq` 补拉；catalog/仓库刷新失败只 toast，不禁用已有缓存选项。

## 8. 明确废弃

以下内容不允许作为新版捷径重新引入：Happy 账户/恢复密钥/加密协议、Blind Relay、App Server 客户端与 catalog、
Codex Desktop 历史扫描、旧 Agent 消息映射、Tailscale 直连要求，以及独立于普通聊天视觉体系的 Work 输入框。
