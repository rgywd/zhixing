# 火山引擎 Agent Plan ASR

知行的火山引擎 ASR 使用方舟 Agent Plan 专属 API Key，并默认接入实时双流接口：

- WebSocket：`wss://openspeech.bytedance.com/api/v3/plan/sauc/bigmodel_async`
- Resource ID：`volc.seedasr.sauc.duration`
- 音频：PCM、16 kHz、16 bit、单声道

客户端通过 `X-Api-Key`、`X-Api-Resource-Id`、`X-Api-Request-Id`、
`X-Api-Connect-Id` 和 `X-Api-Sequence` 完成握手鉴权。普通版接口
`wss://openspeech.bytedance.com/api/v3/sauc/bigmodel` 不接受 Agent Plan API Key。

同一枚 Agent Plan API Key 可以同时用于方舟聊天、搜索、TTS 与 ASR；每项能力仍需使用
各自正确的 Resource ID 和接口地址。
