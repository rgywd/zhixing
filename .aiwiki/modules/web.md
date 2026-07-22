---
source_commit: 762c37f2ba2c6f3f982c8b1ae5761fadb904b8af
generated: 2026-07-21
---


# 模块：web

提供 Web 管理界面与 HTTP 服务，集成前端静态资源并启动 Ktor 嵌入式服务器。

模块通过 build.gradle 构建前端产物并复制到资源目录；Entry.kt 使用 CIO 引擎启动 Ktor 服务，挂载压缩、CORS、SSE 等插件，并将 `/` 路由映射到 `static` 目录实现 SPA 托管。对外暴露 HTTP 端点，依赖 Android 网络权限。核心数据流为客户端请求经 Ktor 分发至静态资源或 SSE 推送。

修改指引：服务端逻辑调整从 `Entry.kt` 入手；前端变更需先构建 web-ui 并重新编译模块。


## 文件摘要

### `web/build.gradle.kts`

Android 库模块，构建 web-ui 静态资源并集成 Ktor 服务。
- `buildWebUi`：构建前端并复制到资源目录
- `android`：配置 SDK 版本、兼容性
- `dependencies`：声明 Ktor 服务及 Android 库依赖

### `web/consumer-rules.pro`

```
- 空 ProGuard 消费者规则文件，无自定义保护条目。
```

### `web/proguard-rules.pro`

Android ProGuard 规则配置文件，当前仅含注释及示例未启用规则。  
- 无活跃规则：所有配置均为注释或示例说明。

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
