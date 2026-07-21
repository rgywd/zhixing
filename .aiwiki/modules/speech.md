---
source_commit: faa1320682d60db0cf30f821d05ed660197e00ed
generated: 2026-07-21
---

# 模块：speech

语音识别与文本转语音的多平台统一接口，支持多种云端提供商。

模块按 `asr` 与 `tts` 分包：`asr` 通过 `ASRController` 接口封装录音、WebSocket/HTTP 传输与转录回调，各提供商（如 OpenAI、DashScope）实现具体协议；`tts` 由 `TtsController` 协调文本分块、`TtsManager` 分发合成、`AudioPlayer` 基于 ExoPlayer 播放。状态统一通过 `ASRState` 和 `PlaybackState` 流暴露。核心依赖 `common`、`media3`、`okhttp`、`kotlinx.serialization`。

**修改指引**：新增提供商应实现 `ASRController` 或 `TTSProvider`，并在对应的 `Setting` 密封类中注册配置。

## 文件摘要

### `speech/build.gradle.kts`

Android 库模块 speech 的构建配置，集成 Compose 与 Media3
- `plugins`：android.library、kotlin.serialization、kotlin.compose
- `namespace`：me.rerere.speech，compileSdk=37，minSdk=26
- `buildFeatures.compose`：true
- `KotlinCompile`：opts-in 多个实验性 Compose 及协程 API
- `dependencies`：common 模块、androidx.core.ktx、okhttp、kotlinx.serialization.json、kotlinx.coroutines、media3(exoplayer,ui,common)、compose.bom+material3、测试库

### `speech/consumer-rules.pro`

```
- 空 ProGuard 消费者规则文件，无自定义保护条目。
```

### `speech/proguard-rules.pro`

Android ProGuard 规则配置文件，当前仅含注释及示例未启用规则。  
- 无活跃规则：所有配置均为注释或示例说明。

### `speech/src/androidTest/java/me/rerere/tts/ExampleInstrumentedTest.kt`

Android 仪器化测试示例，验证应用上下文包名。
- `ExampleInstrumentedTest`：仪器化测试类
- `useAppContext`：测试目标应用包名是否为预期值

### `speech/src/main/AndroidManifest.xml`

空 Android 清单文件，暂无组件声明。

### `speech/src/main/java/me/rerere/asr/ASRController.kt`

定义语音识别控制器接口，管理状态流和生命周期。
- `ASRController`：语音识别控制器接口
- `state`：ASR状态流
- `start`：开始识别，提供转录回调
- `stop`：停止识别
- `dispose`：释放资源

### `speech/src/main/java/me/rerere/asr/ASRProviderSetting.kt`

定义 ASR 提供商配置基类与多平台实现。  
- `ASRProviderSetting`：密封基类，封装 id/name  
- `OpenAIRealtime`：OpenAI Realtime 配置  
- `DashScope`：DashScope 配置  
- `Volcengine`：Volcengine 配置  
- `MiMo`：小米 MiMo HTTP 分段配置  
- `Step`：阶跃星辰 Step SSE 配置  
- `Types`：所有子类列表

### `speech/src/main/java/me/rerere/asr/ASRState.kt`

定义ASR语音识别状态枚举与数据类。
- `ASRStatus`：识别状态枚举（Idle, Connecting, Listening, Stopping, Error）
- `ASRState`：状态数据类，含振幅、转录文本、错误信息
- `isRecording`：计算属性，是否正在录音

### `speech/src/main/java/me/rerere/asr/AudioAmplitude.kt`

计算音频RMS幅度并归一化，维护振幅列表。
- `calculateRmsAmplitude`：将PCM字节转为RMS，映射到0~1归一化值
- `appendAmplitude`：扩展函数，向列表追加振幅，保持最多32项
- `MAX_AMPLITUDES`：最大振幅列表长度（32）

### `speech/src/main/java/me/rerere/asr/providers/DashScopeASRController.kt`

实现 DashScope 实时语音识别，通过 WebSocket 发送音频流并接收转录结果

- `DashScopeASRController`：ASR 控制器，管理录音、状态与转录
- `start`：开始录音并连接 WebSocket
- `stop`：停止录音并关闭连接
- `dispose`：释放资源
- `websocketEndpoint`：构建含模型参数的 WebSocket URL
- `sessionUpdateEvent`：生成会话配置 JSON 事件

