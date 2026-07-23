# 知行运行时与数据契约

状态：实施基线（2026-07-23）

## 总体架构

知行采用本地优先的 Android 单体运行时：

```text
Compose UI
  -> ViewModel / ChatService
  -> GenerationHandler + Transformer Pipeline + Tools
  -> ProviderManager -> 用户配置的模型 API

ChatService -> Room repositories
SettingsStore -> DataStore
```

旧的“App -> 知行 Ktor -> 模型供应商”不是普通聊天主链。未来服务端只能通过明确接口承担同步、备份、
发布或受控代理，不能成为本地会话读取的单点依赖。

## 唯一事实来源

| 数据 | 事实来源 |
| --- | --- |
| 会话、消息分支、文件夹、记忆、工作区元数据、待办 | Room |
| Provider、模型、助手、外观和功能开关 | DataStore |
| 附件与 Workspace 用户原文 | 应用管理的文件目录 |
| RootFS、缓存、OCR/检索索引 | 可重建派生数据 |

覆盖升级、迁移、备份与恢复遵守
[`DATA_SAFETY_AND_BACKUP.md`](./DATA_SAFETY_AND_BACKUP.md)。

## 核心不变量

- `Conversation` 持有稳定 ID、助手、标题、消息节点、组织信息和可选 Workspace。
- `MessageNode` 保存同一位置的候选消息；编辑、重试和重新生成不得破坏兄弟分支。
- `UIMessage` 保留文本、推理、图片、音频、文件、工具调用/结果等结构化 parts；流式更新按身份合并。
- `Assistant` 聚合模型、系统提示、参数、记忆、MCP、工具和 Workspace 策略；每轮生成解析出不可变快照。
- 用户明确记忆与自动画像分开；自动画像遵守
  [`AUTO_PROFILE_MAINTENANCE.md`](./AUTO_PROFILE_MAINTENANCE.md)。
- UI 订阅仓库与 job 状态，不维护第二份会话真相。

## 当前状态理解与介入

普通聊天右侧“现在”面板是个人助手的当前工作台，不是传感器、天气和待办的摘要列表。状态链路固定为：

```text
本地事实与已启用画像 -> Kotlin 介入策略 -> 快速模型解释 -> 结构与证据校验 -> UI
```

- 模型输入同时携带 UTC 采集时间、设备本地日期时间和时区；UI 主状态行显示本地分钟级时间。
- 原始身体读数、正常天气、未设截止时间的待办仍可作为“查看依据”，但不能仅换句话包装成洞察。
- 身体洞察需要明确阈值、趋势或个人基线；当前没有趋势/基线时，普通单次读数不进入洞察。
- `00:00–05:59` 为默认安静时段。无明确临期或安全相关依据时，Kotlin 强制禁止行动建议；无截止时间的
  待办永远不单独构成“现在开始工作”的理由。
- 事项洞察引用具体事项证据，不以聚合数量替代紧急度。两小时内明确截止的事项可以穿透安静时段。
- 当前助手启用记忆时，状态模型只接收 active `PROFILE` 作为偏好语境；`OBSERVATION`、归档画像和画像原始
  证据不进入状态提示，也不能被模型复述给用户。
- 快速模型只能在应用给定的 insight/recommendation 证据白名单内表达；越界洞察与建议在解析后移除。
  `recommendation = null` 是正常成功结果，不视为模型失败。

## 生成生命周期

1. 用户输入转换为结构化消息并写入当前会话。
2. ChatService 固定助手、模型、Provider 和当前消息分支快照。
3. 输入 Transformer 注入时间、提示、文档/OCR、记忆与 Workspace 上下文。
4. ProviderManager 发起流式生成，GenerationHandler 合并文本、推理和工具增量。
5. 需要授权的工具进入等待；工具结果回到同一生成循环。
6. 输出 Transformer 完成显示与持久化处理。
7. 完成、停止、失败和可恢复进度都写回仓库，已有部分结果不得丢失。

普通 Chat 的用户发起型回复在异步 Job 启动前取得独立生成租约，并立即启动专用前台服务。旧生成被取消后，
它只能释放自己的租约；同一会话的替换生成和其他并行会话继续受保护，直到最后一个生成结束。前台服务只负责
运行存活与用户可见的取消入口，`ChatService`、`ConversationSession` 和 Room 仍是状态与数据真相。
这条链路不依赖悬浮窗、忽略电池优化或常驻 Web 服务。

## 扩展边界

- Provider 统一暴露能力与生成方法，供应商特例不得泄漏到聊天 UI。
- MCP、搜索、语音和设备连接均为可选能力；失败只降级对应入口。
- Knowledge Space 复用 Workspace，原文与派生索引边界见
  [`KNOWLEDGE_SPACE.md`](./KNOWLEDGE_SPACE.md)。
- Agenda 以本地待办为事实来源，系统日历只读投影见
  [`AGENDA_AND_CALENDAR.md`](./AGENDA_AND_CALENDAR.md)。
- Codex Work 是独立的手机创建会话域，不读取桌面历史、不复用普通 Provider 生成链路；见
  [`CODEX_PHONE_LINE_ARCHITECTURE.md`](./CODEX_PHONE_LINE_ARCHITECTURE.md)。
- 若重新引入同步服务，客户端持有稳定对象 ID、版本与删除标记；协议必须版本化、幂等并有契约测试。

## 隐私与兼容

- 默认不启用第三方分析、远程配置或崩溃上传。
- API Key 不进入日志、崩溃报告或普通导出；敏感上下文遵守最小必要原则。
- `dev.sundby.zhixing`、正式签名和数据库逻辑名称 `zhixing` 是稳定升级身份。
- 从首个公开稳定包起，后续版本必须支持可验证的覆盖升级，不得要求用户卸载、清数据或静默重建数据库。
