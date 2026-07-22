---
source_commit: 762c37f2ba2c6f3f982c8b1ae5761fadb904b8af
generated: 2026-07-21
---


# 模块：gradle

**Gradle 构建环境与依赖版本配置中心**  
模块通过 `gradle-wrapper.properties` 锁定 Gradle 9.4.1 版本，确保团队统一构建；`libs.versions.toml` 集中声明所有模块共用的版本、库与插件，提供单一可信源；`gradle-daemon-jvm.properties` 指定守护进程工具链（JetBrains JDK 21），保障构建一致性。其他模块均通过该目录的版本目录引用依赖，无需四处硬编码。  

**修改指引**：升级 Gradle 版本编辑 `gradle-wrapper.properties`，更新依赖版本修改 `libs.versions.toml`，调整构建 JDK 修改 `gradle-daemon-jvm.properties`。


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
