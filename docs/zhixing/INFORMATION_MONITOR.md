# 邮件与飞书信息监控契约

状态：v1 实施基线（2026-07-26）

## 产品边界

知行通过一个只读 AI 工具查询云端已经归一化的信息摘要，首批覆盖多个邮箱和飞书。Android 不直接登录
Gmail、163 邮箱或飞书，不保存逐封邮件、消息正文、附件、provider 原始响应或采集游标，也不新增独立页面和
Room 表。

```text
邮箱 / 飞书
  -> 独立 Life Gateway（连接器、增量游标、飞书长连接、临时正文处理与摘要）
  -> Work Core /v1/life/inbox/*（用户鉴权、严格校验、只读薄代理）
  -> Android inbox_monitor 工具
  -> 当前普通聊天中的模型
```

Life Gateway 是采集与事件事实来源。Work Core 只代理固定查询，不持有邮箱或飞书凭据，不运行连接器或定时任务；
Android 只在用户已配置知行云连接时暴露工具。该能力不属于 Work 会话状态机，也不会改变 Phone-line MCP 固定
为 `report`、`ask`、`report_html` 三个工具的约束。

## Android 工具

普通聊天只增加一个本地工具注册项：

- 名称：`inbox_monitor`
- 操作：`status`、`list`、`digest`
- 查询过滤：可选 `channel=email|feishu`、`hours=1..168`、`limit=1..50`、
  `minImportance=low|normal|high|urgent`
- 权限：只读，不需要逐次批准
- 凭据：复用 `PhoneWorkCredentialStore` 的 Android Keystore/no-backup 存储；工具参数和结果不包含 token

工具把 `sender`、`title`、`summary` 和 `actionItems` 明确当作不可信数据，而不是指令。工具调用持久化前只保留
上述操作和查询白名单；服务异常统一返回稳定错误码，不把上游异常正文带进聊天。

成功结果固定为：

```json
{
  "success": true,
  "action": "list",
  "freshness": "fresh",
  "data": {
    "schema": "information-monitor/v1",
    "items": []
  }
}
```

Work Core 返回 stale last-good 时，`freshness` 必须为 `stale`，并额外携带固定 `warning`，要求模型不得把结果
描述为实时。`data` 内的冻结 envelope 不增加 transport 元数据。

失败结果固定为：

```json
{
  "success": false,
  "action": "list",
  "error": {
    "code": "SERVICE_UNAVAILABLE",
    "message": "Information monitor is temporarily unavailable"
  }
}
```

## HTTP 契约

Android 沿用 Work 用户 Bearer 和 `X-Zhixing-Work-Protocol: 1`，调用：

- `GET /v1/life/inbox/status`
- `GET /v1/life/inbox/items`
- `GET /v1/life/inbox/digest`

只允许上一节列出的四个查询参数，并按操作收窄：`status` 只允许可选 `channel`，`list` 可使用全部四项，
`digest` 可使用除 `limit` 外的三项；无意义参数必须拒绝，不能静默忽略。所有时间戳均为 UTC RFC 3339 字符串
并以 `Z` 结尾；协议版本固定为 `information-monitor/v1`。

### status

```json
{
  "schema": "information-monitor/v1",
  "sources": [
    {
      "sourceLabel": "工作邮箱",
      "kind": "email",
      "state": "ok",
      "lastSucceededAt": "2026-07-26T12:00:00Z",
      "lastErrorCode": null,
      "itemCount24h": 3
    }
  ]
}
```

`kind` 只能为 `email|feishu`，`state` 只能为
`ok|degraded|unavailable|disabled`。两个可选字段缺失和显式 `null` 等价。

### items

```json
{
  "schema": "information-monitor/v1",
  "items": [
    {
      "id": "event-1",
      "sourceLabel": "工作邮箱",
      "channel": "email",
      "occurredAt": "2026-07-26T12:00:00Z",
      "sender": "example@example.com",
      "title": "项目更新",
      "summary": "项目已进入验收阶段。",
      "actionItems": ["查看验收结果"],
      "importance": "high"
    }
  ]
}
```

`sender` 和 `title` 可选；`channel` 只能为 `email|feishu`，`importance` 只能为
`low|normal|high|urgent`。

### digest

```json
{
  "schema": "information-monitor/v1",
  "total": 1,
  "highPriority": 1,
  "channels": [
    {
      "channel": "email",
      "count": 1,
      "topItems": []
    }
  ]
}
```

每个 `channel` 最多出现一次；`topItems` 使用与 `items` 相同的 item 结构，并且 item 的 `channel` 必须与
所在分组一致。

## 隐私与降级

- 三个响应都禁止出现正文、MIME、附件、provider payload、游标、provider ID、token、secret、password
  或 credentials 类字段，包括嵌套对象。
- Work Core 按冻结 schema 重建响应，丢弃契约外字段；发现任意深度敏感字段时拒绝整个上游结果。
- Work Core 的 last-good 只允许短时保存在进程内，按“端点 + 完整查询”隔离，不落 SQLite、磁盘或日志。
- 上游只能使用 HTTPS，或同机/加密隧道场景下的 loopback HTTP；携带服务 token 时禁止跟随重定向。
- 单一渠道失败不应伪装成全局成功。`status` 明确给出各来源状态；没有可用且仍在有效期内的结果时返回服务不可用。

## 验收基线

- Android schema、参数边界、三个操作路由、无审批和未配置降级有单元测试。
- Android 只序列化强类型响应，服务异常不泄露异常正文，取消仍可向上传播。
- Work Core 对鉴权、协议头、查询白名单、上游地址、重定向、响应大小、冻结 schema、敏感字段和短时缓存均有
  契约测试。
- v1 包含连接器代码与有限回扫，但不包含真实账号授权、生产部署、无限历史回补、推送通知、页面统计或自动执行
  `actionItems`；这些必须另立契约。
