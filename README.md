# 知行（Zhixing）

Android-first、本地优先的个人 AI 工作台。

## 能力

- 原生 Kotlin、Jetpack Compose 与 Material 3 Expressive。
- Room 保存会话、消息分支、文件夹、记忆和工作区元数据。
- DataStore 保存模型服务、助手与外观设置。
- 在设备端直连用户配置的 OpenAI-compatible、Claude、Gemini 等服务。
- 支持流式消息、多模态、消息分支、MCP、搜索、语音、本地工作区与 Web 客户端。
- 支持火山引擎方舟通用 API 与 Agent Plan 专属配置。
- 不接入第三方 Firebase、遥测、更新源或免费模型服务。

产品与架构决策见：

- [产品与体验设计](docs/zhixing/PRODUCT_DESIGN.md)
- [运行时与数据契约](docs/zhixing/RUNTIME_CONTRACT.md)
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
./gradlew.bat :ai:testDebugUnitTest --tests me.rerere.ai.provider.providers.VolcengineAgentPlanProviderTest
./gradlew.bat :app:assembleDebug
```

完整 `:app:testDebugUnitTest` 当前为 130 条测试中 9 条继承基线失败；发布流水线只放行已验证的产品身份、更新、Provider 配置和 Agent Plan 关键路径。

## 许可

许可条款见 [LICENSE](LICENSE)，上游版权与依赖归属集中记录在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
