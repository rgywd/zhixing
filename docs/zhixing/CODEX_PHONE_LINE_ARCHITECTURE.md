# Work Phone-line v2：Codex / Claude Code 手机电话线架构

状态：v2 现行架构契约（2026-08-14 核对）

## 1. 问题与设计原则

目标不是把桌面开发代理搬进手机，而是让开发机上的 Codex 或 Claude Code 在用户离开电脑后仍有一条可靠、克制的
沟通线。产品体验以“普通聊天”为表面，以“远端自主执行”为运行方式。

四条硬边界：

- 只管理从知行手机端创建的会话，不扫描或同步桌面历史。
- 运行时与模型在创建时选择；思考深度与 Codex 速度可随后续消息调整并从下一轮生效；执行权限固定为无需逐次确认的
  完全访问。
- CLI 只看见三个专用 MCP 工具；手机不消费私有 RPC、原始终端流、推理或工具参数。
- Core 是耐久消息箱，Runner 是本地执行器；二者都不把 CLI 私有事件协议暴露给手机。

## 2. 目标拓扑

```text
Android App
  | HTTPS / SSE（用户消息、提问答案、状态、报告）
  v
Work Core（公网、自建）
  | 耐久队列 + 会话状态 + 认证
  ^ HTTPS 长轮询/出站连接
Work Runner（Windows 开发机）
  | Codex App Server turn bridge / Claude stream-json bridge
  v
Codex CLI 0.147.0 App Server（单轮双向 JSONL）或 Claude Code CLI（隔离启动参数）
  | stdio MCP
  v
Phone-line MCP（report / ask / report_html）
  | HTTPS
  +-----------------------> Work Core
```

Core 可以部署在 VPS，但不持有 OpenAI/Anthropic 登录态、CLI 凭据、仓库文件或 shell 能力。Core 可以耐久保存用户主动发送的
受限附件；附件不等同于仓库文件，只能由所属会话的 Runner 凭据下载。普通文件字节位于 Core 管理的固定目录，SQLite
只保存相对存储键与元数据，不保存或下发 Core 的绝对路径。开发机永远主动出站，不暴露端口，也不依赖手机与
Tailscale/VPN 共存。

## 3. 组件职责

### Android

- 保存 Core 地址和用户 token；秘密由 Android Keystore 保护。
- 展示手机创建的会话、消息、待答问题、HTML 报告与 Runner 在线状态。
- 新建时选择 Runner、仓库、运行时、模型、初始思考深度和 Codex 速度；第一条消息就是普通聊天输入。模型、思考深度
  和速度合并在一个分层选择器中；已有会话可为下一条消息调整思考深度与 Codex 速度，但不能改写正在运行的当前回合。
- 仓库选择偏好只保存在 Android 本机：用户可手动置顶，最近选择保留最多 5 项；新会话优先恢复最近一次仍可用的
  仓库，其次使用置顶项，没有历史时保持未选择，不再按仓库名称擅自默认。
- 使用普通聊天页的视觉骨架和 Markdown 渲染；Work 使用独立数据源，不写入普通 `Conversation`。
- SSE 断开时按游标增量补拉；缓存已有消息，弱网不锁死模型/思考深度/速度控件。
- 只要存在 `QUEUED/RUNNING/WAITING_FOR_USER` 的未归档会话，就以低打扰常驻通知持续显示任务状态并在后台增量补拉；
  某个任务完成、结束或失败而仍有其他任务运行时，常驻通知镜像该关键结果 60 秒，同时保留剩余任务数。
- `REPORT/HTML_REPORT/ASK`、任务完成和失败属于关键时刻：后台或未停留在对应会话时发送可点击通知；`ASK` 使用高优先级提醒。
- 归档只改变会话在首页的可见性，不删除事件、报告、运行时 session 映射或恢复能力；恢复后仍对同一个会话继续发送。
- Work 消息必须具备普通聊天的基础操作：用户与 AI 文本均可选择和复制全文，代码块保留独立复制入口，消息可引用到输入框；
  用户消息可载入输入框修改后作为新消息发送，不改写已执行历史。
- 文本草稿按 Work 会话保存在本机，发送被 Core 耐久接受后才清除；失败时保留正文和本轮附件并提供原地重试。
- 当前 turn 运行时，点按发送把输入加入 Core 持久化 FIFO 队列，长按发送通过 `turn/steer` 引导当前 turn；
  输入框上方默认折叠展示队首和数量，展开后可把尚未派发的条目原子转为当前 turn 引导、编辑或撤回。
  转引导复用 Core 已绑定的正文和附件，失败时恢复原队列项；`WAITING_FOR_USER` 只允许入队，必须先回答问题。
