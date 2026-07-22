---
source_commit: 39425b822b38bc32c68b7d14bd68fddc5ba17824
generated: 2026-07-22
---


# 模块：app

Android 应用入口模块，管理构建配置、签名、混淆与启动性能优化。

模块通过 `build.gradle.kts` 集中声明所有依赖、构建类型、签名与 ABI 拆分，`src` 子模块提供清单与组件声明，`baselineprofile` 子模块生成基线配置以加速冷启动。`compose_compiler_config.conf` 标记稳定类来减少重组，`proguard-rules.pro` 保留序列化/JWT 类并关闭混淆。本模块作为最终产物，依赖所有其他模块及外部库，向外输出 APK/AAB。

修改指引：新增依赖或调整构建在 `build.gradle.kts`；修改混淆保留规则在 `proguard-rules.pro`；添加 Compose 稳定性声明在 `compose_compiler_config.conf`；优化启动性能在 `baselineprofile` 子模块；调整入口权限在 `src` 子模块。


## 子模块

| 页面 | 文件数 | 职责 |
|---|---|---|
| [`baselineprofile`](app/baselineprofile.md) | 4 | 生成应用启动和关键路径的基线配置文件，优化冷启动性能。 |
| [`src`](app/src.md) | 0 | 定义应用清单、声明组件与权限，作为应用入口的配置模块。 |

## 文件摘要

### `app/build.gradle.kts`

配置 Android 应用模块的构建参数、签名、依赖与编译特性。

- `android` 块：设定 SDK、构建类型、NDK 等核心参数
- `dependencies` 块：声明所有模块与第三方库依赖
- `signingConfigs` 块：从 `local.properties` 读取发布签名信息
- `buildTypes` 块：定义 release/debug 构建差异与混淆规则
- `splits` 块：为非 AppBundle 任务按 ABI 拆分 APK
- `ksp` 块：设置 Room 的 schema 导出路径
- `composeCompiler` 块：添加自定义稳定性配置文件
- `tasks.register("buildAll")`：同时执行 assembleRelease 和 bundleRelease

> 符号导航：[files/app/build.gradle.kts.md](../files/app/build.gradle.kts.md)

### `app/compose_compiler_config.conf`

Compose 编译器稳定性配置文件，声明稳定类。
- `kotlinx.collections.immutable.*`
- `kotlin.uuid.*`
- `kotlin.time.*`
- `java.time.*`
- `me.rerere.rikkahub.data.model.Conversation`
- `me.rerere.rikkahub.data.model.MessageNode`
- `me.rerere.ai.ui.UIMessage`
- `me.rerere.ai.ui.UIMessageChoice`
- `me.rerere.ai.ui.MessageChunk`
- `me.rerere.ai.ui.UIMessageAnnotation`
- `me.rerere.ai.ui.ToolApprovalState`
- `me.rerere.ai.core.TokenUsage`

### `app/proguard-rules.pro`

```markdown
Android ProGuard 规则：保留序列化/JWT 类，关闭混淆。
- `-keepattributes SourceFile,LineNumberTable`：保留行号
- `-keep @kotlinx.serialization.Serializable class *`：保留序列化类
- `-keep class org.scilab.forge.jlatexmath.**`：保留 jlatexmath
- `-dontobfuscate`：关闭混淆
- `-dontwarn`：抑制 java.lang.management/java.beans/re2j 缺失警告
- `-keepattributes Signature,InnerClasses,EnclosingMethod`：保留泛型签名
- `-keep class com.fasterxml.jackson.**` & `com.auth0.jwt.**`：保留 Jackson 和 Auth0 JWT
```
