---
source_commit: 4e68436185cb505db643c54224ab82d2f2745844
generated: 2026-07-21
---


# 模块：ai

该模块封装多供应商 AI 对话、图像与嵌入生成，并统一消息模型和 UI 交互。
核心层（core）定义角色、推理、工具等数据类；provider 层提供 Provider 接口与 OpenAI/Claude/Google 实现，由 ProviderManager 管理；registry 通过 DSL 注册模型能力；ui 层定义消息流式合并与 UI 模型；util 提供网络、JSON、Key 轮询等基础设施。对外暴露 ProviderManager 获取实例，调用文本/图像/嵌入 API；依赖 common 模块、OkHttp 与 Kotlin 序列化。
修改指引：新增供应商在 provider/providers 下实现 Provider 并在 ProviderSetting 注册；模型能力变更更新 ModelRegistry 与 DSL；消息结构改动需同步各 provider 解析逻辑与 ui/Message。

## 文件摘要

### `ai/README.md`

提供 AI 子模块的环境配置与构建说明
- `准备环境`：安装 CMake 与 NDK，配置 ANDROID_NDK 环境变量
- `git submodule 初始化`：根目录执行 `git submodule update --init --recursive`
- `构建 libMNN.so`：进入 `src/main/cpp/mnn`，执行 `./build.sh`

### `ai/build.gradle.kts`

管理 ai 模块的 Android 库构建，启用 Compose 与序列化。
- `android.library` 插件：库模块
- `kotlin.serialization` 插件：序列化支持
- `kotlin.compose` 插件：Compose 编译器
- `namespace`：`me.rerere.ai`
- `compileSdk`：37 | `minSdk`：26
- 依赖：`common` 模块、Compose BOM、OkHttp、Kotlinx 序列化/协程/日期
- 实验性 API：`kotlin.uuid.ExperimentalUuidApi`、`kotlin.time.ExperimentalTime`

### `ai/consumer-rules.pro`

- 文件职责：定义库的 ProGuard 消费者规则（当前为空）。  
- 无关键符号。

### `ai/proguard-rules.pro`

ProGuard 规则配置文件，用于 Android 代码混淆与优化。当前仅含基础注释，无实际规则定义。

### `ai/src/androidTest/java/me/rerere/ai/ExampleInstrumentedTest.kt`

Android 仪表化测试，验证应用上下文包名。
- `ExampleInstrumentedTest`：仪表化测试类
- `useAppContext()`：测试方法，断言包名正确

### `ai/src/main/AndroidManifest.xml`

Android 清单，声明应用所需权限。
- `uses-permission INTERNET`：允许网络访问。

### `ai/src/main/java/me/rerere/ai/core/MessageRole.kt`

定义AI对话消息角色枚举，含序列化名映射。
- `MessageRole`：枚举，含 SYSTEM、USER、ASSISTANT、TOOL

### `ai/src/main/java/me/rerere/ai/core/Reasoning.kt`

定义推理级别枚举，提供令牌预算与effort映射。
- `ReasoningLevel`：推理级别枚举
- `budgetTokens`：令牌预算
- `effort`：effort参数
- `isEnabled`：是否启用
- `fromBudgetTokens`：根据预算匹配级别

### `ai/src/main/java/me/rerere/ai/core/Tool.kt`

定义 AI 工具及其输入模式的数据结构。
- `Tool`：表示 AI 工具，含名称、描述、输入模式、执行函数等
- `InputSchema`：工具输入模式密封类
- `InputSchema.Obj`：对象类型输入模式，含属性与必需字段

### `ai/src/main/java/me/rerere/ai/core/Usage.kt`

定义TokenUsage数据类及其合并扩展函数
- `TokenUsage`：可序列化数据类，记录提示、完成、缓存与总令牌数
- `merge`：扩展函数，合并可空TokenUsage与另一个TokenUsage，优先取有效值

### `ai/src/main/java/me/rerere/ai/provider/Model.kt`

定义 AI 模型配置的数据类及相关枚举。
- `Model`：AI 模型配置数据类
- `ModelType`：模型类型（CHAT/IMAGE/EMBEDDING）
- `Modality`：输入输出模态（TEXT/IMAGE）
- `ModelAbility`：模型能力（TOOL/REASONING）
- `BuiltInTools`：内置工具（Search/UrlContext/ImageGeneration）

