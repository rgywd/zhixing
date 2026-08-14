# 消息生成链路文档

本文档描述从用户发送消息到 AI 回复完成的完整数据流，涉及的核心类与处理阶段。

## 核心类概览

| 类                          | 职责                          |
|----------------------------|-----------------------------|
| `ChatService`              | 入口与编排层，管理所有会话，对外暴露操作接口      |
| `ConversationSession`      | 单个会话的状态容器（引用计数、生成 Job、处理状态） |
| `ChatGenerationForegroundController` | 按生成运行持有前台服务租约，避免旧任务释放替换任务的保护 |
| `ChatGenerationForegroundService` | 普通 Chat 后台存活、进行中通知和取消入口 |
| `GenerationHandler`        | 核心生成逻辑，驱动 Step 循环与工具调用      |
| `InputMessageTransformer`  | 发送给 API 前对消息列表的变换管道         |
| `OutputMessageTransformer` | 接收到流式 chunk 后对消息列表的变换管道     |

---

## 完整生成链路

```
用户发送消息
    │
    ▼
ChatService.sendMessage()
    ├── 同步取得 generation lease 并启动前台服务
    ├── 取消上一个 Job（cancel + join）
    ├── finishInterruptedPendingTools()  // 补全上次被打断的 Tool 输出
    ├── preprocessUserInputParts()       // 对用户文本执行助手 regex 替换
    ├── 将 UIMessage(USER) 追加到 Conversation.messageNodes
    └── handleMessageComplete()
            │
            ▼
        GenerationHandler.generateText()   ← Flow<GenerationChunk>
            │  (最多 maxSteps=256 轮循环)
            │
            ├─ [若无待处理 Tool] 构建 internalMessages
            │       ├── 稳定层（系统提示 + 记忆 + tool.systemPrompt）
            │       ├── 历史投影（最新 checkpoint + 最近完整轮次）
            │       ├── limitContext() 按 contextMessageSize 裁剪投影后的历史
            │       └── InputTransformers 管道
            │
            ├─ 首个 Step 请求前 token preflight
            │       ├── < 262,000 → 继续
            │       └── ≥ 262,000 → 自动生成 checkpoint、落库并重新构建请求
            │
            ├─ generateInternal()
            │       ├── 构建 TextGenerationParams
            │       └── 调用 Provider
            │               ├── stream=true → providerImpl.streamText() 逐 chunk emit
            │               └── stream=false → providerImpl.generateText() 一次返回
            │
            ├─ 每次收到 chunk → OutputTransformers.transforms() (实时)
            │                  → OutputTransformers.visualTransforms() → emit GenerationChunk
            │
            ├─ 生成完毕 → OutputTransformers.visualTransforms()
            │           → OutputTransformers.onGenerationFinish()
            │           → 设置 message.finishedAt
            │
            ├─ 检查最新消息中是否有未执行 Tool
            │       ├── 无 Tool → break（生成结束）
            │       ├── ask_user → 设为 ToolApprovalState.Pending
            │       │             → emit → break（等待用户回答）
            │       └── 普通 Tool → 直接执行（见下）
            │
            ├─ 工具执行
            │       ├── Answered → 直接使用用户提供的答案
            │       └── 普通工具 → toolDef.execute(args)
            │               ├── 若输出超 32KB 且有 shell 权限 → 截断并写入文件
            │               └── CancellationException 必须向上传播（不能吞掉）
            │
            └─ 将执行结果写回 messages → emit → 继续下一 Step
                    (Tool 结果内联在 ASSISTANT 消息的 parts 中，不创建 TOOL 角色消息)

    ▼
onCompletion（Flow 结束或取消）
    ├── cancelLiveUpdateNotification()
    ├── 对所有消息 finishReasoning()（兜底）
    ├── 在 NonCancellable 上下文保存最终或局部回复
    └── 若 App 不在前台 → sendGenerationDoneNotification()

    ▼
onSuccess
    ├── generateTitle()    （异步，使用 titleModel）
    └── generateSuggestion()（异步，使用 suggestionModel）

    ▼
Job finally
    └── 释放本次 generation lease；最后一个租约释放时停止前台服务
```

---

## 上下文检查点与 token preflight

压缩只处理会话历史层。当前系统提示、助手配置、生效记忆、工具系统提示与 schema、Workspace、Mode
Injection 和 Lorebook 等稳定请求内容由每次请求重新构建；它们参与 token preflight，但不会被写入摘要。

`ContextCheckpoint` 作为 annotation 写在被覆盖历史的最后一条消息上。Room 与 UI 继续保留完整消息节点和
兄弟分支；模型请求只取最新 checkpoint 摘要及其后的完整轮次。摘要以系统侧不可信历史上下文注入，不创建新的
`USER` 消息。编辑、删除或切换消息分支时，应用清除可能失效的 checkpoint，下次请求重新使用原始历史。

- 自动触发：输入 Transformer 完成后，对真正准备发送的消息及工具定义做保守估算，达到 262,000 token
  后才触发；消息条数不参与触发条件。
