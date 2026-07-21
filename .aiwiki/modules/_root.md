---
source_commit: faa1320682d60db0cf30f821d05ed660197e00ed
generated: 2026-07-21
---

# 模块：_root

项目根模块，集中管理构建配置、多语言文档、许可与 AI 开发指南。

通过 `settings.gradle.kts` 统筹 11 个子模块，`build.gradle.kts` 声明插件版本，`gradle.properties` 统一构建参数。提供中英文 README、贡献指南与架构说明（AGENTS.md、CLAUDE.md），定义 Assistant、Conversation 等核心概念，支撑全项目导航。构建流程依赖 `gradlew` 脚本，无其他模块依赖。

修改指引：构建配置调整在 `build.gradle.kts` 和 `gradle.properties`；模块增删改 `settings.gradle.kts`；开发文档更新在 `AGENTS.md` 或 `CLAUDE.md`。

## 文件摘要

### `AGENTS.md`

仓库贡献指南，涵盖构建、Git工作流、命名规范和模块结构。
- `构建命令`：assembleDebug, test, connectedDebugAndroidTest, lint
- `Git分支`：main, release/x.y.z, feat/, fix/, chore/, exp/
- `模块`：app, ai, common, document, highlight, material3, search, speech, web, workspace
- `核心概念`：Assistant, Conversation, UIMessage, MessageNode, Transformer, Work/Codex Phone-line

### `CLAUDE.md`

项目Zhixing的AI开发指南与架构说明文档。
- 模块结构：app、ai、common、document、highlight、material3、search、speech、web、workspace
- 核心概念：Assistant、Conversation、UIMessage、MessageNode、MessageTransformer
- 技术栈：Jetpack Compose、Koin、Room、DataStore、OkHttp
- 开发规范：Material3、Lucide图标、i18n要求

### `CONTRIBUTING.md`

定义参与开发的分支规范与流程
- 稳定主干：`main` 永远可部署
- 分支格式：`release/x.y.z`, `feat/需求号-简述`, `fix/问题号-简述`, `chore/简述`, `exp/简述`
- 完整流程见 `docs/zhixing/RELEASE_FLOW.md`

### `LICENSE`

定义项目的用户分段双重许可（AGPL v3 与商业许可）及适用条件。
- AGPL v3 适用条件：非商业/个人教育研究/≤10用户
- 商业许可：商业用途/>10用户/规避开源义务，联系 re_dev@qq.com
- 保留更新权利

### `README.md`

项目说明文档，介绍知行个人AI工作台、能力、构建与发布流程。
- `能力`：原生 Kotlin、Jetpack Compose、Room、多模态、MCP、语音等
- `本地构建`：JDK 17+、Android SDK、Node.js、pnpm
- `发布`：推送标签，GitHub Actions 构建签名 APK
- `验证`：gradlew 单元测试与调试 APK 构建

### `README_ZH_CN.md`

项目中文说明，描述 Android 优先、本地优先的个人 AI 工作台功能与构建指引。
- `项目名称`：知行（Zhixing）
- `核心功能`：多模型、流式多模态、消息分支、助手、MCP、搜索、语音、本地工作区
- `构建/许可`：详见 README.md、LICENSE、THIRD_PARTY_NOTICES.md

### `README_ZH_TW.md`

繁体中文README，介绍知行项目特性与构建。
- `知行`：Android-first 本地 AI 工作台
- `特性`：多模型、多模态、分支、助手、MCP、搜索、语音、工作区
- `构建`：见 README.md
- `许可`：LICENSE

### `THIRD_PARTY_NOTICES.md`

声明项目衍生自 RikkaHub 并保留原始许可。
- 衍生自 RikkaHub (`f5f398ef...`)
- 原始许可保留在 LICENSE
- Zhixing 为独立品牌，非官方分发
- 其他第三方库遵守各自许可

### `build.gradle.kts`

Android 顶层构建文件，声明子项目可用的插件版本。  
- 插件别名：`libs.plugins.android.application`、`android.library`、`kotlin.compose`、`ksp`、`android.test`、`baselineprofile`，均 `apply false`

### `gradle.properties`

{
  "path": "gradle.properties",
  "summary": "定义项目级 Gradle 构建参数，包括 JVM 内存设置、AndroidX 启用、NonTransitiveRClass 及配置缓存，统一团队的构建环境。"
}

### `gradlew`

Gradle 包装器启动脚本，处理 JVM 参数、路径及平台差异。  
- `DEFAULT_JVM_OPTS`：默认 JVM 堆大小  
- `JAVACMD`：Java 命令路径  
- `APP_HOME`：项目根目录  
- `CLASSPATH`：指向 `gradle-wrapper.jar`  
- `warn` / `die`：输出警告/错误并退出  
- `MAX_FD`：最大文件描述符  
- `cygwin`/`msys`/`darwin`：平台适配标志

### `gradlew.bat`

Gradle Wrapper Windows启动脚本，定位Java并执行gradle-wrapper.jar。
- `DEFAULT_JVM_OPTS`：默认JVM内存配置
- `JAVA_HOME`：Java安装路径
- `APP_HOME`：脚本所在目录
- `CLASSPATH`：指向gradle-wrapper.jar
- `execute`：执行GradleWrapperMain
- `fail`：错误退出

### `package.json`

项目依赖配置文件，仅声明类型定义依赖。
- `dependencies`：`@types/ink` ^2.0.3

### `settings.gradle.kts`

Gradle 项目设置：插件仓库、依赖解析策略及模块结构。
- `pluginManagement`：指定插件仓库（含 iText），ObjectBox 版本映射。
- `plugins`：fooyjay 工具链约定。
- `dependencyResolutionManagement`：FAIL_ON_PROJECT_REPOS，google/MavenCentral/JitPack/MavenLocal。
- `rootProject.name`："zhixing"。
- `include`：app 等 11 模块（含 baselineprofile）。

### `skills-lock.json`

技能锁定文件，定义技能版本与来源。  
- `version`：锁定文件版本号  
- `skills`：技能集合  
- `material-3-expressive`：技能，来源 GitHub `albermonte/android-skills`，含哈希校验值