### `speech/src/main/java/me/rerere/asr/providers/MiMoASRController.kt`

小米 MiMo 语音识别控制器，封装录音分段、WAV 转换与 HTTP 识别。
- `MiMoASRController`：实现 ASRController，通过 HTTP 分段识别音频
- `start`/`stop`/`dispose`：控制录音生命周期
- `flushSegment`：将 PCM 转 WAV 后 POST 并拼接结果
- `pcm16ToWav`：PCM 转 WAV 工具方法
- `MAX_SEGMENT_BYTES`：6MB 触发分段阈值

### `speech/src/main/java/me/rerere/asr/providers/OpenAIRealtimeASRController.kt`

使用OpenAI Realtime API进行实时语音识别的ASR控制器。
- `OpenAIRealtimeASRController`：ASR控制器实现
- `start`：启动录音并连接WebSocket
- `stop`：停止录音与关闭WebSocket
- `dispose`：释放协程和资源
- `state`：ASR状态流
- `MAX_WEBSOCKET_QUEUE_BYTES`：WebSocket发送队列最大字节数

### `speech/src/main/java/me/rerere/asr/providers/StepASRController.kt`

阶跃星辰Step ASR语音识别控制器，HTTP SSE分段上传PCM并返回识别文本。  
- `StepASRController`：实现ASRController，管理录音与分段上传。  
- `start`：开启录音，开始识别。  
- `stop`：停止录音，提交剩余数据并完成识别。  
- `dispose`：释放所有资源。  
- `state`：识别状态流，包含录音、文本、错误。

### `speech/src/main/java/me/rerere/asr/providers/VolcengineASRController.kt`

火山引擎 ASR 实时识别控制器，基于 WebSocket 传输 PCM 音频。  
- `VolcengineASRController`：实现 ASRController，管理 WebSocket 与录音。  
- `start`：启动识别，连接 WebSocket 并录音。  
- `stop`：发送结束帧，关闭连接。  
- `dispose`：释放资源并取消协程。  
- `state`：识别状态流（Idle/Listening/Error）。

### `speech/src/main/java/me/rerere/tts/controller/AudioPlayer.kt`

封装 ExoPlayer 实现 TTS 音频播放与状态管理。
- `AudioPlayer`：音频播放器类
- `playbackState`：播放状态流
- `play(response)`：播放 TTS 响应
- `pause()`：暂停
- `resume()`：继续
- `stop()`：停止
- `seekBy(ms)`：快进/快退
- `setSpeed(speed)`：设置播放速度
- `release()`：释放资源

### `speech/src/main/java/me/rerere/tts/controller/TextChunker.kt`

将长文本按标点与段落分割为可朗读的语音块。  
- `TextChunker`：可配置最大块长的分割器  
- `split`：按段落和标点拆分为 `TtsChunk` 列表  
- `TtsChunk`：携带随机ID、索引和文本的语音块数据类

### `speech/src/main/java/me/rerere/tts/controller/TtsController.kt`

TTS 控制器：文本分片、预取合成、排队播放与状态上报
- `TtsController`：TTS 核心控制类
- `setProvider`：切换 TTS 引擎
- `speak`：朗读文本，支持 flush 重置
- `pause`/`resume`：暂停/恢复播放
- `fastForward`/`setSpeed`：快进/调速
- `skipNext`/`stop`/`dispose`：跳过、停止、释放
- `playbackState`：统一播放状态流

### `speech/src/main/java/me/rerere/tts/controller/TtsSynthesizer.kt`

将 TTS 提供者流合并为单个音频缓冲区。
- `TtsSynthesizer`：合成器类，桥接 TTS 流与单次响应
- `synthesize`：接收设置和文本块，返回合并后的音频响应
- `collectToResponse`：收集 Flow<AudioChunk> 并组装为 TTSResponse

### `speech/src/main/java/me/rerere/tts/model/PlaybackState.kt`

定义播放状态枚举与数据类，供外部使用。

- `PlaybackStatus`：播放生命周期枚举（Idle/Buffering/Playing/Paused/Ended/Error）
- `PlaybackState`：播放状态数据类，含进度、速度、块索引、错误信息

