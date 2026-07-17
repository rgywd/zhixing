# 知行 Android 基座实施计划

状态：执行中（2026-07-16）

## Phase 0：可构建的知行基线

目标：把上游工程变成不依赖第三方私有服务、可以独立构建的“知行”开发包。

- [x] 工程名、application ID、版本与应用显示名切换为知行。
- [x] 移除 Google Services、Firebase Analytics、Crashlytics 和 Remote Config 依赖，改为本地默认值与 no-op telemetry。
- [x] 替换启动图标和 About、分享、更新、社区等上游品牌入口；保留许可证和必要署名。
- [x] 建立知行语义主题默认值，继续支持动态色、明暗模式和 AMOLED。
- [x] 增加品牌与隐私边界的单元检查。
- [x] 通过定向 JVM 单测、完整 App 编译和 debug APK 构建。

## Phase 0.5：数据安全与可恢复升级

目标：在继续扩展知识库和工作区前，建立从 `v0.1.0` 起可验证的数据保留、备份和恢复底座。完整契约见 [`DATA_SAFETY_AND_BACKUP.md`](./DATA_SAFETY_AND_BACKUP.md)。

- [ ] 统一数据库名称为 `zhixing`，修复 WebDAV/S3 仍读写 `rikka_hub` 的错误并兼容旧备份。
- [ ] 将 Workspace 用户文件纳入备份，排除 RootFS、临时目录和可重建索引。
- [ ] 增加升级前本地快照、版本化 manifest、SHA-256 校验和恢复模式。
- [ ] 使用 Android Keystore 保护 WebDAV/S3 凭据，远端备份增加可跨设备恢复的带认证加密。
- [ ] 使用 WorkManager 实现真正的定时备份、重试、约束、保留策略和失败通知。
- [ ] 增加 `v0.1.0 -> 候选版本` 覆盖升级测试，以及备份/恢复 round-trip 测试。

## Phase 1：核心会话闭环

目标：验证基座原生会话逻辑在知行品牌下完整可用。

- [ ] 首次启动引导用户配置 Provider 和默认模型。
- [ ] 验证新建、流式生成、停止、重试、编辑和消息分支。
- [ ] 验证会话抽屉的搜索、置顶、分组、删除与大屏永久抽屉。
- [ ] 验证文本、图片和文件输入；错误时保留部分生成结果。
- [ ] 对生成生命周期和 Conversation/MessageNode 分支规则补契约测试。

## Phase 2：助手、工具与工作区

目标：把“个人 AI 工作台”能力收口为一条产品路径。

- [ ] 精简助手配置的信息架构和默认模板。
- [ ] 整理本地工具、MCP、搜索、技能和授权提示。
- [ ] 把旧知行“笔记”需求映射到工作区、记忆或工具输出，先完成真实任务闭环再决定独立页面。
- [ ] 审计文件访问、命令执行、屏幕信息和网络工具的权限边界。

### Phase 2.1：项目知识空间 v0.1

完整契约见 [`KNOWLEDGE_SPACE.md`](./KNOWLEDGE_SPACE.md)。

- [x] Workspace 幂等初始化标准项目目录和 `PROJECT.md`，不覆盖已有用户内容。
- [x] 导入 PDF、DOCX、PPTX、EPUB 与常见文本资料，保留原文并生成可重建的归一 Markdown。
- [x] 实现无需 RootFS 的 `knowledge_status`、`knowledge_search`、`knowledge_read` 和需批准的 `knowledge_ingest`。
- [x] 在 Workspace 详情页提供知识空间状态、初始化和知识导入入口。
- [x] 为初始化、中文检索、来源引用、路径边界和工具注册补测试。

### Phase 2.2：原生远程工作流

完整契约见 [`NATIVE_WORKFLOW.md`](./NATIVE_WORKFLOW.md)。

- [x] 实现 Happy 恢复密钥、Bearer token、Keystore 和加密兼容层。
- [x] 原生展示机器、项目、会话、历史消息与实时状态，不加载 Happy Web UI。
- [x] 工作首页按开发机与目录聚合完整 Codex 历史；Happy 账户和机器诊断迁入设置。
- [x] 支持手机指定项目目录启动 Codex 任务，以及恢复已结束的历史对话。
- [x] 支持补充消息、中断任务和审批 RPC，并区分离线、超时、冲突与解密错误。
- [x] 使用当前真实 Happy/Codex 账户完成读写闭环与断线恢复验证。
- [x] 工作流默认入口不再加载 Happy WebView；App 支持安全切换中继 origin，token 与中继绑定。
- [x] 自托管 Happy Relay 的固定上游版本、Docker Compose、可选 Caddy、备份/升级/回滚手册已落库。
- [ ] 在目标 VPS 完成 HTTPS 部署，并做手机 + zhixing-agent + Codex 的真实全链路验收。

## Phase 3：可选同步服务

目标：只在跨设备需求被验证后引入最小服务端。

- [ ] 先写对象版本、幂等、删除和冲突契约。
- [ ] 实现可关闭的 SyncGateway，不代理用户模型请求。
- [ ] 提供加密备份、导入导出和失败恢复。
- [ ] 旧 Ktor 服务只作为需求/测试素材，不直接迁移数据库或 REST 接口。

## 每阶段验证门

1. 先写失败测试或可复现验收脚本。
2. 实现最小闭环，不做无关清理。
3. 运行相关单测、静态检查与 Android 构建。
4. 在小手机、横屏和大屏至少各验证一次；同时检查明暗模式、最大字体和减少动态效果。
5. 更新本计划与实现文档，确认工作树只含本阶段变更后再提交。

## 当前基线与保护规则

- 上游基线与归属见 `THIRD_PARTY_NOTICES.md`。
- 发布分支：`main`。
- 工作目录：`C:/Users/rgywd/Documents/zhixing/zhixing-rikkahub`。
- 旧项目：`C:/Users/rgywd/Documents/zhixing/zhixing-assistant`，保持现状，不 stash、不 reset、不覆盖未提交文件。

## 2026-07-16 验证记录

- `AppIdentityTest` 与 `UpdateCheckerTest`：通过，确认知行身份、关闭第三方 telemetry，并使用自有 GitHub Release 更新源。
- GitHub Release 流水线：已接入签名 APK、SHA-256 与 `latest.json` 更新清单生成。
- `:app:assembleDebug`：通过；生成 arm64、x86_64 和 universal 三种 APK。
- Universal APK：`dev.sundby.zhixing.debug`，`versionCode=1`，`versionName=0.1.0`；中文应用标签为“知行”。
- 完整 `:app:testDebugUnitTest`：130 条中 121 条通过、9 条失败。失败集中在继承基线的 `ShareSheetTest` 1 条、`TimeReminderTransformerTest` 7 条、`ChatServiceTest` 1 条；本阶段未修改对应业务逻辑，后续在 Phase 1 先建立干净基线再处理。
- 当前机器没有连接 Android 设备或已启动模拟器，因此尚未完成真机视觉与交互验收。
- 知识空间核心测试：`KnowledgeSpaceManagerTest` 4 条通过，覆盖幂等初始化、中文检索、来源/行号引用、同名防覆盖与读取边界。
- 知识空间 App 测试：`KnowledgeToolsTest`、`WorkspaceReminderTransformerTest`、`KnowledgeSpaceServiceTest` 共 6 条通过，覆盖审批默认值、无 RootFS 可用性和本地文本归一。
- `:app:assembleDebug`：通过；知识空间 UI、资源、Koin 依赖、文档解析接线和四个 AI 工具完成编译与 APK 打包。