- 手动触发：用户随时从附件菜单创建 checkpoint；至少需要两个用户轮次，以确保最新完整轮次保留原文。
- 历史选择：以用户消息为轮次边界，默认保留约 96,000 token 的最近完整轮次，不拆开最新轮次。
- 摘要生成：默认目标 8,000 token，模型请求的 `maxTokens` 与 UI 预算一致；输入按 96,000 token 顺序
  分块，并串行生成、逐层归并 checkpoint。
- 估算与计费：本地估算以 UTF-8 字节、消息开销、工具 schema 和多媒体保留量计算，故意偏保守；
  Provider 返回的 usage 才是账单和统计真值。

---

## 阶段一：用户消息预处理

**入口**：`ChatService.preprocessUserInputParts()`

对用户输入的 `UIMessagePart.Text` 执行 `Assistant.replaceRegexes(scope=USER)`，即助手配置中 `AffectScope.USER`
的正则替换规则，用于规范化或脱敏输入文本。非文本 Part（图片、文档等）不参与处理。

当普通 Chat 绑定 Workspace 时，正则替换前还会识别消息开头的安全变量声明块：

- `$NAME=value` 写入当前会话的进程内临时环境；
- `$$NAME=value` 写入 Android Keystore 加密的用户环境；
- 空值删除对应变量；
- 持久化消息只保留变量名和操作结果，不保留或发送变量值。

`workspace_shell` 执行时取得用户变量与当前会话临时变量的快照，临时变量同名时优先。变量值通过进程环境
传入，不拼接到工具参数或命令文本；工具 stdout/stderr 写回消息前会按变量值脱敏。

---

## 阶段二：InputMessage 变换管道

**时机**：`buildInternalMessages()` 构建请求时、token preflight 与 Provider 调用前执行。

变换器按顺序执行（`fold`），每个变换器接收上一个的输出：

| 顺序 | 变换器                            | 说明                                       |
|----|--------------------------------|------------------------------------------|
| 1  | `TimeReminderTransformer`      | 在系统消息中注入当前时间/日期                          |
| 2  | `PromptInjectionTransformer`   | 注入 ModeInjection 和 Lorebook 触发的提示词       |
| 3  | `PlaceholderTransformer`       | 替换消息中的 `{{placeholder}}` 占位符             |
| 4  | `DocumentAsPromptTransformer`  | 将文档附件转换为文本内容注入消息                         |
| 5  | `OcrTransformer`               | 对图片 Part 执行 OCR，将识别结果附加为文本               |
| 6  | `TemplateTransformer`          | 用 Pebble 模板引擎渲染消息（可访问 time/date/role 变量） |
| 7  | `WorkspaceReminderTransformer` | 若对话关联 Workspace，注入工作区路径提示                |

`PromptInjectionTransformer` 支持四种注入位置：

- `BEFORE_SYSTEM_PROMPT` / `AFTER_SYSTEM_PROMPT` — 插入到系统消息前后
- `TOP_OF_CHAT` — 插入到第一条用户消息前
- `BOTTOM_OF_CHAT` — 插入到最后一条消息前
- `AT_DEPTH` — 从最新消息往前数第 N 条处插入

---

## 阶段三：OutputMessage 变换管道

分三个时机调用：

| 时机                  | 方法                     | 说明                             |
|---------------------|------------------------|--------------------------------|
| 流式 chunk 到达时（实时）    | `transforms()`         | 真实变换，用于内部消息存储                  |
| 流式 chunk 到达时（UI 展示） | `visualTransforms()`   | 视觉变换，不影响实际存储（如流式 think tag 转换） |
| 生成完全结束后             | `onGenerationFinish()` | 最终后处理，如 base64 图片落盘            |

当前 Output 变换器：

| 变换器                                 | transforms            | visualTransform               | onGenerationFinish |
|-------------------------------------|-----------------------|-------------------------------|--------------------|
| `ThinkTagTransformer`               | —                     | ✓（`<think>` → Reasoning Part） | ✓（最终提取）            |
| `Base64ImageToLocalFileTransformer` | —                     | —                             | ✓（base64 → 本地文件）   |
| `RegexOutputTransformer`            | ✓（助手 regex OUTPUT 替换） | —                             | —                  |

---

## 阶段四：工具系统

### 工具注册顺序

`handleMessageComplete()` 中按如下顺序构建工具列表：

1. **Search Tools**（`createSearchTools`）— 当 `settings.enableWebSearch = true` 时
2. **Local Tools**（`localTools.getTools(assistant.localTools)`）— 按助手配置启用：
  - `JavascriptEngine`：执行 JS 代码片段
  - `TimeInfo`：获取当前时间
  - `Clipboard`：读写剪贴板
  - `Tts`：文字转语音
  - `AskUser`：向用户提问（等待用户回答，不是工具审批）
  - `ScreenTime`：获取屏幕使用时间
