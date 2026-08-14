# Work Phone-line v2：协议契约

状态：v2 现行协议契约（2026-08-14 核对）

所有 JSON 字段使用 camelCase，时间使用 UTC RFC 3339，ID 使用不可预测的 UUID/ULID。所有写操作带
`Idempotency-Key`；成功重试返回第一次创建的对象。

## 1. 会话运行快照

```json
{
  "id": "work_...",
  "runnerId": "runner_...",
  "repoId": "repo_...",
  "repoName": "zhixing",
  "title": "生成 Work 会话标题",
  "runtime": "claude-code",
  "model": "sonnet",
  "reasoningEffort": "high",
  "fastMode": false,
  "sandboxMode": "danger-full-access",
  "approvalPolicy": "never",
  "status": "QUEUED",
  "runtimeSessionId": null,
  "activeTurnId": null,
  "lastSeq": 0,
  "archivedAt": null,
  "createdAt": "2026-07-20T00:00:00Z"
}
```

Android 不提交真实路径、任意 sandbox/approval 值或 CLI 参数。Core 只接受 Runner 已公布的
repo/runtime/model/effort 组合。仓库 catalog 项可携带可选 `group`、`available` 和运行时目录：

```json
{
  "id": "repo_...",
  "name": "zhixing",
  "available": true,
  "runtimes": [
    {
      "id": "codex",
      "name": "Codex",
      "models": ["gpt-5.6-sol", "gpt-5.3-codex-spark"],
      "reasoningEfforts": ["low", "medium", "high", "xhigh", "max"],
      "reasoningEffortsByModel": {
        "gpt-5.3-codex-spark": ["low", "medium", "high", "xhigh"]
      },
      "fastModels": ["gpt-5.6-sol"]
    },
    { "id": "claude-code", "name": "Claude Code", "models": ["sonnet"], "reasoningEfforts": ["high"] }
  ]
}
```

`group` 是 Runner 配置的公开显示标签，不得包含真实绝对路径。Android 只允许创建 `available=true` 的目录会话，
并可按 `group` 分组和搜索。Android 可在本机保存仓库引用（`runnerId + repoId`）的置顶顺序和最多 5 项最近选择；
选择器按置顶、最近使用、其余分组展示且不重复。新会话只从仍可用的最近项或置顶项恢复默认，没有本地历史时保持
未选择。该偏好不上传 Core，也不改变 catalog 或会话创建协议。创建后 runtime/model 固定；`reasoningEffort` 与
`fastMode` 保存会话当前默认值，后续消息可为下一轮调整。`fastMode` 缺省为 `false`，仅 `runtime=codex` 且模型位于
`fastModels` 时可设为 `true`。旧客户端未提交 `runtime` 时默认 `codex`。旧 Runner 的扁平
`models/reasoningEfforts` catalog 也继续映射为 Codex，但不声明 Fast 能力。

`reasoningEffortsByModel` 是可选的按模型覆盖：键必须属于同一 runtime 的 `models`，值必须是
`reasoningEfforts` 的非空子集。未提供覆盖的模型继续使用 runtime 级 `reasoningEfforts`。Android 在切换模型时
立即收窄选择器并回落到该模型支持的档位；Core 和 Runner 都按同一有效组合校验，避免客户端绕过显示约束。

`fastModels` 是 Codex runtime 可选的模型子集；Claude Code 和其他 runtime 必须为空。Android 将模型、思考深度和
Codex 速度收在同一个分层按钮中，速度只提供“标准/快速”。切到不在 `fastModels` 的模型时必须回落为标准；Core 与
Runner 再次校验，不能仅依赖客户端禁用状态。

Android 新建会话默认选择 Codex `xhigh`、Claude Code `max`；若目标模型不支持该档位，则依次回落到 `high` 和该模型
公布的第一个档位。默认值只用于尚未创建的会话，不覆盖已有会话快照或用户手动选择。

Android 创建会话时可提交最多 80 字符的 `title`。当前客户端用已配置的快速模型根据首条文本生成标题；模型不可用、
生成失败或仅发送附件时使用首条文本摘要或仓库名兜底。旧客户端未提交标题时，Core 使用 `repoName`，保持 v1 向后兼容。

