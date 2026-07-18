# Zhixing Relay API v1

状态：0.2.0 实现合约。Relay 只盲转发 `Zhixing Wire v1` 密文，不理解 Codex 或业务 payload。

## 1. 传输与限制

- 公网只允许 HTTPS/WSS；API 前缀 `/v1`。
- JSON body 上限 3 MiB；单个 `cipherBundle` 上限约 2 MiB 明文二进制编码量。
- Bearer token、恢复密钥、正文和设备私钥不得出现在 URL、日志或数据库。
- ID 只允许 `[A-Za-z0-9_-]`，最长 128 字符；整数必须在 JavaScript safe integer 范围内。
- 所有错误统一为 `{"error":{"code":"...","message":"..."}}`。

## 2. 身份认证

### 2.1 创建 challenge

`POST /v1/auth/challenges`

```json
{"publicKey":"base64url-ed25519-public-key-32-bytes"}
```

返回一次性 32-byte challenge、challengeId 和 expiresAt。服务端对同一 IP 限制创建频率。

### 2.2 验证签名

`POST /v1/auth/verify`

Ed25519 签名消息严格为：

```text
UTF8("Zhixing Relay auth v1\n")
|| UTF8(challengeId)
|| UTF8("\n")
|| challenge[32]
|| U64BE(expiresAt)
```

请求：

```json
{
  "challengeId": "opaque",
  "publicKey": "base64url-32-bytes",
  "signature": "base64url-64-bytes"
}
```

成功返回 accountId、随机 32-byte opaque token 和 expiresAt。Relay 只保存 token 的 SHA-256；challenge
只能使用一次。后续请求使用 `Authorization: Bearer <token>`。新 token 在首次设备注册时永久绑定一个 deviceId；
sender/header 与绑定不一致会被拒绝，撤销设备也会撤销该设备的全部 token。

## 3. 设备

- `POST /v1/devices`：注册 `deviceId`、32-byte X25519 `publicKey` 和 `agent|android` 类型。
- `GET /v1/devices`：列出本账户设备和撤销状态。
- `DELETE /v1/devices/:deviceId`：撤销设备并断开它的 WebSocket；deviceId 不可复用。

设备私钥和 root secret 永不上传。设备定向请求额外携带 `X-Zhixing-Device-Id` header。
掌握 root secret 的恶意设备仍可重新证明 account 身份，因此真正的 root 泄露必须轮换 root secret；设备撤销不能替代根密钥轮换。

## 4. Envelope、outbox 和 ACK

### 4.1 提交

`POST /v1/envelopes` 接收 `ZHIXING_WIRE_V1.md` 定义的 relay-visible envelope。

- sender 必须是本账户未撤销设备。
- targetId 可为本 accountId（广播给其他未撤销设备），或本账户的一个 deviceId。
- `id` 全局幂等；完全相同的重试返回 `duplicate`，不会重复创建 delivery。
- `(accountId,senderDeviceId,streamId,seq)` 冲突返回 `SEQUENCE_COLLISION`。
- Relay 不接受 `plaintext`、`type`、`path`、`title` 等额外字段，也不解码 cipherBundle。

### 4.2 拉取

`GET /v1/outbox?limit=100` 按接收顺序返回该设备尚未 ACK 且未过期的 envelope，最多 200 条。

### 4.3 连续 ACK

`POST /v1/acks`

```json
{"senderDeviceId":"opaque","streamId":"opaque","seq":42}
```

seq 表示该 receiver 已连续应用到的最高序号。Relay 幂等保存最高值并清除相应 outbox delivery；客户端不能对仍有 gap
的流发送越级 ACK。

### 4.4 Tombstone receipt

客户端解密并应用 tombstone 后调用 `POST /v1/tombstone-receipts`，只上报 envelopeId。Relay 因而能确认撤销传播，
但仍不知道 machineId、threadId 或删除内容。

## 5. WebSocket 与 presence

连接：`GET /v1/socket` WebSocket upgrade，并通过 header 发送 Bearer token 和 `X-Zhixing-Device-Id`。

WebSocket 只发送：

- `ready`：连接建立；
- `outbox.available`：提示客户端通过 HTTP 拉密文；
- `pong`：心跳。

密文和业务事件不直接走 WebSocket，避免 WS 与持久 outbox 形成两套状态源。`GET /v1/presence` 返回在线状态和持久化
lastSeenAt；服务重启后在线状态重新计算，lastSeenAt 保留。

## 6. 运维端点

- `GET /healthz`：进程、Wire major 和数据库 schema version。
- `GET /metrics`：不带账户、设备、路径或正文标签的 Prometheus counter。
- 数据库版本高于当前二进制时拒绝启动；启动和备份均执行 SQLite integrity check。

Relay 的可见范围仍包含账户、设备、路由 ID、时间、密文大小和流量模式；端到端加密不隐藏这些元数据。
