# Zhixing Wire Protocol v1

状态：Draft contract
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
  "wireMinor": 0,
  "clientKind": "android",
  "clientVersion": "0.2.0",
  "deviceId": "opaque-id",
  "capabilities": ["catalog.v1", "thread.read.v1", "runtime.v1", "approval.v1"]
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
  "operations": ["thread.list", "thread.read", "thread.resume", "turn.start"]
}
```

## 3. 身份和密钥

### 3.1 Root secret

用户持有 32-byte recovery secret。它只存在于已配对设备，不上传 Relay。

用途必须域分离：

- auth seed：`HMAC-SHA512("Zhixing Wire v1 auth", rootSecret)[0..31]`
- content seed：`HMAC-SHA512("Zhixing Wire v1 content", rootSecret)[0..31]`

auth seed 生成 Ed25519 签名身份；content seed 生成 X25519/NaCl box 内容密钥。不得继续使用 `Happy EnCoder` 派生域。

### 3.2 Account 与 device

- `accountId = base64url(SHA-256(authPublicKey))`。
- `deviceId` 是配对时生成的随机稳定 ID。
- 每台设备有独立 X25519 key pair，可被撤销。
- Relay challenge 必须是一次性随机值并带过期时间；已使用 challenge 不能重放。

### 3.3 Data key

- catalog、每个 Thread 内容流和控制流使用独立随机 32-byte data key。
- data key 对每台授权设备使用 NaCl box 封装。
- 撤销设备后新数据必须轮换 key；历史数据是否重加密由显式安全操作决定。

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

## 8. Thread detail 与 chunk

`thread.read` 由 Agent 调用 `thread/read(includeTurns=true)` 并规范化 Turn/Item。

大历史使用 chunk：

```json
{
  "snapshotId": "uuidv7",
  "threadId": "codex-thread-uuid",
  "revision": 9,
  "chunkIndex": 0,
  "chunkCount": 4,
  "contentHash": "sha256-of-complete-plaintext",
  "items": []
}
```

- 单 chunk 解密明文上限和密文上限由 capability 声明。
- 所有 chunk、总数和 hash 验证成功后才能提交新 revision。
- 缺块、重复冲突或 hash 不符时保留旧 revision，并请求缺失块。

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

所有请求包含 `requestId`、machineId、threadId（如适用）、用户可见目标摘要和 deadline。Agent 必须返回同 requestId 的一次性结果。

恢复优先使用 threadId。同一 Thread 已有 RuntimeBinding 时重连现有 binding。Desktop 状态未知时，resume 必须带用户确认标记；Agent 不做静默危险重试。

## 10. 规范化事件

- `thread.upserted|archived|deleted|status_changed`
- `turn.started|completed`
- `item.started|delta|completed`
- `approval.requested|resolved`
- `sync.snapshot|delta|gap`
- `runtime.connected|disconnected`
- `error`

未知 Codex Item 保存 `kind + opaque payload` 到诊断层，不进入普通 Agent 正文。

## 11. ACK、重试和顺序

- ACK key：`accountId + senderDeviceId + streamId + seq`。
- 客户端保存每个 sender/stream 的最高连续 ACK。
- 重连从最后 ACK 续传。
- duplicate：返回原 ACK，不重复应用。
- out-of-order：暂存到有界窗口；超过窗口或超时发送 `sync.gap`。
- expired 控制请求不得执行；历史快照可以没有 expiresAt。
- 退避使用指数增长和 jitter，危险控制请求只允许相同 requestId 重试。

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

## 15. 明确不承诺

- Relay 隐藏流量元数据。
- 对已配对恶意设备或被攻破开发机保密。
- 绕过 OpenAI 账号、组织或区域策略。
- 实时控制 Desktop 私有 App Server。
