---
source_commit: 4e68436185cb505db643c54224ab82d2f2745844
generated: 2026-07-21
---


# 模块：common

公共基础库，提供缓存、网络请求、日志、上下文工具等通用能力。

模块按功能分包：cache 子包提供多级缓存存储（单文件/每键文件）与 LRU 驱逐策略，http 子包封装 OkHttp 请求、SSE 流、JSON 解析与表达式，js 子包为 QuickJS 注入同步 fetch，android 子包提供 Context 目录工具与内存日志。核心数据流以 CacheStore 接口统一持久化，通过 LruCache 管理内存缓存；网络请求经 Call.await 挂起或 sseFlow 流式处理。对外暴露 CacheStore 实现、LruCache、HTTP 工具、SSE 事件流、日志记录等。依赖 OkHttp、kotlinx.serialization、QuickJS 等库。

修改指引：新增缓存存储实现需扩展 CacheStore 接口，新增 HTTP 工具应在 http 包下添加扩展函数。

## 文件摘要

### `common/build.gradle.kts`

Android 公共库模块构建脚本，配置 SDK 37、Kotlin 编译选项及依赖。

- `plugins`：android.library、kotlin.serialization
- `android`：namespace `me.rerere.common`，compileSdk 37，minSdk 26，Java 11
- `KotlinCompile` opt-in：`ExperimentalUuidApi`、`ExperimentalTime`
- `dependencies`：OkHttp、Kotlinx（序列化、协程、DateTime）、FloatingX、QuickJS、Apache Commons Text

### `common/consumer-rules.pro`

空文件，未定义任何混淆规则。

### `common/proguard-rules.pro`

- 文件职责：Android ProGuard 混淆规则模板，当前无自定义规则。
- 关键条目：无实际规则，所有行均为注释与示例说明。

### `common/src/androidTest/java/me/rerere/common/ExampleInstrumentedTest.kt`

Android 设备上的示例仪器化测试。
- `ExampleInstrumentedTest`：AndroidJUnit4 测试类
- `useAppContext`：验证应用上下文包名

### `common/src/main/AndroidManifest.xml`

Android 清单文件，目前为空。

### `common/src/main/java/me/rerere/common/android/ContextUtil.kt`

为Context提供临时文件夹和缓存目录的扩展属性与方法。
- `Context.appTempFolder`：获取应用临时文件夹，自动创建
- `Context.getCacheDirectory`：按命名空间获取缓存目录，自动创建

### `common/src/main/java/me/rerere/common/android/Logging.kt`

定义日志条目模型与内存日志管理器。
- `LogEntry`：日志条目密封类
- `LogEntry.TextLog`：文本日志数据类
- `LogEntry.RequestLog`：网络请求日志数据类
- `Logging`：单例，管理日志并支持开关请求日志
- `MAX_RECENT_LOGS`：最大缓存数量(100)

### `common/src/main/java/me/rerere/common/cache/CacheEntry.kt`

定义带过期时间的缓存条目及自定义序列化器。
- `CacheEntry`：泛型缓存条目，持有值及可选过期时间戳
- `isExpired`：判断条目是否过期
- `cacheEntrySerializer`：生成 KV 序列化器，支持 value 和 expiresAt 字段

### `common/src/main/java/me/rerere/common/cache/CacheStore.kt`

封装键值缓存条目的持久化操作接口。
- `CacheStore<K, V>`：定义缓存存储的增删查清方法。
- `loadEntry`：加载单个缓存条目。
- `saveEntry`：保存缓存条目。
- `remove`：删除指定键的条目。
- `clear`：清空所有缓存。
- `loadAllEntries`：加载全部缓存条目。
- `keys`：获取所有键集合。

### `common/src/main/java/me/rerere/common/cache/FileIO.kt`

提供文件 I/O 工具函数，确保目录存在和原子写入。  
- `ensureParentDir`：创建父目录，失败抛异常。  
- `atomicWrite`：通过临时文件安全写入内容。

### `common/src/main/java/me/rerere/common/cache/KeyCodec.kt`

定义键与文件名的编解码接口，实现Base64+JSON序列化方案。

- `KeyCodec<K>`：键与文件名的双向转换接口
- `Base64JsonKeyCodec<K>`：使用JSON序列化+Base64URL编码实现

