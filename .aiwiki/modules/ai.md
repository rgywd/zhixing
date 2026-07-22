---
source_commit: 762c37f2ba2c6f3f982c8b1ae5761fadb904b8af
generated: 2026-07-21
---


# 模块：ai

# AI 模块

为 Android 应用提供基于 MNN 的模型推理与决策支持。

模块通过 JNI 调用本地 MNN 推理引擎完成模型加载与计算，推理核心逻辑位于 `ai/src` 下（当前空实现）。构建时依赖 `common` 模块提供基础能力，并引入 OkHttp 与 Kotlinx 序列化处理网络请求和数据交换。对外暴露统一的推理接口，由上层调用驱动控制流：请求→AI 推理→返回结果。

**修改指引**：新增推理算法或决策逻辑从 `ai/src` 开始；更换模型或调整引擎参数需同步修改 `mnn` 子模块构建脚本与 `build.gradle.kts` 依赖。


## 子模块

| 页面 | 文件数 | 职责 |
|---|---|---|
| [`src`](ai/src.md) | 0 | AI核心功能实现，负责模型推理、决策逻辑与算法支撑。 |

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

```
- 空 ProGuard 消费者规则文件，无自定义保护条目。
```

### `ai/proguard-rules.pro`

Android ProGuard 规则配置文件，当前仅含注释及示例未启用规则。  
- 无活跃规则：所有配置均为注释或示例说明。
