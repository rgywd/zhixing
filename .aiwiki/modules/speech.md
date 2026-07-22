---
source_commit: 762c37f2ba2c6f3f982c8b1ae5761fadb904b8af
generated: 2026-07-21
---


# 模块：speech

**语音识别（ASR）与文本转语音（TTS）能力收敛模块**

模块以 Android 库形式组织，依赖 `common` 提供基础能力，集成 Compose 构建 UI 交互与 Media3 处理音频录制/播放。核心数据流：麦克风采集 -> Media3 编码 -> OkHttp 发送至 ASR 服务 -> 返回文本；逆过程文本通过 TTS 引擎生成音频并播放。对外暴露 Composable 组件（如录音按钮、播放条）及服务类，供上层业务模块调用。关键依赖 `common` 模块、Media3（音频管线）、OkHttp + kotlinx.serialization（网络调用）。

**修改指引**：当前 `src` 为空，典型改动应从创建 `SpeechManager` 或 `RecognitionService` 类入手，搭建音频采集与网络请求骨架。


## 子模块

| 页面 | 文件数 | 职责 |
|---|---|---|
| [`src`](speech/src.md) | 0 | 语音识别（ASR）与文本转语音（TTS）能力收敛模块 |

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
