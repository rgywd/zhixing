# 知行（Zhixing）

Android-first、本地优先的个人 AI 工作台。

## 能力

- 原生 Kotlin、Jetpack Compose 与 Material 3 Expressive。
- Room 保存会话、消息分支、文件夹、记忆和工作区元数据。
- DataStore 保存模型服务、助手与外观设置。
- 在设备端直连用户配置的 OpenAI-compatible、Claude、Gemini 等服务。
- 支持流式消息、多模态、消息分支、MCP、搜索、语音、本地工作区与 Web 客户端。
- 支持火山引擎方舟通用 API。
- 绑定可用 Rootfs 的助手可在用户确认后通过受控 `gh issue` 工具访问私有开发仓；GitHub Token 仍由 App 安全保存。
- 不接入第三方 Firebase、遥测、更新源或免费模型服务。

产品、架构与交付文档统一从 [知行文档导航](docs/zhixing/README.md) 进入。长期方向见
[AI 助手产品愿景](docs/zhixing/AI_ASSISTANT_VISION.md)，当前运行边界见
[运行时与数据契约](docs/zhixing/RUNTIME_CONTRACT.md)。

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

推送与 `app/build.gradle.kts` 中 `versionName` 一致的 `v*` 标签后，私有开发仓的 GitHub Actions 会构建签名 APK，
并将正式 Release、对应源码归档和应用内更新清单 `latest.json` 发布到公开分发仓
[`rgywd/zhixing-releases`](https://github.com/rgywd/zhixing-releases/releases)。私有开发仓只保留 tag 与构建记录，
不得再创建正式 Release。仓库需要配置：

- `KEY_BASE64`：Base64 编码的签名文件。
- `SIGNING_CONFIG`：包含 `storeFile`、`storePassword`、`keyAlias`、`keyPassword` 的 `local.properties` 内容。
- `RELEASES_REPO_TOKEN`：仅允许写入公开分发仓 Release 资产的细粒度令牌。

Android 的安全机制仍会要求用户在安装新版本时进行系统确认。

## 验证

```powershell
./gradlew.bat :app:testDebugUnitTest --tests me.rerere.rikkahub.AppIdentityTest --tests me.rerere.rikkahub.utils.UpdateCheckerTest
./gradlew.bat :app:assembleDebug
```

合并前必须通过 Android JVM 测试与 APK 构建。

## 许可

正式安装包与每个版本的对应源码归档发布在
[rgywd/zhixing-releases](https://github.com/rgywd/zhixing-releases/releases)。许可条款见 [LICENSE](LICENSE)，
上游版权与依赖归属集中记录在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