- 图片与文件共用每条消息最多 4 个、单个最大 10 MiB 的限制。只有 Runner 声明 `fileAttachments >= 1` 时才开放
  普通文件选择；旧 Runner 继续保持图片能力。
- 打开会话仍定位最新进展；用户上滑阅读历史时，新事件不得强制抢回底部，而应显示“新消息/跳到最新”入口。

### Work Core

- 是 Work 会话、消息、问题、答案、报告和命令的事实来源。
- 持久化未派发输入及 revision；队列项进入实际 turn 前不写 `USER_MESSAGE`。正常完成时原子提升队首，用户停止或失败时冻结。
  队列转引导以 revision 和 active turn 双重 CAS 创建既有 `STEER` 命令；成功后派发，失败则保留正文、附件和 FIFO 位置。
- 保存用户附件及摘要，并只向所属 Runner 提供鉴权下载。图片沿用 SQLite BLOB；普通文件写入
  `WORK_CORE_ATTACHMENT_DIR`，SQLite 只存相对 `storage_key`、文件名、MIME、大小和 SHA-256。
- 按类型组合校验附件扩展名、MIME、文件签名或 UTF-8 文本内容；拒绝伪装文件、可执行二进制与安装包。文本脚本
  只作为非可信输入交付。ZIP、7z、GZip 保持原样，Core 不主动解压。超过 24 小时仍未绑定会话的上传在后续上传时
  清理；已绑定附件随会话保留，归档不删除。
- 对 Android、Runner 和单个 CLI session 使用不同作用域的 token。
- 为所有写请求提供客户端 ID/幂等键，保证重试不重复创建消息或答案。
- 只存仓库显示名、可选分组与 Runner 内部 repo ID，不接收真实路径和源码。
- 清理过期 session token，保留可配置的会话与报告生命周期。
- 可以复用 Work 用户鉴权承载少量 `/v1/life/*` 只读薄代理，但代理不得采集或保存上游原始内容，不得持有邮箱、
  飞书登录凭据，也不得向 Phone-line MCP 增加工具。当前信息监控只代理到独立 Life Gateway，并仅在进程内短暂缓存
  已验证、已剥离敏感字段的 `information-monitor/v1` 结果。

### Work Runner

- 登记本机允许使用的仓库白名单，向 Core 上报稳定 repo ID、显示名和可用状态。
- 仓库白名单既可包含固定 `repos`，也可包含显式授权的 `repoRoots`。Runner 只在这些根目录内直接扫描文件系统，
  支持发现一级子目录或按项目标记递归发现；不得使用 Windows Search 或 Codex Desktop 私有数据库作为事实来源。
- Runner 启动及配置的刷新周期内重新校验目录。新增目录自动加入 catalog，删除的固定目录标为不可用，删除的发现目录
  从 catalog 移除；符号链接或 junction 不得借机越过授权根目录。
- 拉取启动、继续、实时引导和停止命令；在仓库目录启动会话已固定的 Codex 或 Claude Code CLI。
- 把会话附件下载到单轮临时目录并校验摘要。Codex 图片使用 App Server `localImage` input，普通文件通过提示中的
  精确路径与固定 `danger-full-access` 读取；Claude Code 用 `--add-dir` 授权目录。两种运行时都收到结构化附件清单，
  压缩包只在任务需要时检查或解压，绝不因被附加就执行；CLI 退出后立即清理副本。
- 保存 Work session 与通用 `runtimeSessionId` 映射，并在重启后恢复；`codexSessionId` 只作为旧客户端兼容别名。
- Codex 每个活跃 turn 启动一个 `codex app-server --stdio`：完成握手后执行 `thread/start|resume` 与 `turn/start`，
  活跃期间接受 `turn/steer` 和 `turn/interrupt`。Claude Code 继续使用 `-p --output-format stream-json`。两者只把完成的 assistant 文本映射成
  普通 AI 消息；不上传 reasoning、命令正文、工具参数或原始工具输出。
- 观察各 CLI 的公开语义终态、子进程退出和错误。Codex 以 `turn/completed` 的 completed/interrupted/failed 为唯一
  turn 终态；收到终态后关闭 App Server stdin，并等待进程完全退出、释放 thread single-writer 锁，才能 ACK 和启动下一轮。
  超时则清理整棵子进程树；Claude Code 以最终 `result` 事件和 `is_error/api_error_status` 判定结果。
  终态先写本地 outbox，再向 Core 原子提交并在断网/重启后重放。