用户消息可额外携带 `attachmentIds`。Android 先通过 `POST /v1/work/attachments` 上传附件，再在创建会话或补充消息时
引用返回的 ID。每条消息的图片与文件合计最多 4 个，每个最大 10 MiB。允许 PNG、JPEG、WebP、GIF，常见 UTF-8
文本与源码，PDF、Office、EPUB，以及 ZIP、7z、GZip；Core 按类型组合校验扩展名、MIME、文件签名或文本编码，
可执行二进制和安装包不在白名单内。文本脚本可以作为源码输入，但不得自动执行。事件与 Runner 命令只携带附件 ID、
文件名、MIME、大小和 SHA-256，不内嵌文件字节。

为兼容既有数据，Core 继续把图片保存在 SQLite BLOB；普通文件写入 `WORK_CORE_ATTACHMENT_DIR` 管理的固定目录，
SQLite 只保存相对 `storage_key` 与元数据。未绑定会话超过 24 小时的上传会在后续上传时清理；绑定后与会话一同保留，
归档不删除。Core 的绝对存储路径不会进入 Android API、事件或 Runner 命令。

普通文件只有在目标 Runner 注册 `capabilities.fileAttachments >= 1` 时才能绑定到消息；旧 Runner 的图片路径保持兼容。
只有会话所属 Runner 能下载附件。Runner 对命令元数据和响应体分别校验 SHA-256，再写入本轮专用临时目录。Codex
图片通过 Codex App Server `localImage` input 接收；普通文件通过提示中的结构化清单和固定
`danger-full-access` 读取精确本机路径。Claude Code 通过 `--add-dir` 获得该目录访问权。ZIP、7z、GZip
保持压缩状态，由 Agent 按任务需要检查或解压，不自动执行。该轮进程退出后删除 Runner 临时副本。

## 2. 三个 MCP 工具

Runner 除注册工具 schema 外，还必须为每次手机会话注入专属 `developer_instructions`：说明三个工具的用途，要求在
有意义的阶段完成和本轮结束前调用 `report`，在用户偏好会改变做法的决策点调用 `ask`（每题必须给推荐答案，超时
自动采用推荐继续，提问永远不会卡住流程），长结构化产物使用 `report_html`。该约定不写入用户全局配置或仓库配置，
普通电脑 CLI 会话不得加载。自动 `ASSISTANT_MESSAGE` 桥接独立存在，不能以模型未调用工具为由丢弃正常回复。

### `report(text)`

输入：

```json
{ "text": "已完成接口改造，正在跑测试。", "clientCallId": "call_...", "inboxAfter": 12 }
```

返回：

```json
{
  "accepted": true,
  "messageId": "msg_...",
  "inbox": [
    { "seq": 13, "id": "msg_...", "text": "测试时顺便覆盖升级场景。", "createdAt": "..." }
  ],
  "nextInboxCursor": 13
}
```

约束：`text` 1–20,000 字符；写入成功后立即返回。相同 `clientCallId` 不重复展示，但仍返回当前未读 inbox。

### `ask(questions)`

输入必须结构化，不解析 Markdown：

```json
{
  "clientCallId": "call_...",
  "questions": [
    {
      "id": "deployTarget",
      "header": "部署位置",
      "question": "这次先部署到哪台机器？",
      "multiSelect": false,
      "options": [
        { "id": "cpa", "label": "CPA", "description": "复用现有运维环境" },
        { "id": "vps", "label": "myVPS", "description": "与开发服务隔离" }
      ],
      "recommendedOptionIds": ["cpa"]
    }
  ]
}
```

规则：每次 1–4 题；每题 1–8 个选项；题目可单选或多选；客户端永远附带“其他”自由输入，不由模型声明关闭。
每题必须携带 `recommendedOptionIds`（模型诚实判断的最优选项；单选恰好 1 个，多选 1 个或多个，且必须引用已有
选项）。ASK 事件 payload 额外携带 `deadlineAt`（回答截止时刻，由 Core 按等待上限生成）。

