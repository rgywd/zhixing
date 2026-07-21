---
source_commit: 4e68436185cb505db643c54224ab82d2f2745844
generated: 2026-07-21
---


# 模块：gradle

**gradle** — 集中管理 Gradle 构建环境、依赖版本与 Wrapper，确保构建一致性。

模块通过 `gradle-wrapper.properties` 锁定 Gradle 9.4.1 版本及下载源；`gradle-daemon-jvm.properties` 为守护进程指定 JetBrains 提供的 JDK 21 工具链；`libs.versions.toml` 作为版本目录，统一声明所有依赖库与插件的版本坐标。核心数据流：Wrapper 下载对应 Gradle 启动构建，构建过程使用指定的 JDK 21，各子模块通过引用 `libs.versions.toml` 获取依赖版本。无外部模块依赖，以配置形式向上层物资提供版本与工具链信息。

**修改指引**：典型改动从 `libs.versions.toml` 的 `[versions]` 表开始，升级 Gradle 则修改 `gradle-wrapper.properties` 中的 `distributionUrl`，更换 JDK 调整 `gradle-daemon-jvm.properties` 的 `toolchainVersion` 与下载地址。

## 文件摘要

### `gradle/gradle-daemon-jvm.properties`

定义Gradle守护进程的JVM工具链下载源、供应商与版本。
- `toolchainUrl.*`：各平台JDK下载地址
- `toolchainVendor`：JETBRAINS
- `toolchainVersion`：21

### `gradle/libs.versions.toml`

## gradle依赖版本目录，集中管理项目版本、库与插件
- `[versions]`：定义版本号（如 `agp`、`kotlin`、`composeBom`）
- `[libraries]`：声明库依赖坐标（如 `androidx-core-ktx`、`retrofit`、`koin-bom`）
- `[plugins]`：声明Gradle插件（如 `android-application`、`ksp`、`google-services`）

### `gradle/wrapper/gradle-wrapper.properties`

Gradle Wrapper 配置，指定 Gradle 9.4.1 及解压位置。
- `distributionUrl`：下载地址（gradle-9.4.1-bin.zip）
- `distributionBase`/`distributionPath`：解压至 `GRADLE_USER_HOME/wrapper/dists`
- `networkTimeout`：10000ms
- `validateDistributionUrl`：true
- `zipStoreBase`/`zipStorePath`：ZIP 存储路径