### `ai/src/main/java/me/rerere/ai/provider/Provider.kt`

定义 AI 提供商接口及生成参数
- `Provider<T>`：无状态提供商接口，含模型列表、余额、文本生成/流式、嵌入、图片生成/编辑
- `TextGenerationParams`：文本生成参数
- `ImageGenerationParams`：图片生成参数
- `ImageEditParams`：图片编辑参数
- `EmbeddingGenerationParams`：嵌入生成参数
- `EmbeddingGenerationResult`：嵌入结果
- `CustomHeader/Body`：自定义请求头/体

### `ai/src/main/java/me/rerere/ai/provider/ProviderManager.kt`

管理AI提供商实例的注册与按名称/类型获取。
- `ProviderManager`：管理Provider注册与获取
- `registerProvider`：注册Provider实例
- `getProvider`：按名称获取Provider
- `getProviderByType`：根据设置类型获取对应Provider

### `ai/src/main/java/me/rerere/ai/provider/ProviderSetting.kt`

定义AI供应商配置的密封类与子类，含余额、模型管理等。
- `BalanceOption`：余额获取配置
- `ClaudePromptCacheTtl`：Claude缓存TTL枚举
- `ProviderSetting`：抽象供应商配置，含模型增删改移动与复制
- `ProviderSetting.OpenAI/Google/Claude`：具体供应商，含API密钥、URL等
- `ProviderSetting.Types`：所有子类列表

### `ai/src/main/java/me/rerere/ai/provider/providers/ClaudeProvider.kt`

Claude API 提供者实现，处理文本生成与流式响应。
- `ClaudeProvider`：实现 `Provider`，封装 Anthropic 消息/模型 API
- `listModels`：获取可用模型列表
- `generateText`：非流式文本生成
- `streamText`：流式文本生成，支持 SSE
- `buildMessageRequest`：构造请求体，含系统提示、思考、工具、缓存
- `parseMessage`：解析响应内容为 UI 消息部分
- `parseTokenUsage`：提取 token 用量

### `ai/src/main/java/me/rerere/ai/provider/providers/GoogleProvider.kt`

实现 Google Gemini API Provider，支持文本生成、流式及模型列表
- `GoogleProvider`：集成 Google AI 与 Vertex AI 的 Provider
- `generateText`：同步文本生成
- `streamText`：流式文本生成
- `listModels`：获取可用模型列表
- `buildCompletionRequestBody`：构建请求体
- `parseMessage`：解析响应 UIMessage

### `ai/src/main/java/me/rerere/ai/provider/providers/OpenAIProvider.kt`

OpenAI API 提供者实现，支持文本、图像、嵌入生成及余额查询。
- `OpenAIProvider`：实现 Provider 接口，封装 OpenAI 各 API 调用。
- `listModels`：获取可用模型列表。
- `streamText`：流式文本生成。
- `generateImage`：图像生成。
- `generateEmbedding`：向量嵌入生成。
- `getBalance`：查询账户余额。

### `ai/src/main/java/me/rerere/ai/provider/providers/ProviderMessageUtils.kt`

将消息 parts 按工具边界分组，确保工具调用与结果连续。
- `PartGroup`：分组结果密封类（Content与Tools子类）
- `groupPartsByToolBoundary`：将 parts 按执行工具边界分组的函数

### `ai/src/main/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPI.kt`

实现OpenAI聊天补全API，支持流式/非流式、多模态、工具调用。
- `ChatCompletionsAPI`：聊天补全接口实现
- `generateText`：非流式文本生成
- `streamText`：流式文本生成（Flow）

### `ai/src/main/java/me/rerere/ai/provider/providers/openai/OpenAIImpl.kt`

```markdown
定义OpenAI文本生成接口（非流式/流式）。
- `OpenAIImpl`：OpenAI文本生成接口
- `generateText`：非流式生成文本
- `streamText`：流式生成文本
```

### `ai/src/main/java/me/rerere/ai/provider/providers/openai/ResponseAPI.kt`