180 秒内回答：

```json
{
  "status": "answered",
  "answers": [
    { "questionId": "deployTarget", "selectedOptionIds": ["cpa"], "otherText": null }
  ],
  "answeredAt": "...",
  "nextInboxCursor": 13
}
```

超时自动采用推荐答案落定：

```json
{
  "status": "auto_answered",
  "answers": [
    { "questionId": "deployTarget", "selectedOptionIds": ["cpa"], "otherText": null }
  ],
  "answeredAt": "...",
  "message": "No answer within 180 seconds; the recommended options were applied. ..."
}
```

超时不会丢失决策：Core 把推荐答案写入 answers、发出 `ASK_ANSWERED`（payload `source` 为 `timeout_default`，
用户回答为 `user`），会话回到 RUNNING，Codex 继续执行。落定后迟到回答被幂等忽略；用户想改主意直接发普通
消息。Core 重启时对仍 PENDING 的 ask 同样按推荐答案落定，会话恢复 IDLE。

### `report_html(html, title)`

输入：

```json
{ "title": "Work 验收报告", "html": "<h2>结论</h2><p>...</p>", "clientCallId": "call_..." }
```

返回：

```json
{ "accepted": true, "reportId": "report_...", "messageId": "msg_...", "sanitized": true, "outputBytes": 12345 }
```

约束：title 1–120 字符，html 最大 1 MiB。Core 只持久化清洗并由固定模板封装后的版本，不保存原始 HTML。
清洗白名单：`h1-h4`、`p`、`br`、`hr`、`strong`、`em`、`s`、`blockquote`、`ul/ol/li`、`table/thead/tbody/tr/th/td`、
`pre`、`code`、`details`、`summary`、`a`（href 仅 http/https/mailto）、`img`（src 仅 data:）、`figure`、`figcaption`、`mark`；
所有 class、style、script 与 div/span 布局一律丢弃。固定模板自带排版与深色模式，标题由 App 原生顶栏展示。
报告消息只含标题、摘要、大小和 report ID。

## 3. 最小 HTTP API

`/v1/life/*` 是普通聊天复用 Work 用户鉴权的相邻只读代理，不属于 Work 会话状态机，也不改变 Phone-line MCP
三工具约束。信息监控提供 `GET /v1/life/inbox/status|items|digest`，要求相同的协议头和用户 Bearer，并只接受
`channel`、`hours`、`limit`、`minImportance` 四个受限查询参数；其中 `status` 只允许 `channel`，`items`
允许全部四项，`digest` 不允许 `limit`。其上游是独立 Life Gateway；Core 不采集消息，不保存登录凭据、原始
正文或 webhook payload。

### Android scope

- `GET /v1/work/runners`：Runner 在线状态与缓存版本。
- `GET /v1/work/repos?runnerId=`：仓库 catalog，支持 `ETag`。
- `POST /v1/work/attachments`：上传一个受限附件并返回附件元数据。
- `POST /v1/work/sessions`：创建会话与第一条用户消息，可携带可选 `title` 与 `fastMode`；缺省速度为标准。
- `GET /v1/work/sessions?cursor=`：仅返回当前用户的手机会话。
- `GET /v1/work/sessions?archived=true`：返回已归档会话；默认列表不包含归档项。
- `GET /v1/work/sessions/{id}/events?afterSeq=`：补拉有序事件。
- `GET /v1/work/sessions/{id}/stream?afterSeq=`：SSE；断开不影响写入。
- `POST /v1/work/sessions/{id}/messages`：v1 兼容入口；立即写 `USER_MESSAGE` 并创建下一次 START/RESUME。
- `GET /v1/work/sessions/{id}/queue`：读取尚未派发或正在派发的 FIFO 输入。
- `POST /v1/work/sessions/{id}/queue`：耐久入队；可携带 `reasoningEffort` 与 Codex `fastMode`，但派发前不写聊天事件。
- `PATCH /v1/work/sessions/{id}/queue/{itemId}`：携带 `revision` 编辑仍为 `QUEUED` 的正文；附件和运行设置保持不变。
- `DELETE /v1/work/sessions/{id}/queue/{itemId}`：携带 `revision` 撤回仍为 `QUEUED` 的条目。
- `POST /v1/work/sessions/{id}/steer`：携带 `expectedTurnId` 引导当前 Codex turn；Runner 接受后才写 `USER_MESSAGE`。
- `POST /v1/work/sessions/{id}/asks/{askId}/answer`：提交答案。
- `POST /v1/work/sessions/{id}/stop`：停止当前进程，会话进入 IDLE。
- `POST /v1/work/sessions/{id}/complete`：显式结束会话并进入不可继续的业务终态；它不是释放 CLI/App Server
  资源所必需的操作，正常 turn 结束进入 IDLE 时进程已经退出。
