# 知行（Zhixing）

Android-first、本地优先的个人 AI 工作台。

## 能力

- 原生 Kotlin、Jetpack Compose 与 Material 3 Expressive。
- Room 保存会话、消息分支、文件夹、记忆和工作区元数据。
- DataStore 保存模型服务、助手与外观设置。
- 在设备端直连用户配置的 OpenAI-compatible、Claude、Gemini 等服务。
- 支持流式消息、多模态、消息分支、MCP、搜索、语音、本地工作区与 Web 客户端。
- 已发布 0.2.2 通过自有 Wire v1 中继使用 Codex；后续 Work 目标态是在同一聊天页通过 Tailscale WSS
  直连 Codex App Server，以仓库作为 Work 分组，不再维护独立项目看板或消息翻译网关。
- 支持火山引擎方舟通用 API。
- 所有助手内置需求与 Bug 提交工具，经用户确认后打开预填的 Zhixing GitHub Issue 页面。
- 不接入第三方 Firebase、遥测、更新源或免费模型服务。

产品与架构决策见：

- [产品与体验设计](docs/zhixing/PRODUCT_DESIGN.md)
- [运行时与数据契约](docs/zhixing/RUNTIME_CONTRACT.md)
- [知识空间 v0.1](docs/zhixing/KNOWLEDGE_SPACE.md)
- [原生远程工作流契约](docs/zhixing/NATIVE_WORKFLOW.md)
- [Chat/Work 原生模式产品与架构合同](docs/zhixing/CODEX_NATIVE_ARCHITECTURE.md)
- [Codex App Server 直连合同](docs/zhixing/CODEX_APP_SERVER_CONTRACT.md)
- [Codex 原生会话实施计划](docs/zhixing/CODEX_NATIVE_IMPLEMENTATION_PLAN.md)
- [0.2.0 回滚手册](docs/zhixing/CODEX_020_ROLLBACK.md)
- [数据安全与定时备份](docs/zhixing/DATA_SAFETY_AND_BACKUP.md)
- [实施计划](docs/zhixing/IMPLEMENTATION_PLAN.md)

## 本地构建

要求：JDK 17+、Android SDK、Node.js 与 pnpm。

```powershell
git submodule update --init --recursive
Set-Location web-ui
pnpm install --frozen-lockfile
Set-Location ..
./gradlew.bat :app:assembleDebug
```

`local.properties` 需要提供本机 Android SDK 路径，例如：

```properties
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
```

Debug APK 位于 `app/build/outputs/apk/debug/`，包名为 `dev.sundby.zhixing.debug`。

## 发布与应用内更新

推送与 `app/build.gradle.kts` 中 `versionName` 一致的 `v*` 标签后，GitHub Actions 会构建签名 APK、创建 Release，并生成应用内更新清单 `latest.json`。仓库需要配置：

- `KEY_BASE64`：Base64 编码的签名文件。
- `SIGNING_CONFIG`：包含 `storeFile`、`storePassword`、`keyAlias`、`keyPassword` 的 `local.properties` 内容。

Android 的安全机制仍会要求用户在安装新版本时进行系统确认。

## 验证

```powershell
./gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.AppIdentityTest --tests me.rerere.rikkahub.utils.UpdateCheckerTest
./gradlew.bat :app:assembleDebug
```

截至 2026-07-19，完整 `:app:testDebugUnitTest` 为 223 条并全部通过；Agent 为 70 条、Relay 为 7 条，均全部通过。
三端仍必须分别通过测试、TypeScript 类型检查和构建后才能合并。

## 许可

许可条款见 [LICENSE](LICENSE)，上游版权与依赖归属集中记录在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