OpenAI 响应 API 实现，处理文本生成/流式及请求构建。
- `ResponseAPI`：实现 OpenAIImpl 的接口
- `generateText`/`streamText`：非流式/流式生成
- `buildRequestBody`：构建请求体
- `buildMessages`：消息转 input 数组
- `parseResponseDelta`/`parseResponseOutput`：解析 SSE/完整响应
- `parseTokenUsage`：解析 token 用量
- `ResponseProviderCapabilities`：不同 provider 能力

### `ai/src/main/java/me/rerere/ai/provider/providers/vertex/ServiceAccountTokenProvider.kt`

使用服务账号（邮箱+私钥）换取 Google OAuth2 Access Token，含缓存。
- `ServiceAccountTokenProvider`：OAuth2 令牌提供者
- `fetchAccessToken`：生成 JWT 断言并获取 access token
- `cacheKey`/`CachedToken`：令牌缓存逻辑

### `ai/src/main/java/me/rerere/ai/registry/ModelDsl.kt`

模型注册DSL，通过token匹配定义模型识别规则和能力。
- `defineModel`：构建模型定义
- `defineGroup`：构建模型组
- `tokenRegex`：创建正则TokenSpec
- `ModelDefinition`：模型定义，含匹配器与模态能力
- `ModelGroup`：组合多个选择器
- `ModelSelector`：匹配模型ID接口
- `TokenSpec`：令牌规格密封接口

### `ai/src/main/java/me/rerere/ai/registry/ModelRegistry.kt`

模型注册表，按ID匹配模型定义并解析输入/输出模态与能力。  
- `ModelData<T>`：通过模型ID获取数据的函数式接口  
- `ModelRegistry`：单例，包含所有模型定义、分组及解析逻辑  
- `MODEL_INPUT_MODALITIES` / `MODEL_OUTPUT_MODALITIES`：获取模型输入/输出模态  
- `MODEL_ABILITIES`：获取模型能力（工具/推理）  
- 大量模型常量（如`GPT4O`、`GEMINI_2_5_FLASH`）及分组（如`GEMINI_SERIES`、`CLAUDE_SERIES`）

### `ai/src/main/java/me/rerere/ai/ui/Image.kt`

定义图像生成数据模型，含可序列化数据类与枚举。  
- `ImageGenerationItem`：图像生成条目，含 base64 数据、MIME、是否部分及索引。  
- `ImageGenSize`：图像尺寸枚举，如 AUTO/SQUARE_1024 等。

### `ai/src/main/java/me/rerere/ai/ui/Message.kt`

定义消息模型、流式合并及工具迁移逻辑。
- `UIMessage`：可序列化消息，含流式追加 chunk
- `UIMessagePart`：消息部分（Text, Image, Tool 等）
- `MessageChunk`：流式响应块
- `ToolApprovalState`：工具审批状态
- `handleMessageChunk`：chunk 合并到消息列表
- `migrateToolMessages`：TOOL 消息迁移到 ASSISTANT
- `limitContext`：上下文窗口限制

### `ai/src/main/java/me/rerere/ai/ui/MessageMetadata.kt`

定义消息部件元数据的类型安全 schema 与编解码工具。
- `PartMetadata`：元数据基类接口
- `ClaudeReasoningMetadata`：Claude 推理签名
- `OpenAIReasoningMetadata`：OpenAI 推理 ID 与加密内容
- `GoogleThoughtMetadata`：Gemini 思考签名
- `DiffMetadata`：diff 视图数据
- `metadataAs<T>()`：解析为指定类型
- `toMetadata()`：编码为 JsonObject

### `ai/src/main/java/me/rerere/ai/util/ErrorParser.kt`

解析 JSON 错误响应为 HttpException。
- `HttpException`：继承 RuntimeException 的自定义异常。
- `parseErrorDetail`：JsonElement 扩展函数，递归解析常见错误字段并抛出异常。

### `ai/src/main/java/me/rerere/ai/util/FileEncoder.kt`