- `POST /v1/work/sessions/{id}/archive`：归档非活跃会话；运行中、等待中和排队中的会话返回 409。
- `POST /v1/work/sessions/{id}/unarchive`：恢复到默认列表；保留原事件和 `runtimeSessionId`。
- `POST /v1/work/sessions/{id}/revoke-tokens`：立即撤销该会话已签发的全部 MCP token。
- `GET /v1/work/reports/{id}`：只读清洗报告。

### Runner scope

- `POST /v1/runner/register`：登记进程级 `instanceId`、版本、能力和仓库 catalog；除 `fileAttachments=1` 外，
  v2 Runner 声明 `editableQueue=true`，Codex App Server 声明 `appServerTurns=true` 与 `steer=true`。
  同一实例可在本机目录变化后重新登记 catalog，新实例回收旧实例命令。
- `POST /v1/runner/heartbeat`：按 `runnerId + instanceId` 续租当前实例持有的命令。
- `GET /v1/runner/commands?runnerId=&instanceId=`：拉取当前实例的有序命令。
- `POST /v1/runner/commands/{id}/ack`：当前实例领取/完成/失败。
- `POST /v1/runner/sessions/{id}/state`：当前实例写入进程生命周期。
- `POST /v1/runner/sessions/{id}/events`：当前实例幂等写入经过白名单映射的 CLI 可见事件。
- `GET /v1/runner/attachments/{id}?runnerId=`：下载分配给本 Runner 会话的附件。

### MCP session scope

- `POST /v1/mcp/sessions/{id}/report`
- `POST /v1/mcp/sessions/{id}/ask`
- `POST /v1/mcp/sessions/{id}/report-html`

MCP token 只允许以上三个接口，且 URL 中 session ID 必须与 token claim 相同。token 具有独立 `jti`、24 小时
有效期和服务端撤销记录；每次 START/RESUME 都签发新 token，完成会话会撤销该会话全部 token。

## 4. 事件顺序与并发

- 每个 session 使用独立、单调递增的 `seq`；Android 以 `(sessionId, seq)` 去重。
- 同一 session 同时最多一个 Runner lease、一个 CLI 子进程和一个 `activeTurnId`。
- 队列项状态为 `QUEUED -> DISPATCHING -> DISPATCHED` 或 `CANCELED`。只有 `QUEUED` 可按 revision 编辑/撤回。
  正常 START/RESUME 完成且状态为 IDLE 时，Core 在同一事务中提升队首、写 `USER_MESSAGE`、创建下一命令；STOP、
  COMPLETE、FAILED 不提升。Core 返回入队 2xx 只表示已耐久接收，不表示 CLI 已阅读。
- 补充消息携带 `reasoningEffort` 或 `fastMode` 时，Core 必须在同一个幂等事务中按会话的
  runner/repo/runtime/model 当前 catalog 校验组合、写入消息、更新会话默认值并创建命令。无效组合整笔拒绝，不得
  落下消息或部分更新。该设置只约束新建命令；已经 RUNNING 的子进程继续使用启动时的档位和速度。
