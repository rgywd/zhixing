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
  "model": "gpt-5.6-sol",
  "reasoningEffort": "high",
  "sandboxMode": "danger-full-access",
  "approvalPolicy": "never",
  "status": "QUEUED",
  "lastSeq": 0,
  "createdAt": "2026-07-20T00:00:00Z"
}
```

Android 不提交真实路径、任意 sandbox/approval 值或 Codex 参数。Core 只接受 Runner 已公布的 repo/model/effort 组合。

## 2. 三个 MCP 工具

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
- `POST /v1/work/sessions`：创建会话与第一条用户消息。
- `GET /v1/work/sessions?cursor=`：仅返回当前用户的手机会话。
- `GET /v1/work/sessions/{id}/events?afterSeq=`：补拉有序事件。
- `GET /v1/work/sessions/{id}/stream?afterSeq=`：SSE；断开不影响写入。
- `POST /v1/work/sessions/{id}/messages`：排队补充消息。
- `POST /v1/work/sessions/{id}/asks/{askId}/answer`：提交答案。
- `POST /v1/work/sessions/{id}/stop`：停止当前进程，会话进入 IDLE。
- `POST /v1/work/sessions/{id}/complete`：显式结束会话。
- `GET /v1/work/reports/{id}`：只读清洗报告。

### Runner scope

- `POST /v1/runner/register`：登记进程级 `instanceId`、版本、能力和仓库 catalog；新实例回收旧实例命令。
- `POST /v1/runner/heartbeat`：按 `runnerId + instanceId` 续租当前实例持有的命令。
- `GET /v1/runner/commands?runnerId=&instanceId=`：拉取当前实例的有序命令。
- `POST /v1/runner/commands/{id}/ack`：当前实例领取/完成/失败。
- `POST /v1/runner/sessions/{id}/state`：当前实例写入进程生命周期。

### MCP session scope

- `POST /v1/mcp/sessions/{id}/report`
- `POST /v1/mcp/sessions/{id}/ask`
- `POST /v1/mcp/sessions/{id}/report-html`

MCP token 只允许以上三个接口，且 URL 中 session ID 必须与 token claim 相同。

## 4. 事件顺序与并发

- 每个 session 使用独立、单调递增的 `seq`；Android 以 `(sessionId, seq)` 去重。
- 同一 session 同时最多一个 Runner lease 和一个 Codex 子进程。
- 手机消息先持久化再入命令队列；Core 返回 2xx 只表示已耐久接收，不表示 Codex 已阅读。
- `report` 读取 inbox 时使用租约式 cursor：响应已包含的消息在下一次成功提交 cursor 后才确认，避免进程崩溃丢消息。
- stop 与新消息竞态时，先完成 stop；新消息保留为待 resume，不静默丢弃。

## 5. Android 展示映射

- `USER_MESSAGE`：右侧普通消息气泡。
- `REPORT`：左侧 Markdown 消息；不展示“工具调用 report”。
- `ASK`：左侧原生问题卡，回答后折叠为结果摘要。
- `HTML_REPORT`：左侧报告卡，点击进入只读 WebView。
- `RUN_STATE`：轻量行内状态或页头状态，不伪装成 AI 文本。
- `SYSTEM_ERROR`：可恢复错误条，保留重试动作与已有消息。

## 6. 版本与兼容

请求头携带 `X-Zhixing-Work-Protocol: 1`。Core 在不认识主版本时返回 `426 Upgrade Required`；新增可选字段保持向后兼容。
Phone-line v1 不读取旧 Work/Happy 数据；Room v33 迁移会删除旧 Work/Happy/App Server/Codex catalog 表，只保留
新版 `phone_work_sessions` 与 `phone_work_events`。