- App Server 的进程与内存生命周期严格以单个 turn 为边界：本轮进入语义终态后即释放，不需要用户“结束会话”。
  `IDLE` 只保留 Core 中的事件和 Runner 中用于 `thread/resume` 的轻量 session 映射，不保留 App Server 进程或其堆；
  `COMPLETED` 是用户明确选择的不可继续业务终态，不承担资源回收职责。
- JSONL 消息也先写本地 outbox，再异步上传；Core 通过客户端事件 ID 幂等去重，保证消息先于本轮终态落库。
- 接收 Core 为每次 START/RESUME 签发的 24 小时 session token，并写入单轮专用 MCP 配置，不污染用户的普通 CLI 配置。

### Codex exec 兼容路径的手机专属 Stop Hook

- Hook 仅供短期保留的 `codex exec` 回退路径。App Server 路径显式禁用 hooks，并以 turn 通知和 interrupt 为准。
  Hook 只安装到 Runner 的隔离 `CODEX_HOME` profile，普通 `~/.codex` 与仓库 `.codex/` 均不写入，因此电脑端
  Codex Desktop/CLI 不加载。
- 只启用 `Stop`，不启用可能位于工具执行前的 `PreToolUse`、权限 Hook 或逐工具 `PostToolUse`。
- Hook 不联网、不持有 Core/Runner/session token，只把小于 1 KiB 的结束标记原子写入单轮本地 outbox。
- Hook 超时为 1 秒，脚本无论错误与否都静默 `exit 0`；Runner 仍以 JSONL 语义终态、子进程退出码和耐久 transition
  为事实来源，Hook 缺失、超时或损坏不能阻断 Codex。
- Claude Code 读取 user/project settings 以复用本机第三方 API 环境配置；命令级 `disableAllHooks=true`
  始终覆盖并禁用 hooks，本机 local settings 不加载。

### Phone-line MCP

- 与当前 Work session 绑定；不能列出或访问其他会话。
- 工具调用写入 Core 后尽快返回；`ask` 例外，可短挂最多 180 秒，超时自动采用模型给出的推荐答案返回。
- `report` 返回从上次 inbox cursor 之后积压的手机消息，使当前 CLI 会话必然读到补充内容。
- 本地日志只记录请求 ID、状态码和耗时，不记录消息正文或 token。

### 手机会话行为指令

- Runner 在每次手机 START/RESUME 的命令行中显式注入手机会话行为指令，让当前 CLI 知道三个工具以及调用时机；
  该指令不写入普通用户配置，电脑端会话不会继承。
- `report` 至少用于有意义的阶段完成、准备进入较长无人值守等待，以及本轮结束前的最终结果；不能退化为逐工具刷屏。
- `ask` 用于用户偏好会改变做法的决策点，保持 1–4 个简短选择题，每题必须给诚实推荐的默认选项，提问永远不会
  卡住流程；`report_html` 只承载适合独立阅读的长结构化交付物。
- 工具结果中的 inbox 是模型可见输入，当前 CLI 必须阅读并响应；普通 assistant 输出仍然保留，不能把工具调用当成唯一记录。
- 自动 JSONL 消息桥是可靠性兜底，不依赖模型是否遵守工具指令；MCP 负责主动沟通语义，JSONL 负责避免正常回复丢失。

## 4. CLI 启动契约

模型和运行时是 Work 会话的不可变快照；思考深度与 Codex 速度是会话当前默认值，可随队列消息原子更新并应用于
该消息触发的下一次 CLI START/RESUME。`turn/steer` 只增加当前 turn 的输入，不改模型、effort、速度、cwd 或权限。
Runner 不读取任何桌面客户端的 SQLite、
缓存或任务目录。

### Codex

Work 固定使用稳定版 Codex CLI `0.147.0`。在白名单仓库中，每个活跃 turn 启动一次 stdio App Server，使用隔离
`CODEX_HOME`，只注册 Phone-line MCP，并固定：

```toml
sandbox_mode = "danger-full-access"
approval_policy = "never"
model_reasoning_effort = "<selected>"
service_tier = "<fast-or-default>"
```

速度只适用于 Codex。Runner catalog 逐模型公布 `fastModels`；Android 只为 Codex 展示“标准/快速”，不支持 Fast 的模型
禁用“快速”。Runner 对标准速度显式传 `service_tier=default`，避免恢复会话继承旧的 Fast 偏好；快速传
`service_tier=fast`。该值表达服务速度层，不替换模型，也不改变思考深度。