- `report` 读取 inbox 时使用租约式 cursor：响应已包含的消息在下一次成功提交 cursor 后才确认，避免进程崩溃丢消息。
- stop 与队列竞态时，先完成 stop；队列保留并冻结，不静默丢弃或自动重启。
- `STEER` 必须携带 Core 当时保存的 `expectedTurnId`。Runner 只在同一活跃 turn 接受；成功 ACK 后 Core 才写用户事件，
  失败则写 `SYSTEM_ERROR(code=STEER_REJECTED)`，不得静默改成队列或自动重放。
- Runner 事件必须携带 `clientEventId`。只允许 `ASSISTANT_MESSAGE`，其正文来自 Codex App Server
  `item/completed + agentMessage`（exec 回退映射 `item.completed + agent_message`），或 Claude Code stream-json 的 assistant text block；带错误标记的 synthetic
  assistant 事件不得展示为正常回复。Core 拒绝 reasoning、命令、工具参数和任意自定义事件类型。
- Runner 必须先把消息写入本地 outbox，再异步上传；本轮 `IDLE/FAILED` transition 必须排在尚未确认的消息之后。
- 与紧邻 `REPORT` 或既有 `ASSISTANT_MESSAGE` 完全相同的文本由 Core 幂等合并，避免模型显式汇报后重复展示。

## 5. Android 展示映射

- `USER_MESSAGE`：右侧普通消息气泡。
- `REPORT`：左侧 Markdown 消息；不展示“工具调用 report”。
- `ASSISTANT_MESSAGE`：左侧普通 Markdown 消息；与正常聊天回复使用相同视觉，不显示 JSONL/Hook 来源。
- `ASK`：左侧原生问题卡。推荐选项带“推荐”标记并预填为默认选择，用户可一键提交、改选或填“其他”；有
  `deadlineAt` 时显示“X 秒后自动采用推荐方案”倒计时。落定后折叠为结果摘要：`source=user` 显示“已回答”，
  `source=timeout_default` 显示“已超时，采用推荐方案”。
- `HTML_REPORT`：左侧报告卡，点击进入只读 WebView。
- `RUN_STATE`：轻量行内状态或页头状态，不伪装成 AI 文本。
- `SYSTEM_ERROR`：可恢复错误条，保留重试动作与已有消息。

会话页必须把 `session.status`、对应 Runner 的 `online/leaseUntil` 和最后事件时间合成为可信状态，而不是
只展示服务端最后一次写入的状态字符串：

- `RUNNING` 且 Runner 租约有效时展示“运行中 · 开发机在线”，同时显示本轮开始和最近更新时间。
- `RUNNING` 但 Runner 缺失、离线或租约过期时展示“连接中断 · 任务状态待确认”，不得继续声称任务正在运行。
- `RUN_STATE` 事件使用带 `HH:mm` 的时间线节点；`IDLE` 明确展示“本轮完成 · 可继续”，不误写为会话结束。
- 活跃会话页除 SSE/事件补拉外，还要周期刷新 Runner catalog；刷新失败保留缓存和聊天能力，不锁死输入控件。

消息交互遵守以下客户端契约：

- `USER_MESSAGE`、`REPORT` 与 `ASSISTANT_MESSAGE` 正文都支持选择文字、复制全文和引用；Markdown 代码块继续提供独立复制。
- “编辑后发送”只把既有用户消息载入输入框并创建新消息，不修改服务端历史事件。
- 文本草稿使用本地会话 ID 隔离；新会话使用独立临时键。只有写请求成功后才能清除草稿。
- `RUNNING` 时点按发送入队，长按发送引导当前 turn；同时提供可访问的显式“引导当前任务”动作。队列面板默认折叠并
  展示队首和数量，展开后按 FIFO 顺序编辑或撤回。`WAITING_FOR_USER` 只允许入队，长按提示先回答问题。
- SSE 或轮询追加事件时，只有用户原本位于列表底部才自动跟随；否则累计新消息数量并由用户主动跳转。

## 6. Android 后台跟踪与提醒

