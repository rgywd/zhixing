---
source_commit: 4e68436185cb505db643c54224ab82d2f2745844
generated: 2026-07-21
---


# 模块：web

提供嵌入式Ktor Web服务器，托管前端静态资源并支持SPA。  
模块通过 `build.gradle.kts` 中的 `buildWebUi` 任务构建前端资源并复制到 `static` 目录，`Entry.kt` 使用 CIO 引擎启动服务器，挂载压缩、CORS、SSE 等插件，并将根路径映射为静态文件服务。依赖 Ktor 库及 Android 网络权限声明，不对外暴露程序化接口。  
修改指引：典型改动从 `Entry.kt` 的服务器/插件配置或路由入手。

## 文件摘要

### `web/build.gradle.kts`

Android 库模块，构建 web-ui 静态资源并集成 Ktor 服务。
- `buildWebUi`：构建前端并复制到资源目录
- `android`：配置 SDK 版本、兼容性
- `dependencies`：声明 Ktor 服务及 Android 库依赖

### `web/consumer-rules.pro`

为空 ProGuard 消费者规则文件，无需输出符号。

### `web/proguard-rules.pro`

Android ProGuard混淆规则模板，存放自定义规则。
- `-keepclassmembers`：保留WebView JS接口（注释示例）
- `-keepattributes`：保留源文件/行号（注释示例）
- `-renamesourcefileattribute`：重命名源文件属性（注释示例）

### `web/src/androidTest/java/me/rerere/rikkahub/web/ExampleInstrumentedTest.kt`

Android 仪器测试，验证应用上下文包名。  
- `ExampleInstrumentedTest`：测试类，使用 AndroidJUnit4 运行器  
- `useAppContext`：测试方法，检查目标包名为 `me.rerere.rikkahub.web.test`

### `web/src/main/AndroidManifest.xml`

Android清单文件，声明应用所需权限。
- `INTERNET`：网络访问
- `ACCESS_NETWORK_STATE`：网络状态
- `NEARBY_WIFI_DEVICES`：附近WiFi设备发现

### `web/src/main/java/me/rerere/rikkahub/web/Entry.kt`

Web 服务器启动入口，配置 Ktor 服务器并挂载静态资源与插件。

- `startWebServer`：启动嵌入式 CIO 服务器，配置压缩、CORS、SSE、静态资源路由
- 插件：`Compression`、`CORS`、`SSE`、`DefaultHeaders`
- 静态资源：`/` 映射到 `static` 目录，提供 SPA 支持

### `web/src/test/java/me/rerere/rikkahub/web/ExampleUnitTest.kt`

示例单元测试类，验证本地加法运算。
- `ExampleUnitTest`：单元测试类
- `addition_isCorrect`：测试2+2是否等于4
