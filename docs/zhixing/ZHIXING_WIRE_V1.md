# Zhixing Wire Protocol v1

状态：Implemented；会话保真扩展 v1.1 已落地
日期：2026-07-19
跟踪：GitHub Issue #49

## 1. 范围

Wire v1 是 Android、zhixing-agent 和自托管 Zhixing Relay 之间的稳定协议。Codex App Server 协议只存在于 Agent 本机，不直接传到 Android。

Wire v1 提供：

- 版本和 capability 握手。
- 设备身份、配对与撤销。
- E2E encrypted envelope、ACK、增量和快照。
- Machine/Project/Thread 目录同步。
- Thread/Turn/Approval 运行控制和规范化事件。
- 离线、幂等、乱序、删除与降级语义。

## 2. 版本规则

- `wireMajor`：破坏兼容时递增。不同 major 禁止写操作。
- `wireMinor`：只增加可选字段、事件或 capability。
- 未识别字段必须忽略并保留；未识别必需 capability 必须拒绝对应操作。
- major 不兼容时，Android仍可读取本地已解密缓存，但不得向 Agent 发送控制请求。

连接握手：

```json
{
  "wireMajor": 1,
  "wireMinor": 1,
  "clientKind": "android",
  "clientVersion": "0.2.0",
  "deviceId": "opaque-id",
  "capabilities": [
    "catalog.v1",
    "thread.read.v1",
    "thread.chunk.v1",
    "runtime.v1",
    "runtime.settings.v1",
    "composer.v1",
    "attachment.v1",
    "approval.v1"
  ]
}
```

Agent 的加密能力载荷额外包含：

```json
{
  "agentVersion": "0.2.0",
  "codexVersion": "0.144.0",
  "codexSchemaHash": "sha256",
  "platformFamily": "windows",
  "platformOs": "windows",
  "operations": [
    "thread.list",
    "thread.read",
    "thread.resume",
    "turn.start",
    "turn.steer",
    "model.list",
    "permissionProfile.list",
    "skills.list"
  ]
}
```

v1.1 只增加可选 payload、字段和 capability，不改变 envelope、AAD、密钥或 ACK 规则。v1.0 客户端可以
继续读取项目目录和旧的纯文本 Thread fallback；缺少 `composer.v1` 或 `runtime.settings.v1` 时不得显示相应
控制项为可用。

## 3. 身份和密钥

### 3.1 Root secret

用户持有 32-byte recovery secret。它只存在于已配对设备，不上传 Relay。

用途必须域分离：

- auth seed：`HMAC-SHA512(key=UTF8("Zhixing Wire v1 auth"), data=rootSecret)[0..31]`
- content seed：`HMAC-SHA512(key=UTF8("Zhixing Wire v1 content"), data=rootSecret)[0..31]`

auth seed 直接作为 Ed25519 seed；content seed 直接作为 X25519/NaCl box secret key。不得继续使用 `Happy EnCoder` 派生域。

### 3.2 Account 与 device

- `accountId = base64url(SHA-256(authPublicKey))`。
- `deviceId` 是配对时生成的随机稳定 ID。
- 每台设备有独立 X25519 key pair，可被撤销。
- Relay challenge 必须是一次性随机值并带过期时间；已使用 challenge 不能重放。

### 3.3 Data key

- catalog、每个 Thread 内容流和控制流使用独立随机 32-byte data key。
- data key 对每台授权设备使用 NaCl box 封装。
- 撤销设备后新数据必须轮换 key；历史数据是否重加密由显式安全操作决定。

data key 通过一个设备定向的保留 envelope 分发，避免把 key wrapper 放进尚需该 key 才能解密的 payload：

- `targetId` 是接收设备 ID；
- `streamId` 是 `keys_<deviceId>`；
- `keyId` 是随后数据 envelope 引用的随机 ID；
- `cipherBundle` 直接承载下述 NaCl box 封装，不是 AES-GCM bundle；
- 接收端必须先成功解封、加密保存 wrapper 并 ACK key envelope，才能应用引用该 `keyId` 的数据 envelope；
- `keys_` 是 Wire v1 保留 stream 前缀，其他 stream 的 `cipherBundle` 仍严格使用第 5 节格式。

