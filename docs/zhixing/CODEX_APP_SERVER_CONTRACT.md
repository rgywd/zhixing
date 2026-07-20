# 知行 Codex App Server 直连合同

状态：Accepted implementation contract
日期：2026-07-19
跟踪：[GitHub Issue #66](https://github.com/rgywd/zhixing/issues/66)
产品合同：[`CODEX_NATIVE_ARCHITECTURE.md`](./CODEX_NATIVE_ARCHITECTURE.md)

## 1. 范围

本文定义 Android 与开发机 Codex App Server 之间的传输、认证、JSON-RPC、缓存、错误和兼容边界。
它只覆盖消息数据面。仓库候选发现和手机附件受控落盘属于薄守护辅助面，不得代理 App Server RPC。

首个兼容目标固定为：

- `codex-cli 0.144.0`；
- 该版本 `codex app-server generate-json-schema --experimental` 生成物；
- App Server WebSocket transport；
- `experimentalApi=true`，仅用于 P0 已验证且有 fixture 的方法。

App Server WebSocket 仍是 experimental/unsupported 能力。所有实现都必须允许版本锁定、只读降级和快速回滚。

## 2. 端点与认证

### 2.1 开发机本地监听

目标启动形态：

```powershell
codex app-server `
  --listen ws://127.0.0.1:4500 `
  --ws-auth capability-token `
  --ws-token-file C:\Users\<user>\.zhixing-agent\app-server-token
```

规则：

- 必须绑定 `127.0.0.1`，禁止 `0.0.0.0`；
- token 文件只允许当前 Windows 用户读取；
- token 为至少 32 bytes 的 CSPRNG 随机值；
- Codex/OpenAI 登录凭据与 transport token 完全分离；
- App Server 日志不得打印 bearer、用户消息或完整附件路径。

### 2.2 Tailnet 入口

正式客户端使用：

```text
wss://<machine>.<tailnet>.ts.net
Authorization: Bearer <transport token>
```

P0 首选 Tailscale Serve 将 tailnet HTTPS/WSS 反向代理到 `http://127.0.0.1:4500`。开发机安装后必须实测：

1. WebSocket Upgrade 成功；
2. `Authorization` 被完整转发；
3. `/readyz` 和 `/healthz` 行为与 App Server 一致；
4. 仅 tailnet 设备可达；
5. 重启后 `--bg` 配置恢复；
6. Android 测试设备经 tailnet WSS 完成初始化、附件和一轮 Turn。

物理手机 Wi-Fi、5G、休眠和 DERP 切换属于发布后设备/网络 soak，不得用模拟器结果冒充；失败时按网络诊断
处理，但不再作为纯代码合并的硬门。每个正式 APK 仍应在用户设备上至少完成一次 Wi-Fi 与移动网络烟测后，
再扩大安装范围。

若 Serve 不保留 App Server 所需的 Upgrade/header，只能在 tailnet 内增加无业务状态的 TLS 终止层。该层不能解析、
缓存或翻译 JSON-RPC，也不能暴露公网 Funnel。

### 2.3 Android 凭据

每个连接保存：

```kotlin
data class WorkConnection(
    val id: String,
    val displayName: String,
    val wssUrl: String,
    val encryptedTransportToken: String,
    val expectedCliVersion: String,
    val expectedSchemaHash: String,
)
```

- token 使用 Android Keystore 包装后持久化；
- URL 只允许 `wss://`，debug localhost 例外；
- 禁止 URL userinfo、query token 和不受信任证书跳过；
- 断开连接只清除连接凭据，不删除 Room 缓存、仓库配置和 Codex 历史。

## 3. WebSocket 与 JSON-RPC

- 每个 WebSocket text frame 正好包含一个 JSON-RPC 消息；
- App Server 在 wire 上省略 `"jsonrpc":"2.0"`；客户端发送时也省略；
- 请求含 `id/method/params`；响应含相同 `id` 与 `result` 或 `error`；
- notification 只含 `method/params`；
- server request 含 `id/method/params`，Android 必须回复 `id/result` 或 `id/error`；
- binary frame、无法解析 JSON 和重复 response ID 进入诊断，不得污染消息列表。

请求 ID 使用单连接递增 `Long`。连接重建后可重新从 1 开始；挂起请求必须在断线时以可恢复网络错误结束。

## 4. 初始化状态机

```text
DISCONNECTED
  -> CONNECTING
  -> AUTHENTICATING (HTTP Upgrade)
  -> INITIALIZING (initialize)
  -> READY (initialized)
  -> DEGRADED_READ_ONLY / RETRY_WAIT / INCOMPATIBLE
```

初始化请求至少包含：

```json
{
  "id": 1,
  "method": "initialize",
  "params": {
    "clientInfo": {
      "name": "zhixing-android",
      "title": "Zhixing Android",
      "version": "<app version>"
    },
    "capabilities": {
      "experimentalApi": true
    }
  }
}
```

收到成功响应后发送 `{"method":"initialized"}`，然后才能调用 Thread/Turn 方法。

初始化响应必须持久化：

- App Server/CLI 版本；
- capability；
- user agent 或 schema 识别信息；
- 初始化时间；
- 兼容性判定。

## 5. P0 方法

| 方法 | 用途 | 失败语义 |
|---|---|---|
| `initialize` / `initialized` | 建立客户端能力 | 失败则不能写入 |
| `thread/start` | 当前仓库新建空白 Thread | 保留本地草稿，不生成假 Thread |
| `thread/read(includeTurns=true)` | 当前 Thread 全量历史 | 保留最后完整 snapshot，按本地 read generation 退避重试 |
| `thread/resume` | 加载已有当前 Thread | 未加载/占用时明确提示接管 |
| `turn/start` | 空闲发送 | 以响应中的实际 Turn/settings 为准 |
| `turn/steer` | 运行中补充要求 | expectedTurnId 不匹配时刷新状态 |
| `turn/interrupt` | 停止当前 Turn | 幂等；已完成视为成功 |
| `model/list` | 后台兼容性刷新 | 失败不锁模型选择器 |
| `permissionProfile/list` | 后台权限刷新 | 使用缓存/客户端安全默认值 |
| `skills/list` | `/` Skill 完成项 | 失败只隐藏动态 Skill |
| `plugin/list` / `app/list` | `/` 插件/App 完成项 | capability-gate，失败只降级该类 |

历史目录、归档、删除和全量搜索不属于当前 UI P0；底层模型可以保留已验证支持，但不得先把目录页面带回来。

## 6. 输入合同

### 6.1 Thread

`thread/start` 必须带当前仓库 `cwd`，以及当前选择的 model、effort、service tier 和 permission profile 中
App Server 支持的字段。仓库路径只能来自已保存的 `WorkRepository`，不能由聊天文本任意覆盖。

### 6.2 Turn

```json
{
  "id": 20,
  "method": "turn/start",
  "params": {
    "threadId": "thr_123",
    "input": [
      {"type": "text", "text": "修复截图中的布局"},
      {"type": "localImage", "path": "C:\\...\\uploads\\opaque\\shot.png", "detail": "auto"},
      {"type": "text", "text": "<zhixing_file_attachments>{...受控本机文件路径...}</zhixing_file_attachments>", "text_elements": []},
      {"type": "skill", "name": "ui-review", "path": "C:\\...\\SKILL.md"}
    ],
    "model": "<client selected model>",
    "effort": "high",
    "serviceTier": "priority",
    "permissions": ":workspace"
  }
}
```

规则：

- 空闲发送为 `turn/start`；运行中有草稿发送为 `turn/steer`；
- 文本、图片、文件和 Skill 保持用户选择顺序；图片使用官方 `localImage`，Skill 使用官方 `skill`；
- App Server 0.144.0 没有通用 `file` 输入，`mention` 只表示 `app://` / `plugin://` 连接器引用，不得承载普通文件路径；
- 通用文件先由薄守护受控落盘，再以普通 `text` 输入携带机器可读的受控本机路径清单；该清单在原生消息投影边界隐藏并恢复为附件，不泄露到聊天 UI；
- Android URI、content URI 和用户手填 Windows 路径不得直接发给 App Server；
- 模型不支持图片时保留附件和草稿，提示用户切换，不丢内容；
- 发送成功前不清空草稿；收到 Turn 接受响应后才转为消息投影。

## 7. Server request 与审批

App Server 会通过带 `id` 的 server request 请求：

- command/file-change 审批；
- MCP/动态工具审批；
- `request_user_input` 等交互输入。

Android 必须把 requestId 与 Item ID 绑定到现有 Tool/Approval UI。允许、拒绝、取消和填写答案直接回复原始
server request ID。找不到对应 Item 时才显示独立 fallback 卡；超时或断线不能自动批准。

同一 request ID 只允许回复一次。重连后未决请求必须以 App Server 当前状态为准，不能从 Room 猜测。

## 8. Notification 与投影

至少处理：

- `turn/started`、`turn/completed`；
- Item started/completed；
- agent message delta；
- reasoning summary/text delta；
- plan update；
- command output；
- file change/patch；
- MCP、dynamic tool、collab tool 和 web search；
- `thread/settings/updated`；
- `thread/tokenUsage/updated`；
- model reroute；
- error。

所有 notification 先进入 `CodexRuntimeItemReducer`，按 threadId/turnId/itemId 幂等累计，再由
`CodexMessageProjector` 投影为 `UIMessagePart`。完整 `thread/read` 也走同一 projector。

未知 notification：

1. 保存 method、时间和受控大小的 opaque payload；
2. 若包含用户可理解错误，投影为诊断 Note；
3. 不终止连接，不清空 Thread，不打印明文到普通日志。

## 9. 客户端预设与后台 Catalog

客户端随版本内置：

```kotlin
data class BundledCodexModelPreset(
    val id: String,
    val label: String,
    val inputModalities: Set<String>,
    val efforts: List<String>,
    val defaultEffort: String,
    val supportsPriority: Boolean,
)
```

选择器数据优先级：

1. 当前连接最近成功缓存；
2. 当前 App 版本 bundled presets；
3. App Server 后台 `model/list` 新结果。

后台结果用于补充/纠正能力，不作为页面初始启用门槛。服务端确认值优先于乐观选择；不兼容时只回退该字段。

Skill、插件、App 和上下文长度允许没有 bundled 数据；刷新失败时显示“暂不可用/尚未同步”，聊天仍可发送文本。

## 10. 重连、背压与超时

### 10.1 超时

- WebSocket connect：15 秒；
- initialize：15 秒；
- 普通 RPC：30 秒；
- `thread/read`：60 秒；
- 附件上传：按大小独立计算，最大 120 秒。

### 10.2 重试

- 断线：1s、2s、4s、8s、15s、30s 上限，带 20% jitter；
- 前台恢复立即触发一次重连，但不能并发创建第二条 socket；
- App Server `-32001 Server overloaded` 使用同一退避；
- 认证失败、schema 不兼容和用户断开不自动重试。

### 10.3 恢复顺序

1. 重建 WSS；
2. initialize/initialized；
3. `thread/read` 当前 Thread；
4. 原子应用新 snapshot；
5. 继续接收 notification；
6. 更新连接状态。

断线时未确认发送不得自动重复；用户可从保留草稿明确重试。

## 11. 薄守护辅助 API

辅助 API 与 App Server 使用不同端口/路径和独立最小权限 token。P0 只允许：

- `GET /v1/status`：supervisor、Codex、App Server、Tailscale 版本与健康；
- `GET /v1/repositories`：用户允许目录的候选元信息，不递归暴露文件树；
- `POST /v1/attachments`：分片/单包受控上传；
- `DELETE /v1/attachments/{id}`：释放尚未使用附件；
- `POST /v1/app-server/restart`：显式用户操作触发重启。

传输与认证是该 API 的强制合同：

- supervisor 只能监听开发机 loopback，不得绑定 LAN、tailnet IP 或公网网卡；
- 对手机的暴露只允许经 Tailscale Serve 或等价 tailnet-only HTTPS 反向代理，禁止明文 tailnet HTTP、
  Tailscale Funnel 和任意公网入口；
- 每个请求都必须携带 supervisor 专用 bearer token，代理层必须保留 `Authorization`，端点在启动探针
  验证 TLS、目标 loopback 端口和认证失败语义后才可标记 READY；
- supervisor token 与 App Server WebSocket token 分离、可独立轮换，只保存在 Android Keystore 和
  开发机权限受限 token file 中，不写 URL、日志、诊断包或命令行；
- Android 只向当前已配对连接保存的、通过 TLS 身份校验的 supervisor origin 发送 token，用户临时输入、
  重定向或服务端返回的任意主机都不得继承 bearer；
- restart 与附件写入必须另做 endpoint allowlist、请求大小/频率限制和审计事件，审计不得包含附件正文。

附件回执：

```json
{
  "attachmentId": "opaque",
  "localPath": "C:\\...\\uploads\\opaque\\screen.png",
  "mime": "image/png",
  "size": 12345,
  "sha256": "hex",
  "expiresAt": 0
}
```

Android 只接受与上传请求 ID、大小和 SHA-256 全部匹配的回执。`localPath` 不能用于任意下载或目录浏览。

## 12. 缓存写入规则

- Wire 分块 snapshot 继续使用 revision/hash 原子替换；Direct `thread/read` 没有服务端 revision，必须用
  固定的 `(connectionId, connection generation, threadId)` 读身份：请求在途期间只缓冲同连接代际、同 Thread
  的 notification 和 server request，snapshot 解析后按到达顺序重放；失败回放也必须重算 active turn、恢复
  可见审批并立即持久化；读重试不得跨到替换后的 WebSocket；
- 不完整 snapshot、解析失败或未完成 generation 保留旧数据，不允许覆盖已经接收的新事件；
  即使断线已推进 connection generation，也要把旧连接在断线前已接收的缓冲事件写入该连接缓存；旧审批保持
  当前进程内可见但不得跨 generation 回应，等待 App Server 重发后再恢复操作；切仓库、切连接或进程恢复时
  清空缓存审批，不把失去服务端 request ID 生命周期的确认卡恢复成可操作状态；
- runtime Item 以稳定 itemId upsert；
- Thread、Turn、Item raw 只保存在 App 私有 Room，不写普通日志；
- 草稿按 `(connectionId, repositoryId, threadId?)` 保存；
- 当前 Thread 指针按 `(repositoryId, connectionId)` 隔离，并在 `thread/start` 成功后更新；
- 切换 connection 时必须先恢复该连接的缓存 Thread；若无缓存则清空内存详情，禁止把上一连接的 Thread ID
  发送给新连接；
- 所有写 RPC、附件上传和审批响应都携带本地 connection generation 租约；断线或重连会使旧租约失效，
  旧协程不得向新 WebSocket 继续写入；
- 兼容门在入口固定 connectionId、connection generation 与 gate generation；Supervisor 探针、fixture read、
  catalog refresh 任一挂起点返回后不匹配就丢弃结果，旧 gate 不得晚领新租约；model/skill/plugin/app 目录读取
  也必须携带该 connection generation，并在每次 busy retry 前复核完整 gate；
- 每个 notification 和 server request 在 WebSocket listener 入队时记录来源 connection generation；事件只允许
  更新其来源 connectionId 的 Thread/cache，旧连接事件不得污染当前连接，snapshot buffer 也不得按 threadId
  吸收另一代连接的审批；
- 单例 App Server client 是 logical connectionId 与 transport generation 的唯一身份源；repository ViewModel
  不得自行猜测 generation 所属连接，只有当前激活仓库能发起重连；排队的连接请求进入 client 互斥区后还要
  再次核对仓库激活身份，失活请求不得替换当前 socket；已经开始握手的仓库一旦失活，必须取消其 connect
  job 并关闭半开 socket，`initialize` 返回后、发布 `READY` 前还要再次核对归属，连接调用返回后 repository
  controller 也必须复核激活身份才可进入 compatibility gate；同 connectionId 的新 snapshot 建立 generation
  watermark，低于 watermark 的迟到事件不得再次追加到可见消息；
- `thread/start` 返回的空 Thread 在第一条用户消息前尚未 materialize，禁止以 `thread/read(includeTurns=true)`
  作为首条消息的前置门槛；先保留本次连接内的临时 Thread，`turn/start` 成功后再把 Thread ID 与最小
  snapshot 写入原 connectionId 的本地槽位；流式事件直接驱动聊天 UI，`turn/completed` 后再短暂重试
  `thread/read` 等待 rollout 刷盘并以权威快照校准；
- 首轮成功后的 Thread 指针必须以“本机尚未保存当前 Thread”为事实判断，不能只依赖易被重连清除的
  内存 materialization 标记；Turn 占位到达前的 Item/Delta 也必须先创建 Turn 后投影，禁止静默丢弃；
- `thread/read` 权威快照只覆盖服务端 Thread/Turn/Item；手机 URI 等本地附件映射必须按受控远端路径保留，
  以便流式阶段、最终校准和进程重启后都投影为同一原生附件；
- Chat Provider Conversation 不写入 Codex 表，Codex Thread 不写入 Provider Conversation 表。

## 13. 兼容门

连接进入可写状态前必须通过：

1. TLS 与 bearer 认证；
2. initialize 成功；
3. initialize 的 `userAgent` 必须完整匹配 Codex Desktop 结构，首段版本精确命中受审版本；OS 与客户端
   元数据允许随运行环境变化，且 `codexHome/platformFamily/platformOs` 必须完整；
4. 未配置 Supervisor 时只允许 `TEXT_ONLY`，任意未知/缺失版本一律 `READ_ONLY/INCOMPATIBLE`；
5. 配置 Supervisor 后还须验证 schema hash、P0 required method set 与附件健康，才可进入 `FULL`；
6. 已有 Thread 的 `thread/read` 能解析核心 Item；新 Thread 首次成功后补做同一 fixture；
7. 只有前述 gate 完成后才能调用 `thread/resume` 或开放任意写操作；
8. experimental method 只在 capability 开启时使用。

结果：

- `FULL`：读写、附件、审批全部可用；
- `TEXT_ONLY`：文本和 App Server 原生能力可用，薄守护附件上传单项降级；未配置可选 Supervisor 时直接进入此状态；
- `READ_ONLY`：只展示 Room 缓存和可读取 Thread，不发送；
- `INCOMPATIBLE`：显示升级 Codex/知行提示，普通 Chat 不受影响。

## 14. Contract tests

- WebSocket bearer、initialize 顺序、request/response/notification/server request；
- request ID、超时、断线清理和单 socket 约束；
- `-32001` 退避与 jitter；
- busy read/catalog retry 期间替换 WebSocket 时，旧请求不得发送到新连接；
- 同 threadId、不同 connection generation 的 notification/server request 不得进入旧 snapshot buffer；
- bundled model 在 Catalog 失败时仍可选择；
- 服务端拒绝 model/effort/tier/profile 后只回退对应字段；
- snapshot 与 event replay 得到相同 `UIMessagePart`；
- 图片/文件上传 hash、超限、路径逃逸、TTL 和回执匹配；
- 前后台切换、Wi-Fi/5G、开发机休眠与重启；
- schema 升级导致 READ_ONLY 而不是空白/崩溃；
- text-only 模型携带图片时保留草稿和附件、禁用发送并提示切换模型；
- 普通 Chat 不创建 WSS、不读取 Work 凭据且行为无回归。