首轮使用 `thread/start`，继续消息使用 `thread/resume(runtimeSessionId)`，再执行 `turn/start`；现有 `codex exec`
产生的 thread ID 可直接恢复。App Server 终态后必须关闭连接并等待进程退出，不能与同一 thread 的下一进程并发。
稳定版 resume 会返回完整历史，应监控长会话启动延迟；未来只有在相应能力稳定后才启用分页或排除历史。
Runner 为 `initialize` 使用独立的 60 秒预算；仅当该握手超时时，先清理整个失败进程树再自动重试一次。重试发生在
`thread/start|resume` 之前，因此不会重复创建 thread 或 turn。`thread/start|resume` 与 `turn/start` 使用 90 秒启动预算，
活跃 turn 的普通控制 RPC 保持 30 秒预算。

### Claude Code

在白名单仓库中使用 print mode、verbose stream-json、`bypassPermissions` 和用户选择的 model/effort 启动。Runner
通过 `--mcp-config` + `--strict-mcp-config` 只暴露本轮 Phone-line MCP，通过命令级 settings 禁用 hooks，并用
`--setting-sources user,project` 复用当前 Windows 用户的订阅登录或 `settings.json.env` 第三方 API 配置，同时不加载
local settings。继续消息使用 `--resume <runtimeSessionId>`。凭据永远不进入 Runner 配置、Core、命令行参数或日志。

三个 Phone-line 工具的行为约定通过同一次手机专属启动命令注入。它必须明确要求里程碑汇报、决策式提问和结束前
最终汇报，确保“工具已注册”同时也“模型知道何时该用”；该指令仅作用于 Runner 发起的会话。

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
- `RUNNING`：当前 CLI 本轮尚未产生语义终态；不能仅因 CLI 进程仍存活就继续显示运行中。
- `WAITING_FOR_USER`：存在尚未回答的 `ask`；180 秒超时后按推荐答案落定并回到 `RUNNING`，不宣告失败。
- `IDLE`：本轮 CLI/App Server 已退出并释放 thread writer，但会话可用下一条手机消息 resume。
- `COMPLETED`：用户主动结束；不可再发送。
- `FAILED`：启动或 resume 失败，可重试；已有消息与报告仍可读。

归档不是运行状态。活跃任务不能归档，Core 对 `QUEUED/RUNNING/WAITING_FOR_USER` 返回 409；`IDLE/FAILED/COMPLETED`
可归档并从默认列表隐藏。恢复归档会话后，`IDLE/FAILED` 可继续原 runtime session，`COMPLETED` 仍只读。

Runner 离线是连接状态，不改写会话状态。手机允许排队发送，并明确显示“等待开发机上线”。

## 6. 安全与内容边界

- Core 强制 HTTPS；token 仅存哈希，按 user/runner/session 分 scope 并可撤销。
- `/v1/life/*` 上游同样必须使用 HTTPS 或 loopback 加密隧道，禁止携带服务 token 跟随重定向；上游响应按固定
  schema 重建，原始正文、provider ID、credentials 和契约外字段不得穿过 Core。
- Runner 只接受预登记 repo ID，不允许手机提交任意路径、命令、环境变量或 MCP 配置。
- 附件是非可信输入。Core 与 Runner 都校验类型和摘要，Runner 只写入本轮临时目录，禁止把附件当作程序自动执行。
- HTML 由 Core 使用固定模板封装并清洗；脚本、表单、外链资源、文件 URL 和任意导航默认禁用。
- Android WebView 使用独立只读页，关闭文件访问和跨源能力；报告正文不获得 App bridge。
- 所有消息和答案带递增序号；服务端校验 session 所属关系，避免跨会话注入。

## 7. 故障与降级

- Core 不可达：Android 展示本地缓存并允许草稿，不伪装成已发送；Runner 指数退避重连。
- Runner 离线：消息耐久排队；上线后顺序处理，同一会话同时最多一个 CLI 进程。
- `ask` 超时：Core 把该题的推荐答案落定（`ASK_ANSWERED`，`source=timeout_default`）并返回 `auto_answered`，
  当前 CLI 按推荐方案继续；迟到回答被幂等忽略，用户改主意直接发普通消息。
- CLI 未调用 `report`：Runner 自动转发公开事件中的普通 assistant 文本；公开终态到达时提交状态，子进程退出作为
  兼容兜底，手机不会因模型忘记调用 MCP 而空白或无限显示“运行中”。
- SSE 丢失：客户端用 `afterSeq` 补拉；catalog/仓库刷新失败只 toast，不禁用已有缓存选项。

## 8. 明确废弃

以下内容不允许作为新版捷径重新引入：Happy 账户/恢复密钥/加密协议、Blind Relay、把 App Server 私有协议或 catalog
直接暴露给手机、Codex Desktop 历史扫描、旧 Agent 消息映射、Tailscale 直连要求，以及独立于普通聊天视觉体系的 Work 输入框。