发送端要把待提交 envelope 原样持久化后再请求 Relay。进程崩溃时重放同一个 envelope ID 和密文，不能重新生成同一 seq；
Relay 返回 accepted/duplicate 后才推进本地连续序号。

封装格式是 base64url（无 padding）编码的：

```text
0x01 || ephemeralPublicKey[32] || nonce[24] || NaClBox(dataKey[32])[48]
```

收件设备使用自己的 content secret key、bundle 内 ephemeral public key 和 nonce 解包。version 0 属于 Happy 历史格式，Wire v1 只写 version 1。

## 4. Relay-visible envelope

```json
{
  "v": 1,
  "id": "uuidv7",
  "accountId": "opaque",
  "senderDeviceId": "opaque",
  "targetId": "opaque-device-or-account",
  "streamId": "opaque",
  "seq": 42,
  "createdAt": 1784397723000,
  "expiresAt": null,
  "keyId": "opaque",
  "cipherBundle": "base64url"
}
```

约束：

- ID 字段只允许 `[A-Za-z0-9_-]`，不能包含控制字符。
- `(accountId, senderDeviceId, streamId, seq)` 唯一且单调；同一 tuple 对应不同 `id` 或密文时必须拒绝为 sequence collision。
- JSON 中的 seq 和毫秒时间戳必须位于 `0..2^53-1`，保证 Node/Kotlin 无损互操作；AAD 仍使用 U64BE/I64BE 固定宽度编码。
- `id` 全局幂等；重复提交返回首次 ACK。
- Relay 不能修改 envelope 后仍通过认证。
- Relay 不读取解密 payload 的 `type`。

## 5. Cipher bundle v1

Happy AES bundle version 0 没有 AAD，Wire v1 必须使用独立格式。envelope 的 `cipherBundle` 是下列完整字节序列的 base64url 编码，不再重复传递 nonce：

```text
0x01 || nonce[12] || AES-256-GCM(ciphertext || tag[16])
```

`ciphertext` 是 UTF-8 JSON payload。AAD 是以下确定性二进制：

```text
UTF8("ZXW1")
|| LP(id)
|| LP(accountId)
|| LP(senderDeviceId)
|| LP(targetId)
|| LP(streamId)
|| U64BE(seq)
|| I64BE(createdAt)
|| I64BE(expiresAt or -1)
|| LP(keyId)
```

`LP(x) = U16BE(UTF8(x).length) || UTF8(x)`。任一 AAD 字段改变必须认证失败。nonce 必须随机且在同一 key 下不可复用。

## 6. 解密 payload

所有 payload 共有：

```json
{
  "type": "catalog.snapshot",
  "schema": 1,
  "requestId": null,
  "sentAt": 1784397723000,
  "body": {}
}
```

未知 `type` 或更高 schema 不得破坏整条流；客户端记录 opaque 诊断并发送 unsupported capability 错误。

## 7. Catalog

### 7.1 Machine

```json
{
  "machineId": "uuid",
  "displayName": "Minecraft",
  "platformFamily": "windows",
  "agentVersion": "0.2.0",
  "codexVersion": "0.144.0",
  "capabilities": [],
  "lastSeenAt": 1784397723000
}
```

### 7.2 Project

```json
{
  "projectId": "uuid",
  "machineId": "uuid",
  "displayName": "zhixing",
  "canonicalRoot": "C:\\Users\\...\\zhixing",
  "vcs": {"kind": "git", "remote": null},
  "updatedAt": 1784397723000
}
```

`projectId` 是随机持久化 ID；禁止裸路径 hash。canonicalRoot 只存在于密文中。

### 7.3 ThreadSummary

```json
{
  "threadId": "codex-thread-uuid",
  "projectId": "uuid",
  "name": "接入 AnySearch 搜索服务",
  "preview": "...",
  "createdAt": 1784390000000,
  "updatedAt": 1784397000000,
  "recencyAt": 1784397000000,
  "archived": false,
  "source": "vscode",
  "threadSource": null,
  "parentThreadId": null,
  "forkedFromId": null,
  "lastConfirmedTurnStatus": "completed",
  "runtimeState": "unknown"
}
```