### `speech/src/main/java/me/rerere/tts/model/TTSRequest.kt`

定义TTS请求数据模型与音频格式枚举。
- `TTSRequest`：TTS请求的数据类，含`text`文本字段。
- `AudioFormat`：支持的音频格式枚举（MP3、WAV、OGG、AAC、OPUS、PCM）。

### `speech/src/main/java/me/rerere/tts/model/TTSResponse.kt`

定义TTS合成响应与流式音频块的数据模型。
- `TTSResponse`：TTS合成结果，含音频数据、格式、采样率等元数据。
- `AudioChunk`：流式音频片段，含数据、格式、是否末块标记。

### `speech/src/main/java/me/rerere/tts/provider/TTSManager.kt`

{
  "path": "speech/src/main/java/me/rerere/tts/provider/TTSManager.kt",
  "summary": "统一管理所有TTS提供商，根据配置分发语音合成请求，将TTSRequest转换为音频流Flow，并提供各提供商的引导提示词，供上层工具注入系统提示。",
  "symbols": [
    {
      "name": "generateSpeech",
      "kind": "function",
      "description": "根据提供商配置生成音频流"
    },
    {
      "name": "getPromptGuidance",
      "kind": "function",
      "description": "返回提供商的语气标记引导词"
    }
  ]
}

### `speech/src/main/java/me/rerere/tts/provider/TTSProvider.kt`

定义 TTS 提供者接口，含语音生成与可选 AI 情感标记提示。
- `TTSProvider<T>`：带泛型设置的 TTS 提供者接口
- `generateSpeech()`：生成语音流
- `promptGuidance`：AI 语气标记提示，默认空

### `speech/src/main/java/me/rerere/tts/provider/TTSProviderSetting.kt`

{
  "path": "speech/src/main/java/me/rerere/tts/provider/TTSProviderSetting.kt",
  "summary": "定义所有 TTS 提供者的配置数据模型，通过密封类封装各后端（OpenAI、Gemini、系统 TTS、火山引擎等）的配置参数，提供统一的序列化与复制能力，作为配置层核心抽象。",
  "symbols": [
    {
      "name": "TTSProviderSetting",
      "kind": "sealed class",
      "description": "所有 TTS 配置的抽象基类，定义 id/name 与复制方法"
    },
    {
      "name": "OpenAI",
      "kind": "data class",
      "description": "OpenAI TTS 的配置参数"
    },
    {
      "name": "Gemini",
      "kind": "data class",
      "description": "Gemini TTS 的配置参数"
    },
    {
      "name": "SystemTTS",
      "kind": "data class",
      "description": "系统 TTS 的配置参数"
    },
    {
      "name": "Volcengine",
      "kind": "data class",
      "description": "火山引擎 TTS 的配置参数"
    },
    {
      "name": "Step",
      "kind": "data class",
      "description": "阶跃星辰 TTS 的配置参数"
    },
    {
      "name": "ElevenLabs",
      "kind": "data class",
      "description": "ElevenLabs TTS 的配置参数"
    },
    {
      "name": "Types",
      "kind": "property",
      "description": "所有支持的 TTS 提供者类列表"
    }
  ]
}

### `speech/src/main/java/me/rerere/tts/provider/providers/ElevenLabsTTSProvider.kt`

ElevenLabs TTS 提供者，通过 HTTP API 生成 MP3 语音流。  
- `ElevenLabsTTSProvider`：实现 TTSProvider 接口  
- `generateSpeech`：构建请求并发射 AudioChunk 流  
- `TAG`：日志标签

### `speech/src/main/java/me/rerere/tts/provider/providers/FishAudioTTSProvider.kt`

Fish Audio TTS 提供者，调用 API 合成语音流。
- `FishAudioTTSProvider`：实现 TTSProvider，`generateSpeech` 发送请求并返回音频流。

### `speech/src/main/java/me/rerere/tts/provider/providers/GeminiTTSProvider.kt`

调用Gemini API实现文本转语音的TTSProvider
- `GeminiTTSProvider`：TTSProvider实现，调用Gemini API生成语音
- `generateSpeech`：构建请求、解析响应并发送音频流
- `GeminiTTSResponse` / `Candidate` / `Content` / `Part` / `InlineData`：响应数据结构
- `TAG`：日志标签

