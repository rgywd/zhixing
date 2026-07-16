# 知行 RikkaHub 基座实施计划

状态：执行中（2026-07-16）

## Phase 0：可构建的知行基线

目标：把上游工程变成不依赖 RikkaHub 私有服务、可以独立构建的“知行”开发包。

- [x] 工程名、application ID、版本与应用显示名切换为知行。
- [x] 移除 Google Services、Firebase Analytics、Crashlytics 和 Remote Config 依赖，改为本地默认值与 no-op telemetry。
- [x] 替换启动图标和 About/分享/更新/社区等 RikkaHub 品牌入口；保留许可证和上游署名。
- [x] 建立知行语义主题默认值，继续支持动态色、明暗模式和 AMOLED。
- [x] 增加品牌与隐私边界的单元检查。
- [x] 通过定向 JVM 单测、完整 App 编译和 debug APK 构建。

## Phase 1：核心会话闭环

目标：验证 RikkaHub 原生会话逻辑在知行品牌下完整可用。

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

- 上游基线：RikkaHub `f5f398ef15f077fa4b97950a785bb8c740abe029`。
- 实施分支：`codex/zhixing-rikkahub-rebuild`。
- 工作目录：`C:/Users/rgywd/Documents/zhixing/zhixing-rikkahub`。
- 旧项目：`C:/Users/rgywd/Documents/zhixing/zhixing-assistant`，保持现状，不 stash、不 reset、不覆盖未提交文件。

## 2026-07-16 验证记录

- `AppIdentityTest`：通过，确认知行身份、关闭第三方 telemetry、无上游更新源，并保留上游来源。
- `:app:assembleDebug`：通过；生成 arm64、x86_64 和 universal 三种 APK。
- Universal APK：`dev.sundby.zhixing.debug`，`versionCode=1`，`versionName=0.1.0`；中文应用标签为“知行”。
- 完整 `:app:testDebugUnitTest`：125 条中 116 条通过、9 条失败。失败集中在上游的 `ShareSheetTest` 1 条、`TimeReminderTransformerTest` 7 条、`ChatServiceTest` 1 条；本阶段未修改对应业务逻辑，后续在 Phase 1 先建立干净基线再处理。
- 当前机器没有连接 Android 设备或已启动模拟器，因此尚未完成真机视觉与交互验收。