`threadId` 保存 Codex App Server 返回的 Thread ID，并以 `(machineId, threadId)` 作为全局业务键；产品层不得另造会话 ID。`runtimeState` 只能是 Agent 已确认的 `connected|idle|running|waiting_approval|disconnected|unknown`。Desktop 来源且无 RuntimeBinding 时必须是 `unknown`。

### 7.4 Snapshot 和 delta

- 每台机器维护单调 `catalogRevision`。
- snapshot 带完整 summary 集和 `revision`。
- delta 带 `baseRevision` 和 `revision`；base 不匹配时拒绝应用并请求 snapshot。
- `thread/list` 使用 cursor 完整分页；默认包含 cli/vscode/appServer 主来源，排除 `subAgent*`。
- subagent 通过 `parentThreadId` 挂载；Automation 根据 `threadSource`/已验证元数据标记，不能误用 `sourceKinds`。
- archived 使用独立分页。

当完整 Catalog snapshot 的 UTF-8 JSON 超过发送端直传阈值时，改发 `catalog.snapshot.chunk`：

```json
{
  "snapshotId": "uuidv7",
  "revision": 12,
  "generatedAt": 1784397723000,
  "machine": {},
  "chunkIndex": 0,
  "chunkCount": 13,
  "contentHash": "sha256-of-concatenated-raw-chunks",
  "chunkHash": "sha256-of-this-raw-chunk",
  "contentBase64": "base64url-of-utf8-json"
}
```

raw chunk JSON 只包含 `projects` 和 `threads`；projects 只需出现在首块。接收端逐块解密后先验证 `chunkHash`，
持久化所有 raw chunk；只有索引连续、元数据一致、块数齐全且拼接后的 `contentHash` 通过，才在一个 Room 事务中替换旧目录。
缺块、应用重启或新快照未完成时继续展示旧 revision。未完成块最多保留 24 小时。

## 8. Thread detail 与 chunk

`thread.read` 由 Agent 调用 `thread/read(includeTurns=true)` 并规范化 Turn/Item。

Thread detail 不得只保存 `text/status`。规范化 Item 至少包含：

```json
{
  "itemId": "opaque",
  "type": "commandExecution",
  "role": "tool",
  "status": "completed",
  "content": {
    "command": "./gradlew test",
    "cwd": "C:\\repo",
    "output": "..."
  },
  "raw": {}
}
```

`raw` 是结构化前向兼容载体，经 E2E 加密后传输并只落在已配对设备本地；日志不得输出其明文。Android 的
projector 对已知类型读取稳定字段，未知类型隔离为通用 Tool fallback。用户输入中的
text/image/localImage/skill/mention 必须保留类型。旧客户端使用可选 `text` fallback。

App Server 最后确认的 model、reasoningEffort、serviceTier、permission profile 和 token usage 通过同一 Thread
流中的 `thread.settings` / `token.usage` RuntimeEvent 独立持久化，不由 Android 根据请求值猜测。

大历史必须使用 chunk：

```json
{
  "detailId": "uuidv7",
  "machineId": "machine",
  "threadId": "codex-thread-uuid",
  "revision": 1740000000000,
  "chunkIndex": 0,
  "chunkCount": 4,
  "contentHash": "sha256-of-complete-plaintext",
  "chunkHash": "sha256-of-this-chunk",
  "contentBase64": "base64url-of-utf8-detail-chunk"
}
```

- 单 chunk 解密明文上限和密文上限由 capability 声明。
- 所有 chunk、总数和 hash 验证成功后才能提交新 revision。
- 缺块、重复冲突或 hash 不符时保留旧历史，并自动重试整个 detail 请求。
- chunk 以受控 UTF-8 字节数切分，不能仅按 Item 数量估算；任何单 envelope 必须低于 Relay body/cipher limit。
- 首次读取失败是可恢复状态；Android 自动重试并允许用户立即重试，不能吞错后永久显示空历史。