### `speech/src/main/java/me/rerere/tts/provider/providers/GroqTTSProvider.kt`

Groq TTS 提供商实现，调用 Groq API 合成语音。
- `GroqTTSProvider`：实现 TTSProvider 接口，封装 Groq 语音合成请求。
- `generateSpeech`：发送 HTTP POST 请求，返回单块 WAV 音频流。
- `TAG`：日志标签常量。

### `speech/src/main/java/me/rerere/tts/provider/providers/MiMoTTSProvider.kt`

实现 MiMo 文本转语音的流式提供者，通过 SSE 返回 PCM 音频。
- `MiMoTTSProvider`：TTSProvider 实现，发起流式请求。
- `MiMoSseProcessor`：处理 SSE 事件，提取音频块。
- `decodeMiMoAudioData`：解析 SSE 数据，解码 base64 音频。
- `MIMO_SAMPLE_RATE`：固定采样率 24000 Hz。
- `MiMoChunk`/`MiMoChoice`/`MiMoDelta`/`MiMoAudio`：音频增量 JSON 结构。

### `speech/src/main/java/me/rerere/tts/provider/providers/MiniMaxTTSProvider.kt`

MiniMax TTS 提供者，通过 SSE 流式生成 MP3 音频。  
- `MiniMaxTTSProvider`：实现流式 TTS 接口  
- `generateSpeech`：发起 SSE 请求，解码音频块  
- `hexStringToBytes`：十六进制转字节数组  
- `MiniMaxResponse` / `MiniMaxResponseData`：SSE 响应数据模型

### `speech/src/main/java/me/rerere/tts/provider/providers/OpenAITTSProvider.kt`

实现 OpenAI TTS API，将文本转为 MP3 音频流。
- `OpenAITTSProvider`：TTSProvider 实现，调用 /audio/speech 接口
- `generateSpeech`：生成 Flow<AudioChunk>，返回 MP3 数据

### `speech/src/main/java/me/rerere/tts/provider/providers/QwenTTSProvider.kt`

QwenTTS 提供者，通过 DashScope 流式 API 合成语音。
- `QwenTTSProvider`：实现 TTSProvider，提供 Qwen 语音合成
- `generateSpeech`：发起请求并返回 `Flow<AudioChunk>`
- `parseSSEData`：解析 SSE 事件，提取 Base64 音频和结束标志
- `TAG`：日志标签

### `speech/src/main/java/me/rerere/tts/provider/providers/StepTTSProvider.kt`

阶跃星辰 TTS 非流式合成适配器，调用 `/v1/audio/speech` 接口。
- `StepTTSProvider`：实现 TTSProvider 的阶跃 TTS 提供者
- `generateSpeech`：构建请求，执行 HTTP POST 合成语音，返回 Flow<AudioChunk>
- `TAG`：日志标签
- `JSON_MEDIA_TYPE`：JSON 请求媒体类型

### `speech/src/main/java/me/rerere/tts/provider/providers/SystemTTSProvider.kt`

实现系统 TTS 提供者，通过 Android TextToSpeech 合成语音。
- `SystemTTSProvider`：实现 TTSProvider，生成 WAV 音频块
- `TAG`：日志标签常量

### `speech/src/main/java/me/rerere/tts/provider/providers/VolcengineTTSProvider.kt`

{
  "path": "speech/src/main/java/me/rerere/tts/provider/providers/VolcengineTTSProvider.kt",
  "summary": "实现火山引擎 TTS 的语音合成提供者，基于 WebSocket 流式传输，将文本按实时音频流返回，处理协议编解码、错误恢复与连接生命周期。"
}

### `speech/src/main/java/me/rerere/tts/provider/providers/XAITTSProvider.kt`

实现xAI TTS服务的语音生成提供者。
- `XAITTSProvider`：TTSProvider实现类，调用xAI TTS API生成语音
- `generateSpeech`：接收TTS请求，返回MP3音频流

### `speech/src/test/java/me/rerere/asr/ASRProviderSettingMiMoTest.kt`

