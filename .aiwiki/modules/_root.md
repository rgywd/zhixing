---
source_commit: 762c37f2ba2c6f3f982c8b1ae5761fadb904b8af
generated: 2026-07-21
---


# 模块：_root

`_root` 模块是项目入口与元信息中心，负责提供整体规范、许可、构建配置和开发指引。

项目通过 README 系列阐明功能与特性，AGENTS/CLAUDE 定义 AI 协作核心概念，CONTRIBUTING 和 LICENSE 约束开发流程与使用授权。构建体系由 `settings.gradle.kts` 统一管理 11 个子模块的依赖与插件版本，`build.gradle.kts` 声明全局可用插件，`gradlew` 脚本为各平台提供一致的 Gradle 执行入口。`skills-lock.json` 锁定外部 AI 技能版本，确保辅助能力可复现

**修改指引**：新增子模块需在 `settings.gradle.kts` 中加入 `include`；调整全局插件或依赖版本应修改 `build.gradle.kts` 或 `gradle/libs.versions.toml`；更新项目核心概念或规范应同步修改 `AGENTS.md`、`CLAUDE.md` 及相关 README。


## 文件摘要

### `AGENTS.md`

为 AI 与人类贡献者提供项目协作规范、模块导航与核心概念速查，确保一致开发和 Wiki 优先。

### `CLAUDE.md`

Zhixing 项目的 AI 编程助手指南，定义架构、核心概念和开发规范。

- `Assistant`：AI 助手配置与隔离环境
- `Conversation`：持久化对话，支持消息分支
- `UIMessage`：平台无关的消息抽象
- `MessageNode`：消息分支节点容器
- `Message Transformer`：消息预处理与后处理管道
- `Long-term Profile Memory`：自动用户画像记忆系统

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

介绍知行个人AI工作台的功能、构建与发布流程。

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

配置 Gradle 构建环境的全局参数，优化内存与 Android 项目设置。

- `org.gradle.jvmargs`：设置 JVM 堆内存与编码
- `android.useAndroidX`：启用 AndroidX 库
- `kotlin.code.style`：指定 Kotlin 代码风格
- `android.nonTransitiveRClass`：启用非传递 R 类
- `org.gradle.configuration-cache`：启用配置缓存

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
