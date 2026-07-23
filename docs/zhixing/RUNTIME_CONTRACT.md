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

## 生成生命周期

1. 用户输入转换为结构化消息并写入当前会话。
2. ChatService 固定助手、模型、Provider 和当前消息分支快照。
3. 输入 Transformer 注入时间、提示、文档/OCR、记忆与 Workspace 上下文。
4. ProviderManager 发起流式生成，GenerationHandler 合并文本、推理和工具增量。
5. 需要授权的工具进入等待；工具结果回到同一生成循环。
6. 输出 Transformer 完成显示与持久化处理。
7. 完成、停止、失败和可恢复进度都写回仓库，已有部分结果不得丢失。

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
