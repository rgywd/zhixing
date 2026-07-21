# 火山引擎 Agent Plan TTS

知行在语音设置中提供独立的“火山引擎”TTS 类型。该类型只负责语音合成，不恢复已经下架的
`volcengine_agent_plan` 通用模型提供商，也不复用其序列化类型。

## 默认配置

- API：`wss://openspeech.bytedance.com/api/v3/plan/tts/unidirectional/stream`
- Resource ID / 模型：`seed-tts-2.0`
- 音色：小何 2.0（`zh_female_xiaohe_jupiter_bigtts`）
- 音频：MP3、24 kHz、正常语速和响度

用户需要填写火山方舟 Agent Plan API Key。客户端通过 `X-Api-Key`、`X-Api-Resource-Id` 和
`X-Api-Connect-Id` 完成 WebSocket 握手鉴权，不把密钥写入消息体。

## 协议边界

请求使用火山 V3 二进制协议：4 字节协议头、4 字节大端负载长度和 UTF-8 JSON。服务端返回的
AudioOnly 帧按到达顺序输出；只有收到 `SessionFinished` 才视为成功。协议错误、失败事件、文本帧或
连接提前关闭都会作为明确错误返回，不能把半段音频当成完整合成结果。

参考：[火山方舟接入语音模型](https://console.volcengine.com/ark/region:cn-beijing/docs/82379/2516286?lang=zh)。
