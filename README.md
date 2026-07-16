# 知行（Zhixing）

Android-first、本地优先的个人 AI 工作台。当前工程直接基于 RikkaHub 重构，不延续旧 `zhixing-assistant` 的 Story Engine 视觉或远端 Ktor 聊天主链。

## 当前基线

- 原生 Kotlin + Jetpack Compose + Material 3 Expressive。
- Room 保存会话、消息分支、文件夹、记忆和工作区元数据。
- DataStore 保存模型服务、助手和外观设置。
- ProviderManager 在设备端直连用户配置的 OpenAI-compatible、Claude、Gemini 等服务。
- 支持流式消息、多模态、消息分支、MCP、搜索、语音和本地工作区。
- 默认不接入 Firebase Analytics、Crashlytics、Remote Config 或 RikkaHub 更新/免费模型服务。

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

产物位于 `app/build/outputs/apk/debug/`。Debug 包名为 `dev.sundby.zhixing.debug`。

## 验证

定向品牌/隐私边界测试：

```powershell
./gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.AppIdentityTest
```

完整上游 App 测试目前有 9 条已知基线失败，记录在 `docs/zhixing/IMPLEMENTATION_PLAN.md`；debug APK 构建已通过。

## 许可与上游

本项目基于 [RikkaHub](https://github.com/rikkahub/rikkahub) 的
`f5f398ef15f077fa4b97950a785bb8c740abe029` 开始重构。继续遵守仓库根目录 [LICENSE](LICENSE) 中的分段双重许可与 AGPL v3 义务，并保留上游版权及源码说明。

当前用途为个人、非商业开发。若未来变成商业用途、超过许可限定用户数，或不希望履行 AGPL 源码义务，必须先重新评估并取得相应商业授权。