单元测试：验证 MiMo ASR 提供者默认值、注册与复制行为。
- `ASRProviderSettingMiMoTest`：测试类
- `mimo_defaults_are_expected`：校验默认配置
- `mimo_is_registered_in_provider_types`：检查注册
- `mimo_copy_provider_preserves_extra_fields`：测试复制后字段保留

### `speech/src/test/java/me/rerere/asr/ASRProviderSettingStepTest.kt`

这是对Step ASR配置的单元测试。
- `ASRProviderSettingStepTest`：测试Step默认值、注册与复制逻辑
- `step_defaults_are_expected`：验证默认配置值
- `step_is_registered_in_provider_types`：验证注册
- `step_copy_provider_preserves_extra_fields`：验证复制保留字段

### `speech/src/test/java/me/rerere/tts/ExampleUnitTest.kt`

示例本地单元测试，验证基本断言功能。
- `ExampleUnitTest`：示例测试类
- `addition_isCorrect`：验证2+2等于4

### `speech/src/test/java/me/rerere/tts/provider/TTSProviderSettingElevenLabsTest.kt`

测试 ElevenLabs TTS 设置的 JSON 序列化/反序列化。
- `TTSProviderSettingElevenLabsTest`：测试类
- `testElevenLabsSerialization`：验证 ElevenLabs 设置编解码及字段完整性

### `speech/src/test/java/me/rerere/tts/provider/TTSProviderSettingFishAudioTest.kt`

测试FishAudio设置的JSON序列化与反序列化。  
- `TTSProviderSettingFishAudioTest`：测试类  
- `testFishAudioSerialization`：验证序列化与反序列化正确性

### `speech/src/test/java/me/rerere/tts/provider/TTSProviderSettingMiMoTest.kt`

测试 TTSProviderSetting.MiMo 默认配置与注册。
- `TTSProviderSettingMiMoTest`：MiMo 配置单元测试
- `mimo_defaults_are_expected`：验证默认名称、URL、模型、语音、API 密钥
- `mimo_is_registered_in_provider_types`：验证 MiMo 类已注册到类型列表

### `speech/src/test/java/me/rerere/tts/provider/TTSProviderSettingStepTest.kt`

测试 TTSProviderSetting.Step 的默认值、注册及复制逻辑。
- `TTSProviderSettingStepTest`：测试类
- `step_defaults_are_expected`：验证默认配置
- `step_is_registered_in_provider_types`：确认注册到 Types 列表
- `step_copyProvider_preserves_id_and_name`：验证复制后保留 ID/名称及其他字段

### `speech/src/test/java/me/rerere/tts/provider/TTSProviderSettingVolcengineTest.kt`

{
  "path": "speech/src/test/java/me/rerere/tts/provider/TTSProviderSettingVolcengineTest.kt",
  "summary": "验证 Volcengine TTS 提供者设置的默认值符合预期，并测试其多态序列化/反序列化行为，确保类型标识正确。",
  "symbols": [
    {
      "name": "defaultsMatchAgentPlanContract",
      "kind": "method",
      "description": "检查默认设置与合约一致"
    },
    {
      "name": "serializesWithDedicatedType",
      "kind": "method",
      "description": "验证序列化包含类型标识和对称性"
    }
  ]
}

### `speech/src/test/java/me/rerere/tts/provider/providers/MiMoTTSProviderTest.kt`

MiMoTTSProvider 的单元测试：验证 SSE 音频解码与流处理。
- `MiMoTTSProviderTest`：测试类
- `decode_audio_data_from_sse_chunk`：解码 Base64 音频数据
- `ignore_sse_chunk_without_audio_data`：无音频时返回 null
- `emits_single_terminal_chunk_on_done_and_closed`：流结束发送终端块
- `throws_when_stream_closed_without_audio`：无音频关闭时抛异常

### `speech/src/test/java/me/rerere/tts/provider/providers/VolcengineTTSProtocolTest.kt`

{
  "path": "speech/src/test/java/me/rerere/tts/provider/providers/VolcengineTTSProtocolTest.kt",
  "summary": "Volcengine TTS 协议编解码的单元测试，验证请求帧格式、音频帧解析、会话结束及错误处理，确保提供商的协议实现遵循 V3 二进制规范。"
}
