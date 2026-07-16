# 原生远程工作流契约

状态：首个原生闭环完成（2026-07-17）

关联需求：[GitHub Issue #10](https://github.com/rgywd/zhixing/issues/10)

## 1. 目标

知行在自身 Compose 界面中管理开发机上的 Coding Agent。手机只连接中继服务，不直接访问开发机、SSH、终端端口或 Codex app-server。

第一条接入链路复用 Happy 的开放协议和现有 `happy codex` 电脑端：

```text
知行 Compose UI
  -> Happy Kotlin Client Core
  -> HTTPS + Socket.IO + 端到端加密
  -> Happy Relay
  -> 开发机 Happy CLI
  -> Codex app-server
```

Happy Web 页面只保留为开发期故障回退，不作为正式产品界面或账户数据源。

## 2. P0 行为

1. 用户可导入已有 Happy 恢复密钥，或通过另一台已登录设备批准新设备连接。
2. 身份密钥仅以 Android Keystore 保护的密文持久化；日志、崩溃信息和诊断包不得包含恢复密钥、Bearer token 或解密后的敏感消息。
3. 工作流首页原生显示机器、项目和会话，区分离线、空闲、运行、等待审批、完成、失败和取消。
4. 会话页原生显示用户消息、Agent 文本、思考状态、工具调用和错误；Socket 断线后通过 HTTP 增量读取补齐事件。
5. 用户可发送补充要求、中断任务，并对需要人工确认的操作执行允许、拒绝或取消。
6. 所有有副作用的远程操作必须显示目标机器、项目、会话和操作摘要，不提供全局静默自动批准。

## 3. 协议边界

首个兼容目标固定为 2026-07-17 的 `slopus/happy` 主干提交
`3f161de70541b1cedaf0b7547ed70889d8dae22d`，并在代码中声明客户端协议版本。升级 Happy CLI 或 Relay
前必须先运行契约测试。

### 3.1 认证

- 恢复密钥是 32 字节 seed，可用 Happy 的 Base32 分组格式或 Base64URL 导入。
- token 获取：从 seed 生成 Ed25519 keypair，签名随机 32 字节 challenge，调用 `POST /v1/auth`。
- 设备连接：生成 Curve25519 box keypair，调用 `/v1/auth/account/request`，由已有设备批准后解密返回的账户 seed。
- HTTP 与 Socket.IO 均使用同一 Bearer token；客户端标识使用 `android/<知行版本>`。

### 3.2 同步

- HTTP 用于初始机器、会话和历史消息读取。
- Socket.IO 路径固定为 `/v1/updates`，连接类型为 `user-scoped`。
- 持久事件依赖用户级单调 `seq` 排序；断线或发现序列缺口时重新增量读取，不猜测缺失状态。
- `metadataVersion`、`agentStateVersion` 等版本字段必须参与乐观并发控制。

### 3.3 加密

- 兼容旧数据的 XSalsa20-Poly1305 secretbox。
- 当前数据使用每会话/每机器 AES-256-GCM data key。
- data key 通过 NaCl box 封装；解密失败只隔离对应对象，不清空其他会话，也不回退为明文。
- token 用于鉴权，seed/data key 用于内容加密；两者不得互相替代或写入普通偏好设置。

### 3.4 控制 RPC

- 会话 RPC 方法名为 `<sessionId>:<method>`，参数和结果均使用该会话密钥加密。
- 机器 RPC 方法名为 `<machineId>:<method>`，用于创建或恢复会话。
- 超时、目标离线、版本冲突和解密失败必须是不同错误类型，UI 不得统一显示为“连接失败”。

## 4. 分阶段交付

| 阶段 | 范围 | 验收证据 |
| --- | --- | --- |
| A 身份核心 | 恢复密钥解析、token、Keystore、协议模型 | Happy 官方测试向量 + 本地单测 |
| B 只读闭环 | 机器/会话/历史消息、Socket 增量同步 | 真实账户可看到现有 Codex 会话 |
| C 可写闭环 | 发送、中断、审批 | 手机上的操作在开发机实时生效 |
| D 产品化 | 后台恢复、通知、诊断、WebView 移除 | 真机弱网、重启、升级验证 |
| E 自托管 | CPA/VPS Happy Server、迁移与回滚 | 手机仍不直接接触开发机 |

### 4.1 当前进度

- [x] 阶段 A：恢复密钥、Ed25519 登录、Keystore 凭据存储、NaCl/AES-GCM 固定向量测试。
- [x] 阶段 B：机器与最近活跃会话的 HTTP 初始快照及 Compose 原生列表。
- [x] 阶段 B：历史消息、Socket.IO 增量、断线补偿与真实账户验收。
- [x] 阶段 C：补充消息、中断与审批 RPC 的原生读写闭环。
- [ ] 阶段 D 及以后。

### 4.2 真实链路验收记录

2026-07-17 使用当前 Happy 生产中继和本机 `happy codex` 完成以下验证，诊断输出未记录 token、
恢复密钥或会话 data key：

- Bearer token 可读取 1 台在线开发机和 1 个活跃 Codex 会话。
- 以 `sentFrom=android` 加密发送“知行原生闭环验证”消息，HTTP 增量历史收到 Codex 返回的
  “闭环已确认”。
- `user-scoped` Socket.IO 连接成功，`<sessionId>:abort` 返回成功回执。
- `permissionMode=default` 触发真实 `CodexBash` 审批请求；`permission` 的“允许一次”RPC 返回成功，
  请求从 `requests` 转入 `completedRequests`，状态为 `approved`。
- 临时审批验证文件已清理；测试进程未把 Happy 凭据写入仓库或构建日志。

当前发布闭环以恢复密钥导入为账户入口。由已有设备批准新设备的免密配对仍属于后续产品化工作，
不影响已登录设备上的原生读写闭环。

## 5. 非目标与延期项

- P0 不接入 Claude Code，不实现多租户团队权限。
- P0 不实现远程桌面、通用 SSH 终端或开发机文件系统任意浏览。
- P0 不把恢复密钥上传到知行自有服务端。
- Push 服务和 Happy Relay 自托管在原生读写闭环稳定后实施。
- CC Pocket、HAPI、Remodex 仅作为交互和 Codex app-server 映射参考，不混用身份与传输协议。

## 6. 回滚

原生页面在开发阶段可通过内部开关回退到 Happy Web 页面。正式替换前保留原有路由，但默认不加载 WebView；若协议升级导致核心功能不可用，只禁用原生远程工作流，不影响知行本地聊天、知识库和 Provider。
