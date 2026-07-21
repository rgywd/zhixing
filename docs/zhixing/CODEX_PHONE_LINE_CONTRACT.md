# Work Phone-line v1：协议契约

状态：v1 草案（2026-07-20）

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
  "model": "gpt-5.6-sol",
  "reasoningEffort": "high",
  "sandboxMode": "danger-full-access",
  "approvalPolicy": "never",
  "status": "QUEUED",
  "lastSeq": 0,
  "archivedAt": null,
  "createdAt": "2026-07-20T00:00:00Z"
}
```

Android 不提交真实路径、任意 sandbox/approval 值或 Codex 参数。Core 只接受 Runner 已公布的 repo/model/effort 组合。
仓库 catalog 项可携带可选 `group` 和 `available`；`group` 是 Runner 配置的公开显示标签，不得包含真实绝对路径。
Android 只允许创建 `available=true` 的目录会话，并可按 `group` 分组和搜索。

Android 创建会话时可提交最多 80 字符的 `title`。当前客户端用已配置的快速模型根据首条文本生成标题；模型不可用、
生成失败或仅发送图片时使用首条文本摘要或仓库名兜底。旧客户端未提交标题时，Core 使用 `repoName`，保持 v1 向后兼容。

用户消息可额外携带 `attachmentIds`。Android 先通过 `POST /v1/work/attachments` 上传图片，再在创建会话或补充消息时
引用返回的 ID。每条消息最多 4 张，每张最大 10 MiB，仅接受 PNG、JPEG、WebP 和 GIF。事件与 Runner 命令只携带
附件 ID、文件名、MIME、大小和 SHA-256，不内嵌图片字节。

Core 耐久保存图片，但只有该会话所属 Runner 能下载；Runner 校验 SHA-256 后写入本轮专用临时目录，通过 Codex CLI
`--image` 传入，并在该轮进程退出后删除临时副本。Android 不提交或读取开发机真实文件路径。

## 2. 三个 MCP 工具

Runner 除注册工具 schema 外，还必须为每次手机会话注入专属 `developer_instructions`：说明三个工具的用途，要求在
有意义的阶段完成和本轮结束前调用 `report`，仅在阻断决策时调用 `ask`，长结构化产物使用 `report_html`。该约定不写入
用户全局配置或仓库配置，普通电脑 Codex 会话不得加载。自动 `ASSISTANT_MESSAGE` 桥接独立存在，不能以模型未调用工具为由
丢弃正常回复。

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
      ]
    }
  ]
}
```

规则：每次 1–4 题；每题 1–8 个选项；题目可单选或多选；客户端永远附带“其他”自由输入，不由模型声明关闭。

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

超时：

```json
{ "status": "timeout", "questionSetId": "ask_...", "message": "No answer within 180 seconds; stop or continue with safe assumptions." }
```

超时后答案仍可提交一次。Core 把迟到答案转换为高优先级 inbox 消息并排队 resume。

### `report_html(html, title)`

输入：

```json
{ "title": "Work 验收报告", "html": "<h2>结论</h2><p>...</p>", "clientCallId": "call_..." }
```

返回：

```json
{ "accepted": true, "reportId": "report_...", "messageId": "msg_...", "sanitized": true }
```

约束：title 1–120 字符，html 最大 1 MiB。Core 只持久化清洗并由固定模板封装后的版本，不保存原始 HTML。
报告消息只含标题、摘要、大小和 report ID。

## 3. 最小 HTTP API

### Android scope

- `GET /v1/work/runners`：Runner 在线状态与缓存版本。
- `GET /v1/work/repos?runnerId=`：仓库 catalog，支持 `ETag`。
- `POST /v1/work/attachments`：上传一张受限图片并返回附件元数据。
- `POST /v1/work/sessions`：创建会话与第一条用户消息，可携带可选 `title`。
- `GET /v1/work/sessions?cursor=`：仅返回当前用户的手机会话。
- `GET /v1/work/sessions?archived=true`：返回已归档会话；默认列表不包含归档项。
- `GET /v1/work/sessions/{id}/events?afterSeq=`：补拉有序事件。
- `GET /v1/work/sessions/{id}/stream?afterSeq=`：SSE；断开不影响写入。
- `POST /v1/work/sessions/{id}/messages`：排队补充消息。
- `POST /v1/work/sessions/{id}/asks/{askId}/answer`：提交答案。
- `POST /v1/work/sessions/{id}/stop`：停止当前进程，会话进入 IDLE。
- `POST /v1/work/sessions/{id}/complete`：显式结束会话。
- `POST /v1/work/sessions/{id}/archive`：归档非活跃会话；运行中、等待中和排队中的会话返回 409。
- `POST /v1/work/sessions/{id}/unarchive`：恢复到默认列表；保留原事件和 `codexSessionId`。
- `POST /v1/work/sessions/{id}/revoke-tokens`：立即撤销该会话已签发的全部 MCP token。
- `GET /v1/work/reports/{id}`：只读清洗报告。

### Runner scope

- `POST /v1/runner/register`：登记进程级 `instanceId`、版本、能力和仓库 catalog；同一实例可在本机目录变化后重新登记
  catalog，新实例回收旧实例命令。
- `POST /v1/runner/heartbeat`：按 `runnerId + instanceId` 续租当前实例持有的命令。
- `GET /v1/runner/commands?runnerId=&instanceId=`：拉取当前实例的有序命令。
- `POST /v1/runner/commands/{id}/ack`：当前实例领取/完成/失败。
- `POST /v1/runner/sessions/{id}/state`：当前实例写入进程生命周期。
- `POST /v1/runner/sessions/{id}/events`：当前实例幂等写入经过白名单映射的 Codex 可见事件。
- `GET /v1/runner/attachments/{id}?runnerId=`：下载分配给本 Runner 会话的图片附件。