Android 图片/视频/音频 Base64 编码工具，含压缩、Exif 旋转与 MIME 检测。
- \`EncodedImage\`：Base64 图片数据类
- \`ExifTransformType\`：Exif 变换枚举
- \`mapExifOrientationToTransform\`：Exif 方向映射
- \`calculateImageInSampleSize\`：计算采样率
- \`UIMessagePart.Image.encodeBase64\`：图片 Base64 编码
- \`UIMessagePart.Video.encodeBase64\`：视频 Base64 编码
- \`UIMessagePart.Audio.encodeBase64\`：音频 Base64 编码

### `ai/src/main/java/me/rerere/ai/util/Json.kt`

提供全局JSON序列化配置实例
- `json`：忽略未知键、编码默认值、省略null

### `ai/src/main/java/me/rerere/ai/util/KeyRoulette.kt`

API key 轮询工具，支持默认随机和 LRU 持久化策略。
- `KeyRoulette`：策略接口，定义 `next(keys, providerId)` 返回 key
- `KeyRoulette.default()`：随机选取 key
- `KeyRoulette.lru(context)`：LRU 策略，持久化到缓存文件，按 providerId 区分实例
- `splitKey`：按空格/逗号/换行分割 key 字符串

### `ai/src/main/java/me/rerere/ai/util/Request.kt`

HTTP 请求构建及 JSON 处理扩展工具。

- `List<CustomHeader>.toHeaders()`：将自定义头列表转为 OkHttp Headers
- `Request.Builder.configureReferHeaders`：根据 URL 主机添加特定 Refer/Header
- `ResponseBody.stringSafe`：安全读取响应体（仅 RealResponseBody）
- `JsonObject.mergeCustomBody`：递归合并自定义 JSON 体（含键覆盖/递归）
- `JsonElement.removeElements`：从 JSON 中移除或仅保留指定键（递归）

### `ai/src/main/java/me/rerere/ai/util/SSE.kt`

实现 SSE 事件源，处理流式响应与事件回调。

- `SSEEventSource`：自定义 EventSource，管理连接与事件分发
- `factory`：创建 SSEEventSource 的工厂方法，自动添加 Accept 头

### `ai/src/main/java/me/rerere/ai/util/Serializer.kt`

定义 Instant 的 Kotlin 序列化器，使用 ISO 字符串互转  
- `InstantSerializer`：实现 `KSerializer<Instant>`，序列化为 ISO 字符串

### `ai/src/test/java/me/rerere/ai/ExampleUnitTest.kt`

示例本地单元测试，验证基础加法逻辑。
- `ExampleUnitTest`：测试类
- `addition_isCorrect`：验证2+2=4

### `ai/src/test/java/me/rerere/ai/ModelRegistryTest.kt`

```markdown
测试 `ModelRegistry` 的模型匹配与模态配置。
- `ModelRegistryTest`：验证模型名称匹配、输入/输出模态及能力。
- `testGPT5`/`testGemini25`/`testClaudeSeries`/`testSpecificityPriority`/`testOpenAIOModels`/`testGlm5AndMinimaxM25`/`testDeepseekV4`：各模型系列匹配及能力断言。
```

### `ai/src/test/java/me/rerere/ai/provider/providers/ClaudeProviderMessageTest.kt`

测试 ClaudeProvider 消息构建逻辑（多轮工具/推理）。  
- `ClaudeProviderMessageTest`：测试类，验证 `buildMessages` 转换 UIMessage 到 Anthropic API 格式。  
- `createExecutedTool`：构造已执行工具部件。  
- `invokeBuildMessages`：反射调用私有 `buildMessages`。

### `ai/src/test/java/me/rerere/ai/provider/providers/ClaudeProviderPromptCacheTest.kt`

测试 ClaudeProvider 构建请求时 prompt caching 的 cache_control 添加逻辑。
- `ClaudeProviderPromptCacheTest`：测试类
- `setUp()`：初始化 provider
- `buildRequest()`：反射调用 `buildMessageRequest`
- `dummyTool()`：构造假工具
- 测试方法：验证 promptCaching 开关、系统提示、工具、消息、TTL 及倒数第2条用户消息的 cache_control 行为

### `ai/src/test/java/me/rerere/ai/provider/providers/GoogleProviderMessageTest.kt`

测试 GoogleProvider 消息构建逻辑（UIMessage→Gemini API 格式）。
- `GoogleProviderMessageTest`：测试类
- `invokeBuildContents`：反射调用私有 buildContents 方法
- `createExecutedTool`：辅助构造 UIMessagePart.Tool
- `multi-round tool calls...`：验证多轮 functionCall/functionResponse 交替
- `functionCall in model...`：验证 functionCall 后跟随 user 含 functionResponse
- `reasoning parts...`：验证推理部分 thought 标志
- `parallel tool calls...`：验证并行 functionCall 在同一 model 消息
- `multi-round reasoning...`：验证推理+工具交替顺序
- `user message parts...`：验证用户消息格式
- `complex multi-round...`：复杂交错场景
- `functionResponse...`：验证 functionResponse 结构

### `ai/src/test/java/me/rerere/ai/provider/providers/ProviderMessageUtilsTest.kt`

测试 UIMessagePart 列表按工具边界分组的逻辑。
- `ProviderMessageUtilsTest`：测试类，覆盖空输入、纯文本、纯工具、交替、推理分组等场景。

### `ai/src/test/java/me/rerere/ai/provider/providers/openai/ChatCompletionsAPIMessageTest.kt`

ChatCompletionsAPI 消息构建逻辑的单元测试，覆盖多轮推理与工具调用排序。
- `ChatCompletionsAPIMessageTest`：测试类
- `invokeBuildMessages`：反射调用私有 buildMessages 方法
- `createExecutedTool` / `createMultiRoundReasoningMessages`：辅助构建工具/消息
- `multi-round reasoning and tool calls should be correctly ordered`：验证多轮推理与工具调用顺序
- `parallel tool calls should be grouped together`：测试并行工具调用合并
- `reasoning should be included/excluded`：控制历史推理内容是否包含
- `tool_call followed by tool result should maintain correct order`：工具调用与结果顺序
- `complex multi-round conversation with interleaved reasoning and tools`：复杂交错场景
- `assistant with only reasoning and empty text...`：空文本推理消息过滤逻辑

### `ai/src/test/java/me/rerere/ai/provider/providers/openai/ResponseAPIMessageTest.kt`

测试 OpenAI ResponseAPI 消息构建（工具调用、推理级别）。
- `ResponseAPIMessageTest`：验证 UIMessage 到 ResponseAPI 格式的转换
- `multi-round tool calls...`：验证多轮工具调用顺序
- `volc response api...`：验证火山引擎推理设置

### `ai/src/test/java/me/rerere/ai/ui/MessageMetadataTest.kt`

验证 metadata 序列化/反序列化及新旧格式兼容性。
- `MessageMetadataTest`：测试 UIMessagePart.Reasoning 等 metadata 的解析、容错、写入格式。

### `ai/src/test/java/me/rerere/ai/ui/MessageTest.kt`

测试 UIMessage 扩展函数（limitContext、isValidToUpload、迁移工具）的行为
- `MessageTest`：测试类
- `createTestMessages`：辅助函数生成消息列表
- `TestNode`：模拟消息节点辅助数据类

### `ai/src/test/java/me/rerere/ai/ui/ToolApprovalStateTest.kt`

测试 ToolApprovalState 各状态能否恢复工具执行。
- `ToolApprovalStateTest`：验证 Approved/Denied/Answered 可恢复执行，Auto/Pending 不可恢复。

### `ai/src/test/java/me/rerere/ai/util/FileEncoderExifTransformTest.kt`

测试 `mapExifOrientationToTransform` 与 `calculateImageInSampleSize` 的单元测试类。
- `FileEncoderExifTransformTest`：包含 Exif 方向映射和采样率计算测试
- `mapExifOrientationToTransform`：Exif 方向到变换类型的映射
- `calculateImageInSampleSize`：依据尺寸与像素预算计算采样率
- 常量：`ORIENTATION_UNDEFINED`、`ORIENTATION_NORMAL` 等定义所有 Exif 方向值

### `ai/src/test/java/me/rerere/ai/util/JsonTest.kt`

测试 `mergeCustomBody` 扩展函数的合并行为。
- `JsonTest`：测试类，涵盖空列表、简单合并、键覆盖、嵌套与深层合并、空键忽略等场景。