### 8.1 运行选项目录

Android 使用 `runtime.catalog` 命令按 Thread CWD 请求一次版本化目录，Agent 聚合：

- `model/list`：id、displayName、inputModalities、supportedReasoningEfforts、serviceTiers、isDefault；
- `permissionProfile/list`：profile id、displayName、description、allowed；
- `skills/list`：name、path、description、enabled；
- `plugin/list` / `app/list`：安装、启用、授权和不可用原因。

这些目录只存在于 E2E 密文。Android 不把本地 Provider `Model` 列表当成 Codex 模型事实。插件或 App
端点失败时 catalog 必须返回 `capabilities.<name>.available=false` 和脱敏错误，不能把失败伪装成“列表为空”。

### 8.2 附件

手机附件沿现有 E2E command stream 用 `attachment.upload` 分片。每块包含 attachmentId、fileName、MIME、
完整内容 SHA-256、chunkIndex/count 和 contentBase64。Agent 校验附件 ID、文件名、MIME、每块 512 KiB 上限、
总计 20 MiB 上限与完整 SHA-256 后写入 `~/.zhixing-agent/uploads/<attachmentId>/`，返回受控 localPath；Android
只能在最后一块确认完成后发 Turn。

- 图片 receipt 转为 app-server `localImage`；
- 通用文件转为受控开发机路径并以 mention/path + 文本提交；
- Android 不允许用户输入或浏览开发机绝对路径；仅转发 Agent 对上传返回的路径；
- Agent 拒绝路径逃逸、超限、hash 冲突和未知 attachmentId，并按 TTL 清理。

读取历史中的开发机 `localImage` 使用 `attachment.download`：Agent 先读取指定 Thread 的完整 raw 历史，只允许
下载其中实际引用的绝对路径，再以 320 KiB 分块返回；Android 校验 count 和完整 SHA-256 后存入自己的聊天文件区，
并持久化 remotePath → localUri 映射。Agent 不提供任意文件读取接口。

## 9. 控制请求

Android 只发送：

- `thread.start`
- `thread.resume`
- `thread.fork`
- `thread.archive`
- `thread.unarchive`
- `thread.delete`
- `turn.start`
- `turn.steer`
- `turn.interrupt`
- `approval.resolve`
- `interaction.resolve`
- `runtime.catalog`
- `attachment.upload`
- `attachment.download`

所有请求包含 `requestId`、machineId、threadId（如适用）、用户可见目标摘要和 deadline。Agent 必须返回同 requestId 的一次性结果。

`turn.start` / `turn.steer` 使用结构化 input，不再只有 `text`：

```json
{
  "input": [
    {"type":"text","text":"修复这个问题"},
    {"type":"localImage","path":"C:\\...\\uploads\\opaque\\screen.png","detail":"auto"},
    {"type":"skill","name":"ui-review","path":"opaque-catalog-path"}
  ],
  "model":"gpt-5.6-sol",
  "effort":"high",
  "serviceTier":"priority",
  "permissions":":workspace"
}
```

Skill path 必须来自 Agent 发布的目录；localImage/mention path 必须来自本次 E2E 上传回执。Agent 将结构化 input
原样交给当前 App Server；未知输入类型拒绝对应 Turn，不得静默丢弃后只发送文本。

`approval.resolve` 的 decision 支持 `accept/decline/cancel`；`interaction.resolve` 支持答案或 `decision=cancel`。
Android 必须在对应 Tool/ask_user 内提供取消入口，Agent 将 cancel 原样完成 App Server 的挂起请求。

恢复优先使用 threadId。同一 Thread 已有 RuntimeBinding 时重连现有 binding。Desktop 状态未知时，resume 必须带用户确认标记；Agent 不做静默危险重试。

## 10. 规范化事件

- `thread.upserted|archived|deleted|status_changed`
- `turn.started|completed`
- `item.started|delta|completed`
- `approval.requested|resolved`
- `sync.snapshot|delta|gap`
- `runtime.connected|disconnected`
- `error`

