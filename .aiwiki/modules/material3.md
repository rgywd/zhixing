---
source_commit: 4e68436185cb505db643c54224ab82d2f2745844
generated: 2026-07-21
---


# 模块：material3

**material3**  
将 Material Color Utilities 的 `DynamicScheme` 转换为 Compose `ColorScheme` 的适配模块。

模块通过 `DynamicScheme.toColorScheme()` 扩展函数，根据 `isDark` 标志生成对应的 `lightColorScheme` 或 `darkColorScheme`，桥接底层动态配色算法与 Compose Material3 组件。模块仅暴露该单一转换函数，无其他公开 API。编译依赖 Compose BOM 与 `material3`，并直接包含 `material-color-utilities` 源码。清单文件与 ProGuard 规则均为空或未启用。

**修改指引**：改动颜色映射逻辑应编辑 `DynamicSchemeExt.kt`。

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

ProGuard 消费者规则文件（material3 库），当前为空。

### `material3/proguard-rules.pro`

ProGuard规则模板，所有规则均为注释示例，未实际启用。
- 注释示例：`-keepclassmembers`、`-keepattributes`、`-renamesourcefileattribute`

### `material3/src/androidTest/java/me/rerere/material3/ExampleInstrumentedTest.kt`

Android 仪器测试示例，验证应用包名。
- `ExampleInstrumentedTest`：仪器测试类
- `useAppContext`：测试方法，检查包名为 `me.rerere.material3.test`

### `material3/src/main/AndroidManifest.xml`

Android 清单文件，当前为空，未声明任何组件或权限。

### `material3/src/main/java/me/rerere/material3/DynamicSchemeExt.kt`

将DynamicScheme转换为Compose ColorScheme。
- `DynamicScheme.toColorScheme()`：根据isDark构建dark或light ColorScheme

### `material3/src/test/java/me/rerere/material3/ExampleUnitTest.kt`

示例本地单元测试，验证基本加法。  
- `ExampleUnitTest`：示例测试类  
- `addition_isCorrect`：测试 2+2=4