3. **Conversation Tools**（`createConversationTools`）— `enableRecentChatsReference = true` 时，查询历史对话
4. **Knowledge Tools**（`createKnowledgeTools`）— 仅当绑定知识空间已初始化且存在可检索文档时注入；空库不向模型暴露工具
5. **Workspace Tools**（`createWorkspaceToolsIfReady`）— Workspace Shell 就绪时注入，含
   `workspace_shell`；安全变量只向模型公开名称，并在工具执行时注入环境
6. **Skill Tools**（`createSkillTools`）— 助手启用的 Skill 列表
7. **MCP Tools** — 所有已连接 MCP 服务器的工具，命名格式 `mcp__{serverName}__{toolName}`
8. **Memory Tools**（`buildMemoryDocumentTools`，由 `GenerationHandler` 注册）— `enableMemory = true` 时提供
   可见的 `memory_read` / `memory_write`。两者只在 App 活跃的当前 Run 内按需调用；无变更时不调用
   `memory_write`，也没有 Run 终态或后台补扫。每次调用只读或写一份文档；同一 Run 可沿直接相关关系连续读取，
   也可按事实归属连续修改多份文档，证据足够后停止。再次修改同一文档时使用前一次结果返回的 version。写入来源
   ID 由宿主根据当前对话绑定；校验失败返回 `retryable/error/correction`，显式记忆请求修正后重试，机会式失败
   不阻塞普通回答

### 工具问答状态机

```
普通 Tool ─────────────────────► 直接执行

ask_user ──► Pending ── 用户回答 ──► Answered ──► 使用回答继续生成
```

`ask_user` 的回答仍通过兼容入口 `ChatService.handleToolApproval()` 写回，更新状态后重新调用
`handleMessageComplete()`；`GenerationHandler` 检测到 `Answered` 后使用用户文本继续。`needsApproval`、历史
`Approved`/`Denied` 状态与 Web approval route 仅为旧消息兼容，新运行时不会让普通工具进入等待批准。

### 工具输出截断

当 Workspace Shell 工具可用且工具输出超过 **32KB** 时，输出被截断：

- 前 4KB 保留在消息中
- 完整输出写入 `filesDir/tool_outputs/{toolCallId}.txt`
- 消息中附带 shell 读取指令提示

---

## 阶段五：会话生命周期管理

`ConversationSession` 使用**原子引用计数**管理内存生命周期：

- `acquire()` / `release()` — UI 页面打开/关闭时调用
- `refCount == 0 && !isGenerating` — 触发 5 秒空闲超时后，`ChatService.removeSession()` 清理 session
- `setJob()` — 设置生成 Job，完成后自动置 null 并触发空闲检查

---

## 阶段六：后台执行与通知

| 类型 | 触发条件 | Channel |
| --- | --- | --- |
| 生成前台服务（ongoing） | 任一普通 Chat 回复正在运行 | `CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID` |
| Live Update | 用户已开启实时进度、生成过程中且 App 在后台 | `CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID` |
| 生成完成 | 用户已开启完成通知、生成结束且 App 在后台 | `CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID` |

Live Update 通知内容根据当前生成状态动态更新：

- 工具执行中 → 显示工具名与输入预览
- 推理中（Reasoning）→ 显示推理内容片段
- 写回复中 → 显示文本内容片段

前台服务租约在发送、重新生成和 `ask_user` 回答恢复入口中同步取得，早于异步 Job 调度。通知提供返回目标会话和取消
当前目标会话生成的入口；多个会话并行时，结束一个会话不会停止其余生成。用户未授权普通通知时，Android 仍可
在系统的前台服务任务界面展示该服务，但可选的实时内容通知和完成通知保持原设置语义。

---

## 关键文件路径

```
app/src/main/java/me/rerere/rikkahub/
├── service/
│   ├── ChatService.kt              # 编排入口
│   ├── ChatGenerationForegroundController.kt # 生成租约与服务启停
│   ├── ChatGenerationForegroundService.kt # 后台执行前台服务
│   ├── ChatGenerationLeaseRegistry.kt # 并发租约状态
│   └── ConversationSession.kt      # 会话状态容器
└── data/ai/
    ├── ContextCompaction.kt       # checkpoint 投影、轮次选择与 token 分块
    ├── GenerationHandler.kt        # 核心生成逻辑
    ├── PromptTokenEstimator.kt     # Provider 无关的保守请求估算
    ├── transformers/
    │   ├── Transformer.kt          # 接口定义与扩展函数
    │   ├── PromptInjectionTransformer.kt
    │   ├── TemplateTransformer.kt
    │   ├── TimeReminderTransformer.kt
    │   ├── ThinkTagTransformer.kt
    │   ├── RegexOutputTransformer.kt
    │   ├── DocumentAsPromptTransformer.kt
    │   ├── OcrTransformer.kt
    │   ├── Base64ImageToLocalFileTransformer.kt
    │   ├── PlaceholderTransformer.kt
    │   └── WorkspaceReminderTransformer.kt
    └── tools/
        ├── SearchTools.kt
        ├── ConversationTools.kt
        ├── WorkspaceTools.kt
        ├── SkillsTools.kt
        ├── MemoryTools.kt
        └── local/
            └── LocalTools.kt       # 本地工具注册表
```