### MCP session scope

- `POST /v1/mcp/sessions/{id}/report`
- `POST /v1/mcp/sessions/{id}/ask`
- `POST /v1/mcp/sessions/{id}/report-html`

MCP token 只允许以上三个接口，且 URL 中 session ID 必须与 token claim 相同。token 具有独立 `jti`、24 小时
有效期和服务端撤销记录；每次 START/RESUME 都签发新 token，完成会话会撤销该会话全部 token。

## 4. 事件顺序与并发

- 每个 session 使用独立、单调递增的 `seq`；Android 以 `(sessionId, seq)` 去重。
- 同一 session 同时最多一个 Runner lease 和一个 Codex 子进程。
- 手机消息先持久化再入命令队列；Core 返回 2xx 只表示已耐久接收，不表示 Codex 已阅读。
- `report` 读取 inbox 时使用租约式 cursor：响应已包含的消息在下一次成功提交 cursor 后才确认，避免进程崩溃丢消息。
- stop 与新消息竞态时，先完成 stop；新消息保留为待 resume，不静默丢弃。
- Runner 事件必须携带 `clientEventId`。首期只允许 `ASSISTANT_MESSAGE`，其正文来自公开 `codex exec --json`
  的 `item.completed` + `agent_message`；Core 拒绝 reasoning、命令、工具参数和任意自定义事件类型。
- Runner 必须先把消息写入本地 outbox，再异步上传；本轮 `IDLE/FAILED` transition 必须排在尚未确认的消息之后。
- 与紧邻 `REPORT` 或既有 `ASSISTANT_MESSAGE` 完全相同的文本由 Core 幂等合并，避免模型显式汇报后重复展示。

## 5. Android 展示映射

- `USER_MESSAGE`：右侧普通消息气泡。
- `REPORT`：左侧 Markdown 消息；不展示“工具调用 report”。
- `ASSISTANT_MESSAGE`：左侧普通 Markdown 消息；与正常聊天回复使用相同视觉，不显示 JSONL/Hook 来源。
- `ASK`：左侧原生问题卡，回答后折叠为结果摘要。
- `HTML_REPORT`：左侧报告卡，点击进入只读 WebView。
- `RUN_STATE`：轻量行内状态或页头状态，不伪装成 AI 文本。
- `SYSTEM_ERROR`：可恢复错误条，保留重试动作与已有消息。

消息交互遵守以下客户端契约：

- `USER_MESSAGE`、`REPORT` 与 `ASSISTANT_MESSAGE` 正文都支持选择文字、复制全文和引用；Markdown 代码块继续提供独立复制。
- “编辑后发送”只把既有用户消息载入输入框并创建新消息，不修改服务端历史事件。
- 文本草稿使用本地会话 ID 隔离；新会话使用独立临时键。只有写请求成功后才能清除草稿。
- SSE 或轮询追加事件时，只有用户原本位于列表底部才自动跟随；否则累计新消息数量并由用户主动跳转。

## 6. Android 后台跟踪与提醒

- 存在未归档的 `QUEUED/RUNNING/WAITING_FOR_USER` 会话时，Android 启动 Work 专属前台服务；没有活跃会话时自动停止。
- 常驻通知使用低优先级通道，默认显示仓库、当前状态和活跃数量；当某个任务进入 `IDLE/COMPLETED/FAILED`
  且仍有其他活跃任务时，镜像最近一次关键结果 60 秒并保留剩余活跃数量，随后恢复默认汇总。点击镜像结果进入对应会话；
  不得包含 token、路径或消息正文。
- 新增 `ASK` 时发高优先级提醒；新增 `REPORT/HTML_REPORT`、进入 `IDLE/COMPLETED/FAILED` 时发普通关键事件提醒。
- 提醒使用独立于消息缓存的确认游标：先发通知再确认 `(sessionId, seq)`；进程中断时允许极少量重复，不能静默漏掉 `ASK`。
- Android 13 以上未授权通知时不阻断任务创建或消息发送，只在界面提示用户无法后台提醒。
- 保存 Core 配置后立即接管已有活跃会话；断开 Core 时停止跟踪服务并移除常驻通知。

## 7. 版本与兼容

请求头携带 `X-Zhixing-Work-Protocol: 1`。Core 在不认识主版本时返回 `426 Upgrade Required`；新增可选字段保持向后兼容。
Phone-line v1 不读取旧 Work/Happy 数据；Room v33 迁移会删除旧 Work/Happy/App Server/Codex catalog 表，只保留
新版 `phone_work_sessions` 与 `phone_work_events`。

## 8. Hook 隔离与失败语义

- 仅 Runner 发起的手机会话携带显式 phone profile；普通 Codex Desktop/CLI 不安装、不继承该 Hook。
- 只允许 `Stop` Hook。Hook 输入只提取 `session_id`、`turn_id` 和事件名，输出只写本机单轮 outbox，不包含正文或密钥。
- Hook handler 的超时上限为 1 秒并必须静默、fail-open。Hook 没有运行、没有写入标记或写入失败时，Runner 仍按
  JSONL 的 `turn.completed` / `turn.failed` 完成会话，并以子进程退出结果作为兼容兜底。收到语义终态后 CLI
  若未在短暂宽限期内退出，Runner 必须清理其进程树；不得把 Hook 失败转换成任务失败。
