---
source_commit: 762c37f2ba2c6f3f982c8b1ae5761fadb904b8af
generated: 2026-07-21
---


# 模块：document

文档处理模块，负责文档生成、解析与存储管理，当前尚未实现功能。

模块基于 Android Library 构建，依赖 MuPDF 库，并通过 ProGuard 规则保留其相关类。核心数据流待实现，预期对外提供文档解析与渲染接口。当前仅包含空壳工程与基础依赖配置，未定义任何对外入口或对其他模块的依赖。

修改指引：从 `src/main` 开始添加 Android 组件与 MuPDF 调用逻辑。


## 子模块

| 页面 | 文件数 | 职责 |
|---|---|---|
| [`src`](document/src.md) | 0 | 该模块负责文档内容的生成、解析与存储管理，当前尚未实现任何功能。 |

## 文件摘要

### `document/build.gradle.kts`

Android 库构建配置，定义插件、SDK 与依赖。
- `plugins`：应用 `android.library` 插件
- `android`：命名空间 `me.rerere.document`，编译 SDK 37，最低 SDK 26，Java 11
- `dependencies`：`core-ktx`、`appcompat`、`material` 等基础库

### `document/consumer-rules.pro`

保留 MuPDF 库的 ProGuard 消费者规则。
- `-keep class com.artifex.mupdf.** {*;}`：保留所有 MuPDF 类防止混淆/移除。

### `document/proguard-rules.pro`

ProGuard 混淆规则文件，当前为空。
- 无自定义规则：文件仅含注释及示例，未定义任何实际混淆配置。
