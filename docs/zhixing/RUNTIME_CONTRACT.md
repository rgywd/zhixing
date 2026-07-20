# 知行运行时与数据契约

状态：实施基线（2026-07-16）

## 1. 架构选择

新知行采用本地优先单体 Android 运行时：

```text
Compose UI
  -> ViewModel / ChatService
  -> GenerationHandler + Transformer Pipeline + Tools
  -> ProviderManager
  -> 用户配置的模型 API

ChatService
  -> Room repositories（会话、消息节点、文件夹、记忆、工作区）
SettingsStore
  -> DataStore（供应商、模型、助手、外观和功能开关）
```

旧实现的 `Compose -> 知行 Ktor -> 模型供应商` 不再是主链。未来服务端如重新引入，只能通过明确接口提供同步、备份、发布或受控代理能力，不能成为本地会话读取的单点依赖。

## 2. 核心领域对象

### Conversation

- `id`: 本地稳定 UUID。
- `assistantId`: 本轮使用的助手。
- `title`: 可由用户或模型生成。
- `messageNodes`: 按时间排列的消息节点。
- `folderId`, `isPinned`, `createAt`, `updateAt`: 列表和组织信息。
- `workspaceCwd`: 可选工作区上下文。

### MessageNode

一个对话位置可以保存多个候选 `UIMessage`，`selectIndex` 指向当前分支。编辑、重试和重新生成追加候选，不破坏兄弟分支。

### UIMessage

消息由结构化 parts 组成，而不是只有 `role + content` 字符串。文本、推理、图片、音频、视频、文件、工具调用和工具结果必须保持类型信息，流式更新按 message/part 身份合并。

### Assistant

助手聚合默认模型、系统提示、请求参数、记忆、MCP、本地工具、技能、提示注入和工作区策略。会话引用助手 ID，但生成时必须解析出一次明确的运行快照，避免设置在请求中途变化。

## 3. 生成生命周期

1. UI 把用户输入转换为结构化 `UIMessage` 并立即写入当前会话状态。
2. ChatService 获取助手、模型、供应商和当前分支快照。
3. 输入 transformer 依次注入时间、提示词、占位符、文档/OCR 和工作区上下文。
4. ProviderManager 选择具体 Provider 发起流式请求。
5. GenerationHandler 合并文本、推理和工具增量；需要授权的工具进入等待状态。
6. 工具结果回到同一生成循环；输出 transformer 处理 think 标签、本地文件和正则规则。
7. 每个可恢复进度写入会话仓库；完成、停止或失败都保留已有部分结果。
8. UI 仅订阅会话、job、处理状态和错误流，不自行拼接第二份会话真相。

## 4. 持久化边界

- Room 是会话、消息节点、文件夹、记忆和工作区元数据的事实来源。
- DataStore 是配置的事实来源，不存大段会话正文。
- 附件复制到应用管理的文件目录，数据库只保存稳定 URI/元数据。
- Workspace 的 `files` 目录保存用户文件与未来知识库原文；RootFS、缓存和派生索引不是事实来源。
- 项目知识空间复用 Workspace，目录、导入、检索、来源引用与工具边界遵守 [`KNOWLEDGE_SPACE.md`](./KNOWLEDGE_SPACE.md)。知识检索必须在未安装 RootFS 时仍可用。
- API key 使用 Android 安全存储策略保护；日志、崩溃报告和导出文件不得包含明文密钥。
- 删除会话时同步清理无引用的托管附件；用户外部文件不得被连带删除。
- 覆盖升级、数据迁移、定时备份和恢复必须遵守 [`DATA_SAFETY_AND_BACKUP.md`](./DATA_SAFETY_AND_BACKUP.md)。

## 5. 外部边界

### 模型 Provider

统一由 Provider 接口暴露模型列表、能力与生成方法。OpenAI-compatible、Claude、Google 等实现不得把供应商特例泄漏到聊天 UI。

### MCP、搜索与语音

均为可选能力。不可用时只禁用对应入口并提供可恢复错误，不能阻断本地会话浏览与设置访问。

### Codex 远程运行时

Codex Thread 不进入本地 Provider `ChatService` 生成链，也不复制成普通 `Conversation`。Codex App Server
是 Thread/Turn/Item 的远端事实来源；Room 只保存离线缓存、当前 Thread 指针、草稿和本地整理偏好。
Android 通过 `CodexRuntimeItemReducer` 与 `CodexMessageProjector` 生成临时
`UIMessage/UIMessagePart` 视图，并复用相同 ChatTimeline、ChatComposer、Markdown、思考和 Tool UI。
历史 snapshot 与实时 event 必须经过同一 projector。0.2.0–0.2.2 已发布的 Agent/Wire/Relay 链路仅作为
迁移与回滚来源，不再定义目标运行时事实边界。完整合同见
[`CODEX_NATIVE_ARCHITECTURE.md`](./CODEX_NATIVE_ARCHITECTURE.md)。

### 可选知行服务端（后续）

如需要服务端，首选新增独立 `SyncGateway` 边界：

- 客户端生成并持有稳定对象 ID、版本和删除标记。
- 同步请求幂等，冲突可观测且不静默覆盖。
- 服务端不可要求上传 API key；模型代理必须由用户单独启用。
- 任何协议都先版本化并写契约测试，再接 UI。

旧 `/v1/chat/stream`、session、provider、notebook API 不作为新客户端兼容目标。

## 6. 隐私与可观测性

- 移除对上游 Firebase 项目的构建和运行依赖。
- 默认不启用第三方分析、远程配置或崩溃上传。
- 本地日志使用类别和错误 ID，敏感请求体仅在用户主动导出的诊断包中脱敏出现。
- “关于”页保留上游来源、AGPL/商业许可说明和本项目源码入口。

## 7. 兼容政策

旧 `zhixing-assistant` 与其他历史应用不提供就地升级保证。知行 Android `v0.1.0` 已作为首个公开稳定包发布；从该版本起冻结 `dev.sundby.zhixing`、发布签名和数据库逻辑名称，所有后续版本都必须提供可验证的覆盖升级路径，不得要求稳定版用户卸载、清空数据或静默重建数据库。
