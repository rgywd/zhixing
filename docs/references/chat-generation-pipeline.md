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
            │       ├── 稳定前缀（对话冻结的助手提示词；Provider 缓存边界）
            │       ├── 动态系统层（记忆 + tool.systemPrompt + checkpoint + runtime context）
            │       ├── 历史投影（最新 checkpoint + 最近完整轮次）
            │       ├── limitContext() 按 contextMessageSize 裁剪投影后的历史
            │       └── InputTransformers 管道
            │
            ├─ 首个 Step 请求前 token preflight
            │       ├── 已达到 78% 且 checkpoint 已准备 → 原子激活后重建请求
            │       ├── 未到安全上限且无已准备结果 → 不阻塞，继续当前请求
            │       └── 到安全上限且仍无结果 → 静默同步兜底并重建请求
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
    ├── prompt ≥ 窗口 60% → 单飞后台准备 checkpoint（不显示处理状态）
    ├── generateTitle()    （异步，使用 titleModel）
    └── generateSuggestion()（异步，使用 suggestionModel）

    ▼
Job finally
    └── 释放本次 generation lease；最后一个租约释放时停止前台服务
```

---

## 上下文检查点与 token preflight

压缩只处理会话历史层。对话在首条用户消息时冻结助手用户提示词；它作为系统消息的稳定首段和显式缓存边界。
生效记忆、工具系统补充、Workspace、Mode Injection、Lorebook、checkpoint 和 runtime context 在其后动态构建；
工具 schema 继续由 Provider 单独建立缓存边界。这些内容参与 token preflight，但不会被写入摘要。

`ContextCheckpoint` 作为 annotation 写在被覆盖历史的最后一条消息上。Room 与 UI 继续保留完整消息节点和
兄弟分支。后台生成的 checkpoint 先以 `active=false` 持久化，不改变当前投影；到激活线后只翻转该标记，模型
请求才改用摘要及其后的完整轮次。摘要以系统侧不可信历史上下文注入，不创建新的 `USER` 消息。编辑、删除或
切换消息分支时，应用清除全部 checkpoint，下次请求重新使用原始历史。

- 模型窗口：`Model.contextWindowTokens` 默认 500,000，可为窗口更小的模型覆盖。
- 自动准备：输入 Transformer 完成后，对真正发送的消息及工具定义做保守估算；达到模型窗口 60% 时，本轮
  回复照常完成，随后由会话级单飞 Job 在后台生成 checkpoint，不写 `processingStatus`、不清空聊天建议。
- 自动激活：达到窗口 78% 时，若后台结果已准备，只做原子持久化并重建请求；checkpoint 在两次压缩周期之间
  保持冻结，不做逐轮摘要改写。
- 安全兜底：默认在 `contextWindowTokens - 64,000` 处阻止继续膨胀；小于 256k 的模型改为预留窗口 25%。只有
  此时仍无准备结果才同步生成 checkpoint，且自动路径仍不显示“正在压缩”。
- 手动触发：用户随时从附件菜单创建 checkpoint；至少需要两个用户轮次，以确保最新完整轮次保留原文。
- 历史选择：以用户消息为轮次边界，不拆开最新轮次。自动路径按窗口 45% 的压缩后目标，扣除稳定/工具开销和
  8,000 Token 摘要预算后动态计算保留量；手动路径继续使用约 96,000 Token 的最近历史预算。
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
   可见的 `memory_find` / `memory_list` / `memory_read` / `memory_write`。只有 pinned 文件进入稳定提示；focused lookup
   先 find，显式浏览、零命中或歧义再 list，候选必须 exact read 后才能作为事实或用于更新。find/list 仅返回 metadata，
   不返回正文、来源、snippet 或 score，也不搜索原始聊天。四个工具只在 App 活跃的当前 Run 内按需调用；无变更时
   不调用 `memory_write`，也没有 Run 终态或后台补扫。再次修改同一文档时使用前一次读写结果返回的 version。写入
   来源 ID 由宿主根据当前对话绑定；校验失败返回 `retryable/error/correction`，显式记忆请求修正后重试，机会式失败
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
