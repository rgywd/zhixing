---
source_commit: 4e68436185cb505db643c54224ab82d2f2745844
generated: 2026-07-21
---


# 模块：highlight

提供基于 Compose 和 QuickJS 的代码语法高亮组件。

核心由 `Highlighter` 挂起函数通过 QuickJS 执行 Prism 脚本，将代码解析为 `HighlightToken` 序列；`buildHighlightText` 将其转为 `AnnotatedString`，最终由 `HighlightText` 可组合项渲染。颜色通过 `HighlightTextColorPalette` 配置，`LocalHighlighter` 作为上下文提供引擎。模块依赖 `quickjs` 和 `kotlinx-serialization` 进行 JSON 解析。

修改指引：更改高亮逻辑应编辑 `Highlighter` 或 JS 脚本；调整 UI 样式应从 `HighlightTextColorPalette` 或 `HighlightText` 入手。

## 文件摘要

### `highlight/build.gradle.kts`

配置 Android 库模块 highlight 的构建脚本，集成 Compose 与序列化。
- 插件：`android-library`, `kotlin-compose`, `kotlin-serialization`
- 命名空间：`me.rerere.highlight`
- compileSdk=37, minSdk=24
- 依赖：`quickjs` (api), `kotlinx-serialization-json`, `compose-bom`, `material3`

### `highlight/consumer-rules.pro`

```
ProGuard 消费者规则文件，当前为空，无保留规则。
- 无关键条目
```

### `highlight/proguard-rules.pro`

这是一个 Android ProGuard 规则模板，当前仅含注释，无实际规则。  
- 关键定义：`-keepclassmembers`（JS接口保留）、`-keepattributes`（行号保留）、`-renamesourcefileattribute`（源文件名混淆）均以注释形式存在。

### `highlight/src/androidTest/java/me/rerere/highlight/ExampleInstrumentedTest.kt`

Android 仪器化测试示例，验证应用上下文包名。

- `ExampleInstrumentedTest`：AndroidJUnit4 测试类
- `useAppContext`：测试目标上下文包名是否为 `me.rerere.highlight.test`

### `highlight/src/main/AndroidManifest.xml`

Android 清单文件（空壳，无应用定义）。  
- 无关键符号或配置条目。

### `highlight/src/main/java/me/rerere/highlight/HighlightText.kt`

提供代码语法高亮的 Compose Text 组件与颜色配置。
- `HighlightText`：可组合，高亮显示代码
- `LocalHighlighter`：CompositionLocal 提供高亮引擎
- `MAX_CODE_LENGTH`：限制最大代码长度
- `HighlightTextColorPalette`：颜色配置，含默认配色
- `buildHighlightText`：扩展函数，将 Token 转为 AnnotatedString
- `getStyleForTokenType`：私有，根据 token 类型返回样式

### `highlight/src/main/java/me/rerere/highlight/Highlighter.kt`

通过 QuickJS 执行 Prism 脚本解析代码，返回高亮令牌列表。
- `Highlighter`：初始化 JS 上下文，提供 `highlight` 挂起函数解析代码。
- `HighlightToken`：密封类，定义 `Plain`、`Token` 等令牌类型。
- `HighlightTokenSerializer`：自定义序列化器，将 JSON 令牌反序列化为 `Token`。

### `highlight/src/test/java/me/rerere/highlight/ExampleUnitTest.kt`

示例单元测试，验证基础加法断言。
- `ExampleUnitTest`：单元测试类
- `addition_isCorrect()`：测试 2+2=4