- 存在未归档的 `QUEUED/RUNNING/WAITING_FOR_USER` 会话时，Android 启动 Work 专属前台服务；没有活跃会话时自动停止。
- 常驻通知使用低优先级通道，默认显示仓库、当前状态和活跃数量；当某个任务进入 `IDLE/COMPLETED/FAILED`
  且仍有其他活跃任务时，镜像最近一次关键结果 60 秒并保留剩余活跃数量，随后恢复默认汇总。点击镜像结果进入对应会话；
  不得包含 token、路径或消息正文。
- 新增 `ASK` 时发高优先级提醒，正文注明距自动采用推荐方案的分钟数；提醒按 `askId` 键控，`ASK_ANSWERED`
  （用户回答或超时落定）到达时取消对应通知。有 `deadlineAt` 时在截止前约 1 分钟追加一次临期提醒（服务重启后
  未补发，属于可接受的尽力而为）。新增 `REPORT/HTML_REPORT`、进入 `IDLE/COMPLETED/FAILED` 时发普通关键事件提醒。
- 提醒使用独立于消息缓存的确认游标：先发通知再确认 `(sessionId, seq)`；进程中断时允许极少量重复，不能静默漏掉 `ASK`。
- Android 13 以上未授权通知时不阻断任务创建或消息发送，只在界面提示用户无法后台提醒。
- 保存 Core 配置后立即接管已有活跃会话；断开 Core 时停止跟踪服务并移除常驻通知。

## 7. 版本与兼容

Android v2 请求携带 `X-Zhixing-Work-Protocol: 2`；Core 在迁移期同时接受 1 和 2，Runner 控制面继续用 v1 头并通过
capability 协商启用新能力。部署顺序为 Runner、Core、Android；旧 `/messages` 与附件流程保持不变，未声明能力时
Android 不展示队列或 steer，Core 也拒绝绕过能力门禁。
Phone-line 不读取旧 Work/Happy 数据；Room v33 迁移会删除旧 Work/Happy/App Server/Codex catalog 表，只保留
新版 `phone_work_sessions` 与 `phone_work_events`。Room v39→v40 为现有会话补入 `runtime=codex` 和通用
`runtime_session_id`；Room v46→v47 为现有 Work 会话补入 `fast_mode=0`（标准），Room v47→v48 增加可空
`active_turn_id`。Core 同样从旧 `codex_session_id`
回填通用 ID，并为旧会话补入标准速度。`codexSessionId` 仅对 Codex 会话保留为兼容别名。

## 8. Hook 隔离与失败语义

- `codex exec` 回退路径才携带显式 phone profile；App Server 路径显式禁用 hooks。普通 Codex Desktop/CLI 不安装、不继承该 Hook。
- 只允许 `Stop` Hook。Hook 输入只提取 `session_id`、`turn_id` 和事件名，输出只写本机单轮 outbox，不包含正文或密钥。
- Hook handler 的超时上限为 1 秒并必须静默、fail-open。Hook 没有运行、没有写入标记或写入失败时，Runner 仍按
  JSONL 的 `turn.completed` / `turn.failed` 完成会话，并以子进程退出结果作为兼容兜底。收到语义终态后 CLI
  若未在短暂宽限期内退出，Runner 必须清理其进程树；不得把 Hook 失败转换成任务失败。
- App Server `initialize` 使用 60 秒超时并且只允许一次自动重试；第一次超时后必须先清理完整进程树。该重试发生在
  任何 `thread/start|resume` 之前，不得造成重复 thread/turn。`thread/start|resume` 与 `turn/start` 使用 90 秒启动
  超时，`turn/steer|interrupt` 使用 30 秒控制超时；任何启动阶段失败都必须清理本次 App Server 进程树。
- Claude Code 会话不用该 Hook；Runner 在命令级 settings 中设置 `disableAllHooks=true`，并通过
  `--setting-sources user,project` 复用本机订阅登录或第三方 API env，通过 `--mcp-config` +
  `--strict-mcp-config` 只注入本轮 Phone-line MCP。最终 `result` 的 `is_error`、
  `api_error_status` 和错误文本优先于 `subtype` 判断成功，认证失败不得被误报为 IDLE。
