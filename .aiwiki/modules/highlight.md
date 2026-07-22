---
source_commit: 762c37f2ba2c6f3f982c8b1ae5761fadb904b8af
generated: 2026-07-21
---


# 模块：highlight

该模块提供代码语法高亮的 Compose 组件，基于 QuickJS 执行 Prism 脚本解析代码。

- 核心为 `HighlightText` 可组合项，通过 `LocalHighlighter` 提供高亮引擎，将代码字符串转换为 `AnnotatedString` 渲染。
- `Highlighter` 封装 QuickJS 上下文，调用 Prism 生成 `HighlightToken` 列表，经 `buildHighlightText` 映射为样式。
- 对外暴露 `HighlightText`、`HighlightTextColorPalette` 及 `LocalHighlighter`，依赖 `quickjs` 和 `kotlinx-serialization-json`。

修改指引：添加语言支持需更新 Prism 脚本与颜色映射；修改解析逻辑入口在 `Highlighter`，样式调整在 `buildHighlightText` 与 `HighlightTextColorPalette`。


## 文件摘要

### `highlight/build.gradle.kts`

配置 Android 库模块 highlight 的构建脚本，集成 Compose 与序列化。
- 插件：`android-library`, `kotlin-compose`, `kotlin-serialization`
- 命名空间：`me.rerere.highlight`
- compileSdk=37, minSdk=24
- 依赖：`quickjs` (api), `kotlinx-serialization-json`, `compose-bom`, `material3`

### `highlight/consumer-rules.pro`

```
- 空 ProGuard 消费者规则文件，无自定义保护条目。
```

### `highlight/proguard-rules.pro`

Android ProGuard 规则配置文件，当前仅含注释及示例未启用规则。  
- 无活跃规则：所有配置均为注释或示例说明。

### `highlight/src/androidTest/java/me/rerere/highlight/ExampleInstrumentedTest.kt`

Android 仪器化测试示例，验证应用上下文包名。

- `ExampleInstrumentedTest`：AndroidJUnit4 测试类
- `useAppContext`：测试目标上下文包名是否为 `me.rerere.highlight.test`

### `highlight/src/main/AndroidManifest.xml`

空 Android 清单文件，暂无组件声明。

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