### `common/src/main/java/me/rerere/common/cache/LruCache.kt`

实现 LRU 缓存，支持持久化存储、过期与容量驱逐。
- `LruCache`：线程安全 LRU 缓存类，可配置容量、持久化存储、过期时间
- `get`/`put`/`remove`/`clear`：缓存读写操作
- `containsKey`/`size`/`keysInMemory`：查询方法
- `now()`：获取当前时间戳

### `common/src/main/java/me/rerere/common/cache/PerKeyFileCacheStore.kt`

基于文件的每键JSON缓存存储实现。
- `PerKeyFileCacheStore`：每键独立文件缓存存储类
- `loadEntry`：加载单个缓存条目
- `saveEntry`：保存缓存条目
- `remove`：删除指定键
- `clear`：清空所有缓存
- `loadAllEntries`：加载所有条目
- `keys`：获取所有键集合

### `common/src/main/java/me/rerere/common/cache/SingleFileCacheStore.kt`

基于单文件JSON的线程安全缓存存储实现。
- `SingleFileCacheStore`：实现CacheStore接口，单文件持久化缓存
- `loadEntry`：读取单个缓存条目
- `saveEntry`：保存单个缓存条目
- `remove`：移除指定键的条目
- `clear`：清空缓存文件
- `loadAllEntries`：加载全部条目
- `keys`：获取所有键集合
- `safeReadMap`：安全读取整个映射，含兼容旧格式逻辑
- `safeWriteMap`：安全写入整个映射，原子写入文件

### `common/src/main/java/me/rerere/common/http/AcceptLang.kt`

构建 Accept-Language 头，支持语言优先级、展开、去重和 q 值。
- `AcceptLanguageBuilder`：构建 Accept-Language 头字符串的类
- `Options`：配置参数（最大语言数、q步长、最小q等）
- `fromJvmSystem`：从 JVM 系统语言创建
- `fromAndroid`：从 Android 系统语言创建
- `withLocales`：自定义语言列表创建
- `build`：生成头字符串

### `common/src/main/java/me/rerere/common/http/Json.kt`

为 kotlinx.serialization 提供安全类型转换与键路径访问扩展。
- `jsonObjectOrNull`：安全转为 JsonObject
- `jsonArrayOrNull`：安全转为 JsonArray
- `jsonPrimitiveOrNull`：安全转为 JsonPrimitive
- `getByKey`：通过点分隔键路径获取字符串值

### `common/src/main/java/me/rerere/common/http/JsonExpression.kt`

定义并解析/评估 JSON 路径表达式的小型表达式引擎。

- `ParseResult`：解析结果数据类
- `parseExpression`：解析表达式字符串为 AST
- `isJsonExprValid`：验证表达式语法是否有效
- `evaluateJsonExpr`：在 JSON 对象上执行表达式返回字符串
- `Expr`：表达式 AST 接口
- `Value`：求值结果（字符串或数字）

### `common/src/main/java/me/rerere/common/http/Request.kt`

为OkHttp的`Call`提供挂起等待响应的扩展函数  
- `Call.await()`：将异步请求转为挂起函数，返回`Response`

### `common/src/main/java/me/rerere/common/http/SSE.kt`

将 OkHttp SSE 事件封装为 Kotlin Flow
- `SseEvent`：SSE 事件密封类（Open/Event/Closed/Failure）
- `sseFlow`：OkHttpClient 扩展，转换 SSE 事件为 Flow

### `common/src/main/java/me/rerere/common/js/QuickJSFetch.kt`

为 QuickJS 注入同步 fetch polyfill，通过 OkHttp 执行 HTTP 请求。
- `QuickJSContext.injectFetch`：绑定 `__httpRequest` 并执行 polyfill 脚本
- `FETCH_POLYFILL`：JS 端 fetch 实现，返回同步 Response 对象
- `HttpResponseDto`：内部数据类，序列化 HTTP 响应

### `common/src/test/java/me/rerere/common/ExampleUnitTest.kt`

示例本地单元测试，验证基本断言功能。
- `ExampleUnitTest`：示例测试类
- `addition_isCorrect()`：测试加法，断言2+2=4
