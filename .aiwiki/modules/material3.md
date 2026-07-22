---
source_commit: 762c37f2ba2c6f3f982c8b1ae5761fadb904b8af
generated: 2026-07-21
---


# 模块：material3

**material3** 模块封装 Material3 动态配色方案到 Compose ColorScheme 的映射。

模块作为 Android 库，通过 `DynamicSchemeExt.kt` 将 `DynamicScheme` 转换为 `light` 或 `dark` 的 `ColorScheme`，供 Compose 主题使用。构建脚本依赖 Compose BOM、Material3 库，并引入 `material-color-utilities` 源码以支持动态取色。对外仅暴露 `toColorScheme()` 扩展函数，无其他组件或界面。

**修改指引**：颜色映射逻辑集中在 `DynamicSchemeExt.kt`，调整主题色板应从此文件入手。


## 文件摘要

### `material3/build.gradle.kts`

Android 库模块构建脚本，配置 Material3 组件与 Compose 支持。
- `plugins`：`android.library`、`kotlin.compose`
- `namespace`：`me.rerere.material3`
- `compileSdk`：37，`minSdk`：26
- `compileOptions`：Java 11
- `buildFeatures`：启用 Compose
- `sourceSets`：添加 `material-color-utilities/kotlin` 源码
- `dependencies`：Compose BOM、`material3`、`junit`

### `material3/consumer-rules.pro`

```
- 空 ProGuard 消费者规则文件，无自定义保护条目。
```

### `material3/proguard-rules.pro`

Android ProGuard 规则配置文件，当前仅含注释及示例未启用规则。  
- 无活跃规则：所有配置均为注释或示例说明。

### `material3/src/androidTest/java/me/rerere/material3/ExampleInstrumentedTest.kt`

Android 仪器测试示例，验证应用包名。
- `ExampleInstrumentedTest`：仪器测试类
- `useAppContext`：测试方法，检查包名为 `me.rerere.material3.test`

### `material3/src/main/AndroidManifest.xml`

Android 应用清单文件，当前为空，未定义任何组件或权限。  
- 无关键条目。

### `material3/src/main/java/me/rerere/material3/DynamicSchemeExt.kt`

将DynamicScheme转换为Compose ColorScheme。
- `DynamicScheme.toColorScheme()`：根据isDark构建dark或light ColorScheme

### `material3/src/test/java/me/rerere/material3/ExampleUnitTest.kt`

示例本地单元测试，验证基本加法。  
- `ExampleUnitTest`：示例测试类  
- `addition_isCorrect`：测试 2+2=4