v1.1 RuntimeEvent 的共同字段为 `eventId/threadId/turnId/itemId/sequence/type/at/payload`。事件至少覆盖：

- `item.agent_message.delta`
- `item.reasoning.summary_part_added|summary_delta|text_delta`
- `item.plan.delta|updated`
- `item.command.output_delta`
- `item.file.output_delta|patch_updated`
- `item.mcp.progress`
- `token.usage`
- `thread.settings`

App Server 原始 delta 在 Agent 内按 itemId 聚合；Wire 的 `item.delta.payload` 是该 Item 当前累计 snapshot。
Android 按 envelope sequence 去重并按 itemId upsert；发现 gap 时请求 Thread snapshot，而不是把下一段文本直接拼上。

未知 Codex Item 保存 `kind + opaque payload` 到诊断层，不进入普通 Agent 正文。

## 11. ACK、重试和顺序

- ACK key：`accountId + senderDeviceId + streamId + seq`。
- 客户端保存每个 sender/stream 的最高连续 ACK。
- 重连从最后 ACK 续传。
- duplicate：返回原 ACK，不重复应用。
- out-of-order：暂存到有界窗口；超过窗口或超时发送 `sync.gap`。
- expired 控制请求不得执行；历史快照可以没有 expiresAt。
- 退避使用指数增长和 jitter，危险控制请求只允许相同 requestId 重试。
- 新注册设备第一次看到某个 sender/stream 时，可以把首个已投递 seq 作为该设备的初始连续基线；此后必须严格连续。

## 12. 归档和删除

- `archive` 是默认整理操作。
- `delete` 要求开发机在线、App Server 成功确认，然后写 tombstone。
- tombstone 包含 machineId、threadId、deletionRevision、deletedAt，不含正文。
- 所有设备确认 tombstone 或超过明确保留期后，Relay 才清理旧密文。
- 手机只清 Room 缓存是本地操作，不能显示为永久删除。
- tombstone revision 阻止旧设备重新上传已删除 Thread。

## 13. 错误

稳定错误码：

- `WIRE_MAJOR_UNSUPPORTED`
- `CAPABILITY_UNSUPPORTED`
- `AUTH_FAILED`
- `DEVICE_REVOKED`
- `DECRYPTION_FAILED`
- `REPLAY_REJECTED`
- `REVISION_CONFLICT`
- `CHUNK_INCOMPLETE`
- `MACHINE_OFFLINE`
- `RUNTIME_STATE_UNKNOWN`
- `CODEX_VERSION_UNSUPPORTED`
- `CODEX_REQUEST_FAILED`
- `REQUEST_EXPIRED`

错误正文不得包含 token、seed、data key、完整路径或未脱敏工具参数。

## 14. 必须测试

- Node/Kotlin AES-GCM + AAD 交叉向量。
- 任一 AAD 字段篡改认证失败。
- duplicate、reordered、gap、过期和 requestId 重试。
- chunk 缺失、冲突和 hash 不符不覆盖旧数据。
- tombstone resurrection 被拒绝。
- Windows/WSL/Git root 路径归一。
- wire major、capability、Codex schema 不兼容的只读降级。
- Relay 数据库和日志明文扫描。
- 备份、恢复、重启持久化和 0.1.13 回滚。
- snapshot 与等价 runtime event replay 得到相同结构化 `UIMessagePart`。
- model/effort/tier/profile 只接受 catalog 中当前模型支持值，并以 App Server 确认响应为准。
- 图片/文件 attachment 的 hash、缺块、重复、路径逃逸、大小限制和 TTL 清理。
- Android 与 Agent 共同读取的 `runtime-command-turn-start` fixture，防止跨语言字段漂移。
- v1.0 客户端面对 v1.1 可选 payload 明确只读降级，不执行缺少 capability 的写操作。

## 15. 明确不承诺

- Relay 隐藏流量元数据。
- 对已配对恶意设备或被攻破开发机保密。
- 绕过 OpenAI 账号、组织或区域策略。
- 实时控制 Desktop 私有 App Server。
