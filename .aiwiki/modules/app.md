---
source_commit: faa1320682d60db0cf30f821d05ed660197e00ed
generated: 2026-07-21
---

# 模块：app

知行 AI 助手 Android 应用的全功能主模块，集成聊天、AI 交互、工作区、设置、备份等全部界面与业务逻辑。

模块以 Koin 依赖注入组织，UI 层由 Compose 页面 (`ui/pages`) 构成，数据层通过 Room 数据库 (`data/db`) 与 Repository (`data/repository`) 管理持久化，核心服务包括 `ChatService`、`WebServerService`、`PhoneWorkTrackingService`。AI 交互流经 `ChatService` → `GenerationHandler` → 网络层，再经消息转换器 (`data/ai/transformers`) 处理后进入 UI。设置通过 `PreferencesStore` 统一管理，对外提供 Web API、文件提供者与文档提供者。

修改指引：UI 改动从 `ui/pages` 对应页面入手；数据层变更从 `data/db` 或 `data/repository` 开始；AI 生成与工具调用调整在 `data/ai` 和 `service/ChatService`；设置项修改在 `data/datastore/PreferencesStore` 与 `ui/pages/setting`。

## 文件摘要

### `app/baselineprofile/build.gradle.kts`

Android 基线配置文件生成模块构建脚本。
- `plugins`：android.test、baselineprofile
- `namespace`：me.rerere.baselineprofile
- `compileSdk`：36, minorApiLevel=1
- `minSdk`：28, targetSdk=36
- `suppressErrors`：EMULATOR
- `targetProjectPath`：:app
- `baselineProfile`：useConnectedDevices=true
- `dependencies`：基准测试、Espresso、JUnit、UiAutomator
- `androidComponents`：传递 targetAppId

### `app/baselineprofile/src/main/AndroidManifest.xml`

Android 基线性能测试的空清单文件。  
- 无实际组件，仅声明空 `<manifest />`。

### `app/baselineprofile/src/main/java/me/rerere/baselineprofile/BaselineProfileGenerator.kt`

生成启动基线配置，覆盖聊天输入与 Markdown 渲染路径。
- `BaselineProfileGenerator`：基线配置生成器类
- `rule`：`BaselineProfileRule` 规则
- `generate()`：执行关键用户流程的测试方法

### `app/baselineprofile/src/main/java/me/rerere/baselineprofile/StartupBenchmarks.kt`

应用启动基准测试，对比有无 Baseline Profile 的冷启动速度。
- `StartupBenchmarks`：测试类，定义启动基准测试
- `startupCompilationNone`：无优化编译启动测试
- `startupCompilationBaselineProfiles`：使用 Baseline Profiles 启动测试
- `benchmark`：执行 Macrobenchmark 测量逻辑

### `app/build.gradle.kts`

{
  "path": "app/build.gradle.kts",
  "summary": "Android 主应用模块构建脚本，配置编译SDK、版本、签名、ABI 拆分、Kotlin 编译器选项及大量依赖，并定义自定义任务 buildAll 同时构建 APK 和 AAB。整合 Koin、Room、Ktor、Compose 等核心库，配置发布构建与调试变体，启用 Compose 和 buildConfig 特性，并设置 Room schema 导出位置。"
}

### `app/compose_compiler_config.conf`

Compose 编译器稳定性配置文件，声明稳定类。
- `kotlinx.collections.immutable.*`
- `kotlin.uuid.*`
- `kotlin.time.*`
- `java.time.*`
- `me.rerere.rikkahub.data.model.Conversation`
- `me.rerere.rikkahub.data.model.MessageNode`
- `me.rerere.ai.ui.UIMessage`
- `me.rerere.ai.ui.UIMessageChoice`
- `me.rerere.ai.ui.MessageChunk`
- `me.rerere.ai.ui.UIMessageAnnotation`
- `me.rerere.ai.ui.ToolApprovalState`
- `me.rerere.ai.core.TokenUsage`

### `app/proguard-rules.pro`

```markdown
Android ProGuard 规则：保留序列化/JWT 类，关闭混淆。
- `-keepattributes SourceFile,LineNumberTable`：保留行号
- `-keep @kotlinx.serialization.Serializable class *`：保留序列化类
- `-keep class org.scilab.forge.jlatexmath.**`：保留 jlatexmath
- `-dontobfuscate`：关闭混淆
- `-dontwarn`：抑制 java.lang.management/java.beans/re2j 缺失警告
- `-keepattributes Signature,InnerClasses,EnclosingMethod`：保留泛型签名
- `-keep class com.fasterxml.jackson.**` & `com.auth0.jwt.**`：保留 Jackson 和 Auth0 JWT
```

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/1.json`

Room 数据库版本1的 schema 导出文件，定义 ConversationEntity 表结构。
- `ConversationEntity`：表，含 id, title, messages, create_at, update_at 字段，主键 id。

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/10.json`

Room 数据库 v10 的 schema 导出，定义 ConversationEntity 与 MemoryEntity 两张表结构。
- `ConversationEntity`：对话表，字段 id, assistantId, title, nodes, createAt, updateAt, truncateIndex, chatSuggestions, isPinned
- `MemoryEntity`：记忆表，字段 id, assistantId, content

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/11.json`

Room 数据库 schema 版本 11，定义三个实体表及列信息。

- `ConversationEntity`：会话表，含 id, assistantId, title, nodes, createAt, updateAt, truncateIndex, chatSuggestions, isPinned
- `MemoryEntity`：记忆表，含 id, assistantId, content
- `GenMediaEntity`：生成媒体表，含 id, path, modelId, prompt, createAt

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/12.json`

Room 数据库 schema v12，定义会话、记忆、生成媒体、消息节点四张表。
- `ConversationEntity`：会话，含标题、节点、建议、置顶等。
- `MemoryEntity`：记忆，含助手ID、内容。
- `GenMediaEntity`：生成媒体，含路径、模型ID、提示词。
- `message_node`：消息节点，含会话外键、消息、选择索引。

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/13.json`

Room 数据库版本 13 的 schema 定义，描述表结构。
- `ConversationEntity`：会话表（id, 标题, 节点, 置顶等）
- `MemoryEntity`：助手记忆表
- `GenMediaEntity`：生成媒体记录表
- `message_node`：消息节点（关联会话, 级联删除）
- `managed_files`：托管文件元数据表（unique 相对路径）

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/14.json`

Room 数据库版本14的 schema 导出
- `ConversationEntity`：会话表
- `MemoryEntity`：记忆表
- `GenMediaEntity`：生成媒体记录表
- `message_node`：消息节点表（外键关联会话）
- `managed_files`：受管文件表

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/15.json`

Room数据库v15 schema导出，定义6张表。  
- `ConversationEntity`：对话  
- `MemoryEntity`：记忆  
- `GenMediaEntity`：生成媒体  
- `message_node`：消息节点  
- `managed_files`：文件管理  
- `favorites`：收藏

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/16.json`

RikkHub Room 数据库版本16的 schema 导出，定义6个实体表。  
- `ConversationEntity`：对话记录  
- `MemoryEntity`：记忆  
- `GenMediaEntity`：生成媒体  
- `message_node`：消息节点（外键关联 ConversationEntity）  
- `managed_files`：托管文件  
- `favorites`：收藏

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/17.json`

Room 数据库版本 17 schema 定义，含 6 张表与索引。
- `ConversationEntity`：对话表，存节点、置顶等。
- `MemoryEntity`：助手记忆表。
- `GenMediaEntity`：生成媒体记录。
- `message_node`：消息节点，关联对话，级联删除。
- `managed_files`：托管文件，唯一路径索引。
- `favorites`：收藏表，含快照与索引。

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/18.json`

Room 数据库版本18的Schema导出，定义6张核心表结构。

- `ConversationEntity`：会话表，含标题、节点、置顶等
- `MemoryEntity`：助手记忆表
- `GenMediaEntity`：生成媒体记录，存储路径、模型、提示词
- `message_node`：消息节点，关联会话，级联删除
- `managed_files`：托管文件索引，唯一相对路径
- `favorites`：收藏夹，通用ref_json快照

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/19.json`

数据库版本19的Android Room schema导出，定义6张表结构。
- `ConversationEntity`：对话会话
- `MemoryEntity`：助手记忆
- `GenMediaEntity`：生成媒体记录
- `message_node`：消息节点（外键关联对话）
- `managed_files`：托管文件索引
- `favorites`：收藏夹

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/2.json`

数据库版本2的Room schema导出文件，定义ConversationEntity和MemoryEntity表结构。
- `ConversationEntity`：会话表，字段id(主键)、title、messages、create_at、update_at。
- `MemoryEntity`：记忆表，字段id(自增主键)、assistant_id、content。

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/20.json`

定义 Room 数据库版本 20 的表结构，含 6 个实体。
- `ConversationEntity`：会话表，主键 id
- `MemoryEntity`：记忆表，自增主键 id
- `GenMediaEntity`：媒体生成记录，自增主键 id
- `message_node`：消息节点，外键关联 ConversationEntity，级联删除
- `managed_files`：受管文件，唯一索引 relative_path
- `favorites`：收藏，唯一索引 ref_key

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/21.json`

Room 数据库 v21 的 schema 导出，定义 7 个数据表及索引。
- `ConversationEntity`：对话
- `MemoryEntity`：记忆
- `GenMediaEntity`：生成媒体
- `message_node`：消息节点
- `managed_files`：托管文件
- `favorites`：收藏
- `workspaces`：工作区

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/22.json`

Room 数据库版本 22 的 schema 快照，定义 7 张表的结构与索引。

- `ConversationEntity`：对话会话表
- `MemoryEntity`：记忆内容表
- `GenMediaEntity`：生成媒体记录表
- `message_node`：消息节点表（含外键、索引）
- `managed_files`：管理文件表（唯一索引）
- `favorites`：收藏表（多种索引）
- `workspaces`：工作区表（根目录唯一索引）

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/23.json`

Room 数据库版本23的 schema 导出，定义对话、记忆、媒体、文件、收藏、工作区等表。
- `ConversationEntity`：对话
- `MemoryEntity`：记忆
- `GenMediaEntity`：生成媒体
- `message_node`：消息节点
- `managed_files`：托管文件
- `favorites`：收藏
- `workspaces`：工作区

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/24.json`

Room 数据库 v24 schema 导出定义，描述所有表结构及索引。
- `ConversationEntity`：对话表
- `MemoryEntity`：助手记忆
- `GenMediaEntity`：生成媒体
- `message_node`：消息节点
- `managed_files`：托管文件
- `favorites`：收藏
- `workspaces`：工作区
- `conversation_folder`：对话文件夹

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/25.json`

数据库 schema 快照 v25，定义 12 个实体表  
关键表：`ConversationEntity`（对话），`message_node`（消息），`managed_files`（文件），`workspaces`（工作区），`work_sessions`（工作会话）等

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/26.json`

Room schema v26，定义12张表。
- `ConversationEntity`：会话
- `MemoryEntity`：记忆
- `GenMediaEntity`：生成媒体
- `message_node`：消息节点
- `managed_files`：文件
- `favorites`：收藏
- `workspaces`：工作区
- `conversation_folder`：文件夹
- `work_sessions`：工作会话
- `work_messages`：工作消息
- `work_machines`：机器
- `work_repo_presets`：仓库预设

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/27.json`

Room 数据库 v27 的 schema 导出文件，定义 12 个实体表结构。
- `ConversationEntity`：会话主表
- `MemoryEntity`：记忆数据
- `GenMediaEntity`：生成媒体
- `message_node`：消息节点
- `managed_files`：托管文件
- `favorites`：收藏夹
- `workspaces`：工作区
- `conversation_folder`：会话文件夹
- `work_sessions`：工作会话
- `work_messages`：工作消息
- `work_machines`：远程机器
- `work_repo_presets`：仓库预设

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/28.json`

Room 数据库版本 28 的 schema 导出文件，定义 22 张表结构。
- `ConversationEntity`：对话记录
- `MemoryEntity`：记忆条目
- `GenMediaEntity`：生成媒体
- `message_node`：消息节点
- `managed_files`：托管文件
- `favorites`：收藏夹
- `workspaces`：工作区
- `conversation_folder`：对话文件夹
- `work_sessions`：工作会话
- `work_messages`：工作消息
- `work_machines`：远程机器
- `work_repo_presets`：仓库预设
- `codex_machines`：Codex 机器
- `codex_projects`：Codex 项目
- `codex_threads`：Codex 线程
- `codex_turns`：对话轮次
- `codex_items`：消息项
- `codex_runtime_bindings`：运行时绑定
- `codex_approvals`：审批
- `codex_catalog_sync`：目录同步
- `codex_catalog_chunks`：目录分块
- `codex_tombstones`：删除标记

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/29.json`

Room数据库版本29 schema文件，定义17个实体表用于本地持久化。
- `ConversationEntity`：对话
- `MemoryEntity`：记忆
- `message_node`：消息节点
- `workspaces`：工作区
- `codex_threads`：Codex线程
- `codex_items`：Codex条目

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/3.json`

定义 Room 数据库版本 3 的表结构，含对话与记忆实体。
- `ConversationEntity`：id, title, messages, create_at, update_at
- `MemoryEntity`：id, assistant_id, content

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/30.json`

数据库版本30的Room schema导出，定义29个实体表。
- `ConversationEntity`：对话
- `codex_threads`：Codex线程
- `codex_items`：Codex消息项
- `MemoryEntity`：记忆
- `workspaces`：工作区
- `work_sessions`：工作会话

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/31.json`

RikkaHub 数据库版本31的Room schema导出，定义会话、记忆、工作区、Codex等实体表。
- `ConversationEntity`：会话
- `MemoryEntity`：记忆
- `message_node`：消息节点
- `workspaces`：工作区
- `work_sessions`：工作会话
- `codex_*`：Codex集成（机器、项目、线程、条目等）

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/33.json`

Room 数据库版本 33 的模式快照，定义 10 张表结构。  
- `ConversationEntity`：对话  
- `MemoryEntity`：记忆  
- `GenMediaEntity`：生成媒体  
- `message_node`：消息节点  
- `managed_files`：托管文件  
- `favorites`：收藏  
- `workspaces`：工作区  
- `conversation_folder`：对话文件夹  
- `phone_work_sessions`：手机工作会话  
- `phone_work_events`：手机工作事件

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/34.json`

Room 数据库版本 34 schema 文件，定义 10 张表及索引、外键。
- `ConversationEntity`：对话
- `MemoryEntity`：记忆
- `GenMediaEntity`：生成媒体
- `message_node`：消息节点
- `managed_files`：托管文件
- `favorites`：收藏
- `workspaces`：工作区
- `conversation_folder`：对话文件夹
- `phone_work_sessions`：手机工作会话
- `phone_work_events`：手机工作事件

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/35.json`

{
  "path": "app/schemas/me.rerere.rikkahub.data.db.AppDatabase/35.json",
  "summary": "Room 数据库版本 35 的 schema 导出文件，用于在编译时验证 AppDatabase 的实体结构与迁移路径，包含对话、记忆、工作区、收藏、文件管理及电话工作会话等核心表定义。"
}

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/4.json`

定义 Room 数据库版本 4 的表结构，包含两个实体表及初始化查询。
- `ConversationEntity`：对话表，含 id, title, messages, usage, create_at, update_at
- `MemoryEntity`：记忆表，含 id, assistant_id, content
- `setupQueries`：创建 room_master_table 并插入 identity_hash

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/5.json`

Room 数据库版本5的 schema 导出，定义对话与记忆表结构。
- `ConversationEntity`：对话表（id, assistant_id, title, messages, usage, create_at, update_at）
- `MemoryEntity`：记忆表（id, assistant_id, content）

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/6.json`

Room 数据库版本 6 的 schema 导出，定义 ConversationEntity 和 MemoryEntity 表。
- `ConversationEntity`：会话表，字段 id, assistantId, title, messages, tokenUsage, createAt, updateAt, truncateIndex。
- `MemoryEntity`：记忆表，字段 id, assistantId, content。

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/7.json`

Room数据库版本7的schema，含对话和记忆表。
- `ConversationEntity`：对话表，字段包括id, assistantId, title, nodes, tokenUsage, createAt, updateAt, truncateIndex。
- `MemoryEntity`：记忆表，字段包括id(自增), assistantId, content。

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/8.json`

Room 数据库 schema 版本 8，定义 ConversationEntity 和 MemoryEntity 表结构。
- `ConversationEntity`：对话表，主键 id，字段 assistantId, title, nodes, tokenUsage, createAt, updateAt, truncateIndex, chatSuggestions。
- `MemoryEntity`：记忆表，自增主键 id，字段 assistantId, content。

### `app/schemas/me.rerere.rikkahub.data.db.AppDatabase/9.json`

Room 数据库 v9 的 schema 导出，定义 ConversationEntity 和 MemoryEntity 表。
- `ConversationEntity`：对话表，字段 id, title, nodes, createAt, updateAt, truncateIndex, suggestions。
- `MemoryEntity`：记忆表，字段 id, assistantId, content。

### `app/src/androidTest/java/me/rerere/rikkahub/ExampleInstrumentedTest.kt`

Android 仪器化测试示例，验证应用包名。

- `ExampleInstrumentedTest`：仪器化测试类
- `useAppContext`：校验 targetContext 包名是否为 `me.rerere.rikkahub`

### `app/src/androidTest/java/me/rerere/rikkahub/data/db/migrations/Migration_11_12_Test.kt`

验证 Room 数据库迁移 11→12 的测试类。
- `Migration_11_12_Test`：测试消息节点表创建、数据迁移、分支消息、空对话、多对话、索引生成及超大对话处理。

### `app/src/androidTest/java/me/rerere/rikkahub/data/db/migrations/Migration_26_27_Test.kt`

测试 Room 数据库版本 26 到 27 的迁移逻辑。
- `Migration_26_27_Test`：迁移测试类
- `testDb`：测试数据库名
- `helper`：MigrationTestHelper 实例
- `legacyMemoryBecomesActiveContextWithoutLosingItsIdentityOrContent`：验证迁移后旧记忆变为 active context，kind 为 CONTEXT，state 为 ACTIVE，时间戳初始化为 0

### `app/src/androidTest/java/me/rerere/rikkahub/data/db/migrations/Migration_27_28_Test.kt`

测试数据库迁移 27→28 时新增 codex 表且保留旧数据。
- `Migration_27_28_Test`：迁移测试类
- `addsCodexCatalogWithoutChangingLegacyWorkData`：验证旧数据不变且 codex 表存在
- `testDb`：测试数据库名

### `app/src/androidTest/java/me/rerere/rikkahub/data/db/migrations/Migration_30_31_Test.kt`

测试 Room 数据库从版本 30 到 31 的迁移，确保新增表且保留已有数据。
- `Migration_30_31_Test`：版本 30→31 迁移测试类
- `addsThreadDetailRevisionWithoutChangingExistingData`：验证新增 `codex_thread_detail_revisions` 表且原数据不变

### `app/src/androidTest/java/me/rerere/rikkahub/data/db/migrations/Migration_32_33_Test.kt`

测试 Room 数据库从版本32迁移到33，移除废弃表并保留电话线相关表。
- `Migration_32_33_Test`：迁移测试类
- `removesRetiredWorkTablesAndKeepsPhoneLineTables`：验证废弃表移除，保留电话线表

### `app/src/main/AndroidManifest.xml`

Android 清单文件，声明权限、入口 Activity、服务与提供者。
- `uses-permission`：INTERNET、CAMERA、RECORD_AUDIO 等13项
- `RouteActivity`：主入口，处理 MAIN/SEND/PROCESS_TEXT
- `SafeModeActivity`：安全模式
- `ShortcutHandlerActivity`：快捷方式
- `McpOAuthCallbackActivity`：MCP OAuth 回调
- `UCropActivity`：图片裁剪
- `WebServerService`：本地 Web 服务
- `PhoneWorkTrackingService`：任务跟踪
- `FileProvider`：文件分享
- `WorkspaceDocumentsProvider`：文档提供者
- `InitializationProvider`：移除 WorkManager 初始化
- `queries`：TTS、启动器、桌面

### `app/src/main/assets/html/mark.html`

Android WebView中渲染Markdown的HTML模板，支持LaTeX、图表、代码高亮。
- 模板变量：`{{BACKGROUND_COLOR}}`等主题色、`{{MARKDOWN_BASE64}}`存储Base64编码的Markdown内容
- 脚本：`renderMarkdown`异步渲染函数
- 库：`markdown-it`、`katex`、`mermaid`、`highlight.js`

### `app/src/main/assets/simple_dict/pos_dict/prob_start.utf8`

中文分词HMM模型初始状态概率文件
- `B,<pos>`：词首状态概率
- `E,<pos>`：词尾状态概率（均为-3.14e+100）
- `M,<pos>`：词中状态概率（均为-3.14e+100）
- `S,<pos>`：单字词状态概率

### `app/src/main/assets/simple_dict/stop_words.utf8`

停用词列表文件，用于分词时过滤无意义词与标点。
- 中文停用词：常见虚词、代词、连词等
- 英文停用词：常见介词、冠词、代词等
- 标点符号：中英文标点及特殊字符

### `app/src/main/assets/simple_dict/user.dict.utf8`

用户自定义分词词典，供分词器加载。  
- `云计算`：自定义词条  
- `韩玉鉴赏`：自定义词条  
- `蓝翔 nz`：专有名词，词性 nz  
- `区块链 10 nz`：词频 10，专有名词 nz

### `app/src/main/java/me/rerere/rikkahub/AppIdentity.kt`

定义应用产品身份与托管服务策略的常量集合。
- `AppIdentity`：产品身份单例，包含名称、仓库地址、更新源等常量
- `productName`：产品名"Zhixing"
- `userAgentProduct`：UA标识"Zhixing-Android"
- `repositoryOwner`/`repositoryName`：仓库所有者和名称
- `sourceUrl`/`issueTrackerUrl`/`licenseUrl`：源码、问题、许可证链接
- `thirdPartyNoticesUrl`/`thirdPartyTelemetryEnabled`：第三方声明及遥测开关
- `updateFeedUrl`：更新信息JSON地址

### `app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/RikkaHubApp.kt",
  "summary": "Application 入口，负责初始化 Koin 依赖注入、通知渠道、崩溃处理、QuickJS 引擎、清理临时文件，并根据用户设置启动 Web 服务、Work 任务追踪与配置文件维护。",
  "symbols": [
    {
      "name": "RikkaHubApp",
      "kind": "class",
      "description": "Application 子类，编排应用启动时的所有初始化任务"
    },
    {
      "name": "AppScope",
      "kind": "class",
      "description": "全局协程作用域，用于启动长期后台任务"
    },
    {
      "name": "CHAT_COMPLETED_NOTIFICATION_CHANNEL_ID",
      "kind": "constant",
      "description": "聊天完成通知渠道 ID"
    },
    {
      "name": "CHAT_LIVE_UPDATE_NOTIFICATION_CHANNEL_ID",
      "kind": "constant",
      "description": "聊天实时更新通知渠道 ID"
    },
    {
      "name": "WEB_SERVER_NOTIFICATION_CHANNEL_ID",
      "kind": "constant",
      "description": "Web 服务前台通知渠道 ID"
    }
  ]
}

### `app/src/main/java/me/rerere/rikkahub/RouteActivity.kt`

`RouteActivity`：主 Activity，管理导航图与全局服务初始化  
- `Screen`：密封接口，定义所有页面路由键

### `app/src/main/java/me/rerere/rikkahub/data/ai/AIRequestInterceptor.kt`

网络请求拦截器，当前无额外处理逻辑。
- `AIRequestInterceptor`：OkHttp拦截器，未修改请求

### `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationHandler.kt`

处理 AI 文本生成、多步工具调用与翻译。
- `GenerationHandler`：管生成流程与工具执行
- `generateText`：多步生成、工具调用与审批
- `translateText`：执行文本翻译（含 Qwen 专用模式）
- `GenerationChunk.Messages`：生成结果消息分块

### `app/src/main/java/me/rerere/rikkahub/data/ai/GenerationPrompts.kt`

为AI提示生成记忆上下文，筛选并格式化长期记忆。
- `selectMemoriesForPrompt`：筛选活跃记忆，分档取PROFILE/CONTEXT。
- `buildMemoryPrompt`：构建插入提示的记忆块，含指引和JSON。
- `buildMemoryArray`：将记忆转为JSON数组。

### `app/src/main/java/me/rerere/rikkahub/data/ai/RequestLoggingInterceptor.kt`

OkHttp请求日志拦截器，记录请求/响应详情及耗时。
- `RequestLoggingInterceptor`：OkHttp拦截器，记录请求/响应和异常
- `intercept`：拦截请求，记录日志并返回响应
- `toMap`：将Headers转Map用于日志

### `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpConfig.kt`

定义 MCP 配置、OAuth 及工具数据模型。
- `McpCommonOptions`：通用配置（启用、名称、头、工具、OAuth）
- `McpOAuthState`：OAuth 2.1 授权状态
- `McpTool`：工具定义（启用、名称、描述、输入模式、需审批）
- `McpServerConfig`：服务器配置（SSE 与 HTTP 传输实现）
- `serverUrl`：获取服务器 URL 的扩展属性

### `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpManager.kt`

MCP 子系统入口，协调配置、OAuth、连接、工具调用。
- `McpManager`：统一入口，管理 session 和 OAuth
- `syncingStatus`：服务器连接状态流
- `getAllAvailableTools`：获取启用工具列表
- `callTool`：调用工具并转换结果为 UI 内容
- `addClient`/`removeClient`：增减服务器连接
- `startAuthorization`/`cancelAuthorization`/`clearAuthorization`：OAuth 授权控制
- `sessionRegistry`：连接状态机
- `oauthCoordinator`：OAuth 协议协调

### `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthCallback.kt`

定义 MCP OAuth 回调 URI 常量与启动授权的方法
- `MCP_OAUTH_REDIRECT_URI`：OAuth 回调 URI 常量
- `launchOAuthAuthorization`：用 Chrome Custom Tabs 打开授权 URL

### `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthClient.kt`

实现 MCP OAuth 2.1 授权客户端，含发现、注册、PKCE 流程。
- `McpOAuthClient`：OAuth 客户端，封装元数据发现、DCR、授权码与刷新令牌
- `discoverProtectedResource`/`discoverAuthorizationServer`：发现受保护资源/授权服务器元数据
- `registerClient`：动态客户端注册
- `generatePkce`/`buildAuthorizationUrl`：生成 PKCE 与授权请求 URL
- `exchangeCode`/`refreshToken`：授权码换取令牌/刷新令牌
- `canonicalResource`：规范化资源 URI

### `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpOAuthCoordinator.kt`

协调 MCP OAuth 授权、令牌刷新与持久化。
- `McpOAuthCoordinator`：管理授权流程与令牌生命周期
- `startAuthorization`：启动授权
- `ensureFreshToken`：刷新过期令牌
- `needsAuthorization`：判断是否需要授权
- `persistOAuthState`：持久化OAuth状态
- `looksUnauthorized`：检测未授权错误

### `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpSessionRegistry.kt`

管理 MCP 服务器的生命周期、连接、重连和工具同步。

- `McpSessionRegistry`：核心会话注册表，管理连接与重连
- `McpStatusStore`：暴露连接状态流（Idle/Connecting/Connected/Error）
- `McpSession`：内部会话，持有客户端、配置和重连状态
- `connectSession`：执行连接、认证与工具同步
- `syncTools`：从服务器获取工具列表并合并到本地配置
- `requestReconnect`：指数退避重连，最大重试 5 次
- `callTool`：调用指定工具，自动处理 OAuth 刷新
- `McpConnectionKey`：比较连接参数，决定是否触发重连
- 常量：`MAX_RECONNECT_ATTEMPTS=5`, `BASE_RECONNECT_DELAY_MS=1000`, `MAX_RECONNECT_DELAY_MS=30000`

### `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/McpStatus.kt`

定义 MCP 连接会话状态。
- `McpStatus`：连接状态密封类
- `Idle`：空闲
- `Connecting`：连接中
- `Connected`：已连接
- `Reconnecting`：重连中(含 attempt, maxAttempts)
- `Error`：错误(含 message, detail)
- `Error.from`：从异常构建错误
- `NeedsAuthorization`：需授权
- `Authorizing`：授权中

### `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/transport/SseClientTransport.kt`

SSE 客户端 MCP 传输实现（已全部注释）。  
- `SseClientTransport`：基于 Ktor SSE 的 MCP 传输类，支持重连、端点发现与消息收发。

### `app/src/main/java/me/rerere/rikkahub/data/ai/mcp/transport/StreamableHttpClientTransport.kt`

MCP Streamable HTTP 客户端传输（已注释，Kotlin实现）
- `StreamableHttpError`：自定义错误
- `StreamableHttpClientTransport`：传输实现类
- `start` / `send` / `close`：传输生命周期
- `terminateSession`：销毁会话
- `startSseSession` / `handleInlineSse`：SSE处理
- 常量 `MCP_SESSION_ID_HEADER` 等

### `app/src/main/java/me/rerere/rikkahub/data/ai/prompts/CompressPrompt.kt`

定义 AI 对话压缩的默认提示词模板。
- `DEFAULT_COMPRESS_PROMPT`：含占位符的压缩提示模板，指导压缩对话摘要。

### `app/src/main/java/me/rerere/rikkahub/data/ai/prompts/LearningMode.kt`

定义学习模式下的AI系统提示词。
- `LEARNING_MODE_PROMPT`：学习模式系统提示词，指导AI作为引导式教师，禁止直接给出答案。

### `app/src/main/java/me/rerere/rikkahub/data/ai/prompts/OcrPrompt.kt`

定义 OCR 提示常量，用于文字识别与版面描述。
- `DEFAULT_OCR_PROMPT`：OCR 助手提示，要求提取文字并描述非文本元素的位置与关系。

### `app/src/main/java/me/rerere/rikkahub/data/ai/prompts/Suggestion.kt`

定义AI建议回复的默认提示词模板。
- `DEFAULT_SUGGESTION_PROMPT`：生成用户回复建议的提示词，包含规则占位符。

### `app/src/main/java/me/rerere/rikkahub/data/ai/prompts/TitleSummary.kt`

文件职责：定义用于生成对话标题的默认AI提示词模板。  
关键符号：  
- `DEFAULT_TITLE_PROMPT`：包含占位符的标题生成提示词常量。

### `app/src/main/java/me/rerere/rikkahub/data/ai/prompts/Translation.kt`

定义翻译任务的AI提示模板常量。
- `DEFAULT_TRANSLATION_PROMPT`：含占位符的翻译提示模板，用于指导AI将源文本翻译为目标语言。

### `app/src/main/java/me/rerere/rikkahub/data/ai/tools/ConversationTools.kt`

定义 AI 助手查询对话历史的工具集。
- `createConversationTools`：返回 `recent_chats` 和 `conversation_search` 两个 Tool

### `app/src/main/java/me/rerere/rikkahub/data/ai/tools/KnowledgeTools.kt`

用于创建知识空间工具（状态/搜索/读取/摄取）的工厂函数。
- `createKnowledgeTools`：创建四个知识工具，依赖workspaceId与仓库服务。

### `app/src/main/java/me/rerere/rikkahub/data/ai/tools/MemoryTools.kt`

构建记忆管理工具列表，支持创建/编辑/归档/恢复/删除记忆。
- `buildMemoryTools`：生成记忆工具的工厂函数。

### `app/src/main/java/me/rerere/rikkahub/data/ai/tools/SearchTools.kt`

定义 AI 搜索与抓取工具，含多引擎聚合、结果合并与 URL 标准化。  
- `createSearchTools`：构建 `search_web` 和 `scrape_web` 工具  
- `selectedSearchServices`：解析启用的搜索服务  
- `executeMultiSearch`：并发执行多搜索  
- `aggregateSearchResults`：去重合并结果项  
- `normalizeSearchResultUrl`：URL 规范化  
- `SearchToolResult`、`SearchToolResultItem`：结果数据类

### `app/src/main/java/me/rerere/rikkahub/data/ai/tools/SkillsTools.kt`

根据启用技能创建 use_skill 工具，供AI代理加载技能指令或文件。

- `createSkillTools`：根据启用技能集生成 Tool 列表，含系统提示、参数定义与执行逻辑。

### `app/src/main/java/me/rerere/rikkahub/data/ai/tools/TextReplacers.kt`

为 workspace_edit_file 提供多级文本替换策略。
- `TextReplacer`：匹配器接口
- `ReplaceTextResult`：替换结果
- `WorkspaceEditReplacers`：默认替换器列表
- `replaceText`：执行替换的主函数
- `ExactReplacer`：精确匹配
- `LineTrimmedReplacer`：忽略行首尾空白匹配
- `BlockAnchorReplacer`：块锚点匹配（首尾行锚定）

### `app/src/main/java/me/rerere/rikkahub/data/ai/tools/WorkspaceTools.kt`

定义AI工作区文件读写、编辑、shell工具及其审批逻辑。
- `WorkspaceToolDefaultApprovals`：默认审批映射
- `createWorkspaceTools`：创建工具列表
- `createReadFileTool`：读文件工具
- `createWriteFileTool`：写文件工具
- `createEditFileTool`：编辑文件工具
- `createShellTool`：执行shell工具

### `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/AskUserTool.kt`

定义 `ask_user` 工具，用于 AI 向用户提问澄清需求。
- `buildAskUserTool`：构建并返回 `Tool` 实例，含提问参数、最大问题数、选项规则及 HITL 执行逻辑。

### `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/CalendarTool.kt`

构建日历查询与创建工具的 Android 工具函数集。
- `buildCalendarQueryTool`：构建日历查询工具
- `buildCalendarCreateTool`：构建日历创建工具
- `hasCalendarReadPermission`：检查读权限
- `hasCalendarWritePermission`：检查写权限
- `getDefaultCalendarId`：获取默认日历ID
- `parseCalendarTime`：解析时间字符串

### `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/ClipboardTool.kt`

定义剪贴板读写工具，供AI调用。
- `buildClipboardTool`：构建剪贴板读写Tool实例。

### `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/GitHubIssueTool.kt`

定义 AI 工具：提交 GitHub issue（需求/缺陷）。
- `FEATURE_TYPE`/`BUG_TYPE`：issue 类型常量
- `GitHubIssueEnvironment`：设备环境信息
- `GitHubIssueDraft`：issue 草稿数据
- `buildGitHubIssueTool`：构建 Tool 实例
- `createGitHubIssueDraft`：从输入生成草稿并校验

### `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/JavascriptTool.kt`

构建 JavaScript 代码执行工具，使用 QuickJS 引擎。

- `buildJavascriptTool`：返回 `eval_javascript` 工具，执行 JS 代码并捕获日志与结果。

### `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/LocalToolOption.kt`

定义本地工具选项的密封类，用于序列化/反序列化。
- `LocalToolOption`：密封类，包含各工具单例
- `JavascriptEngine`：JS引擎选项
- `TimeInfo`：时间信息选项
- `Clipboard`：剪贴板选项
- `Tts`：TTS选项
- `AskUser`：询问用户选项
- `ScreenTime`：屏幕时间选项
- `Calendar`：日历选项

### `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/LocalTools.kt`

管理本地AI工具，根据选项动态提供工具集。
- `LocalTools`：按需提供本地工具容器。
- `getTools(options)`：返回匹配`LocalToolOption`的工具列表。
- 工具属性：`javascriptTool`、`clipboardTool`、`ttsTool`、`calendarQueryTool`等9个本地工具。

### `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/ScreenTimeTool.kt`

定义获取屏幕使用时间的AI工具，支持时间范围查询与权限检查。
- `buildScreenTimeTool`：构建屏幕时间工具，处理权限、参数及结果。
- `computeForegroundTime`：基于事件计算各应用真实前台时长。
- `resolveLauncherPackages`：获取桌面包名，用于排除。
- `resolveAppName`：获取应用显示名称。
- `parseUsageTime`：解析多种时间格式字符串。
- `LOOKBACK_MS`：回看窗口常量(12h)。

### `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/TextToSpeechTool.kt`

构建本地文本转语音工具，供 AI 调用朗读文本。
- `buildTextToSpeechTool`：返回 Tool 实例，注入事件总线、TTS 管理器与设置存储。

### `app/src/main/java/me/rerere/rikkahub/data/ai/tools/local/TimeInfoTool.kt`

构建获取当前本地日期时间的 AI 工具。
- `buildTimeInfoTool`：返回返回本地时间信息的 Tool 实例

### `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Base64ImageToLocalFileTransformer.kt`

将消息中的 Base64 图片转换为本地文件。
- `Base64ImageToLocalFileTransformer`：输出转换器，实现 `OutputMessageTransformer`
- `onGenerationFinish`：遍历消息，调用 `FilesManager` 转换 Base64 图片部分

### `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/DocumentAsPromptTransformer.kt`

将上传的文档消息转换为内嵌文件内容的 `<UploadFile>` 提示块，供 AI 直接读取。

- `DocumentAsPromptTransformer`：实现`InputMessageTransformer`，遍历消息将文档部分转为文本提示。
- `transform`：在IO线程处理消息，对每个`UIMessagePart.Document`读取内容并生成`<UploadFile>`标签。
- `readDocumentContent`：根据MIME类型调用对应解析器（PDF/DOCX/PPTX/EPUB）或直接读文本。
- `resolveWorkspacePath`：返回文件在workspace中的挂载路径（`/upload/文件名`）。

### `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/OcrTransformer.kt`

将消息中的图片转换为 OCR 文本，支持缓存。
- `OcrTransformer`：实现输入消息转换，图片转文字
- `transform`：处理消息，将图片部分替换为 OCR 结果
- `performOcr`：执行 OCR 并缓存，调用 AI 模型

### `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/PlaceholderTransformer.kt`

定义占位符上下文与替换逻辑，将系统信息注入 AI 消息。
- `PlaceholderCtx`：占位符解析所需上下文
- `PlaceholderProvider`：提供占位符信息接口
- `PlaceholderInfo`：占位符显示名与解析器
- `PlaceholderBuilder`：DSL 构建占位符集合
- `buildPlaceholders`：构建占位符映射的 DSL 函数
- `DefaultPlaceholderProvider`：默认占位符，含日期、设备、电池等
- `PlaceholderTransformer`：实现消息转换，替换文本中的占位符

### `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTransformer.kt`

根据 Assistant 配置注入模式提示词和词书条目到消息列表。
- `PromptInjectionTransformer`：InputMessageTransformer 入口对象
- `transformMessages`：纯函数，收集注入并应用到消息
- `collectInjections`：收集启用的 ModeInjection 和触发的 Lorebook 条目
- `applyInjections`：按位置（BEFORE/AFTER_SYSTEM, TOP/BOTTOM_OF_CHAT, AT_DEPTH）插入注入
- `findSafeInsertIndex`：避免在 USER→带工具的 ASSISTANT 之间插入

### `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/RegexOutputTransformer.kt`

对助手输出消息应用正则替换。
- `RegexOutputTransformer`：输出消息转换器，应用正则替换
- `visualTransform`：替换助手消息中文本和推理部分

### `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/TemplateTransformer.kt`

文件职责：使用 Pebble 模板转换消息文本，并加载助理模板。

- `TemplateTransformer`：实现 `InputMessageTransformer`，用模板包装消息文本
- `AssistantTemplateLoader`：实现 `Loader<String>`，从 `SettingsStore` 读取助理模板

### `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/ThinkTagTransformer.kt`

将AI消息中的`thinking`标签内容提取为`Reasoning`部分显示。

- `ThinkTagTransformer`：实现`OutputMessageTransformer`，将助手消息文本中的`thinking...response`片段转换为`UIMessagePart.Reasoning`，并剥离文本。
- `visualTransform`：处理流式消息，根据是否有`response`结束标签设置`finishedAt`为`null`或当前时间。
- `onGenerationFinish`：生成完成时调用，统一设置`finishedAt`为当前时间。

### `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/TimeReminderTransformer.kt`

在长时间间隔消息前注入 `<time_reminder>` 提醒 AI 当前时间。  
- `TimeReminderTransformer`：实现 `InputMessageTransformer`，按 `enableTimeReminder` 开关处理  
- `applyTimeReminder`：遍历消息，超过 1 小时间隔则插入时间提醒  
- `buildTimeReminderMessage`：生成带星期、时间、间隔的 `<time_reminder>` 用户消息  
- `formatGap`：将秒数格式化为“min/h/d”  
- `TIME_GAP_THRESHOLD_SECONDS`：阈值 3600 秒

### `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt`

定义消息转换器接口与扩展函数，处理输入/输出UIMessage转换链。
- `TransformerContext`：转换上下文，含模型、助手、设置等
- `MessageTransformer`：基础转换接口
- `InputMessageTransformer`：输入消息转换接口
- `OutputMessageTransformer`：输出转换接口，含visualTransform和onGenerationFinish
- `transforms`：列表扩展函数，链式调用转换器
- `visualTransforms`：仅执行视觉转换
- `onGenerationFinish`：生成完成时转换

### `app/src/main/java/me/rerere/rikkahub/data/ai/transformers/WorkspaceReminderTransformer.kt`

将 workspace 绑定信息与 Shell 状态注入系统提示。
- `WorkspaceReminderTransformer`：实现输入消息转换，注入 workspace 系统提示
- `buildWorkspacePrompt`：根据 workspace 实体和状态生成提示文本
- `UIMessage.appendText`：将文本追加到消息的文本部分（私有扩展）

### `app/src/main/java/me/rerere/rikkahub/data/datastore/DefaultProviders.kt`

定义AI服务商默认配置及自动模型ID  
- `DEFAULT_AUTO_MODEL_ID`：默认自动模型UUID  
- `DEFAULT_PROVIDERS`：内置AI服务商列表（OpenAI, Gemini, DeepSeek等）

### `app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/data/datastore/PreferencesStore.kt",
  "summary": "应用设置持久化层，基于 Preferences DataStore 统一管理 UI 主题、AI 模型、提供商、助手、搜索、TTS/ASR、备份等全部配置，提供默认值合并、数据迁移、引用清理与流式监听。",
  "symbols": [
    {
      "name": "SettingsStore",
      "kind": "class",
      "description": "核心设置读写入口，管理流式配置与持久化更新"
    },
    {
      "name": "Settings",
      "kind": "data class",
      "description": "包含所有用户配置项的数据模型"
    },
    {
      "name": "settingsFlow",
      "kind": "val",
      "description": "提供设置状态的 StateFlow，供 UI 层观察"
    },
    {
      "name": "update",
      "kind": "suspend fun",
      "description": "将设置对象或部分更新持久化到 DataStore"
    },
    {
      "name": "getCurrentAssistant",
      "kind": "fun",
      "description": "获取当前选中的助手配置"
    },
    {
      "name": "getCurrentChatModel",
      "kind": "fun",
      "description": "获取当前聊天使用的模型对象"
    },
    {
      "name": "resolveSearchServiceSelection",
      "kind": "internal fun",
      "description": "解析并验证搜索服务选择，确保引用有效"
    },
    {
      "name": "DEFAULT_ASSISTANTS",
      "kind": "internal val",
      "description": "预置助手列表，用于初始化默认助手"
    }
  ]
}

### `app/src/main/java/me/rerere/rikkahub/data/datastore/RecommendedProviders.kt`

定义推荐AI提供商列表，用于设置页推荐Sheet展示。
- `RECOMMENDED_PROVIDERS`：推荐提供商列表，含AiHubMix和随想AI网关。

### `app/src/main/java/me/rerere/rikkahub/data/datastore/migration/PreferenceStoreV1Migration.kt`

数据迁移类，将旧版MCP服务器JSON的type字段从全限定类名改为短键值。
- `PreferenceStoreV1Migration`：执行版本升级至1并清理旧字段
- `migrateMcpServersJson`：解析JSON，将type全限定类名映射为`sse`/`streamable_http`

### `app/src/main/java/me/rerere/rikkahub/data/datastore/migration/PreferenceStoreV2Migration.kt`

数据迁移，将偏好存储从v1升级到v2，重命名消息部分类型并递归迁移助理预设。

- `PreferenceStoreV2Migration`：实现 `DataMigration<Preferences>`，检查版本并迁移 `ASSISTANTS` 键。
- `migrateAssistantsJson`：解析助理JSON，递归迁移 `presetMessages` 中部分 `type` 值。
- `migratePartsArray`：递归映射 `parts` 数组中的 `type` 字段，并处理嵌套 `output`。
- `partTypeMapping`：旧类型名到新小写类型名的映射表。

### `app/src/main/java/me/rerere/rikkahub/data/datastore/migration/PreferenceStoreV3Migration.kt`

数据迁移：从 V2 助手内嵌快捷消息升级为全局快捷消息与 ID 引用
- `PreferenceStoreV3Migration`：执行 V2→V3 迁移，迁移助手快捷消息并合并全局消息
- `migrateAssistantsQuickMessages`：提取助手旧快捷消息，生成 UUID，返回迁移后的助手 JSON 和全局消息列表

### `app/src/main/java/me/rerere/rikkahub/data/datastore/migration/PreferenceStoreV4Migration.kt`

数据迁移：移除已停用的“volcengine_agent_plan”类型提供者及相关配置。
- `PreferenceStoreV4Migration`：执行 V4 迁移，清理提供者、模型、助手。
- `RetiredProviderCleanup`：封装清理后的 JSON 与被移除 id 集合。
- `removeRetiredProviderEntries`：从 JSON 中移除 retired 提供者及其模型。
- `clearRetiredAssistantModels`：移除助手引用已退役模型 id 的字段。

### `app/src/main/java/me/rerere/rikkahub/data/datastore/migration/SettingsJsonMigrator.kt`

对备份 settings.json 执行与 DataStore 迁移等价的 JSON 修复。
- `SettingsJsonMigrator`：迁移工具单例
- `migrate`：应用多版本迁移，返回修复后的 JSON

### `app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/data/db/AppDatabase.kt",
  "summary": "定义 Room 数据库核心类，声明对话、记忆、媒体等 10 个实体与 2 个手动迁移，暴露各 DAO 接口供上层访问，并注册 TokenUsage 类型转换器。",
  "symbols": [
    {
      "name": "AppDatabase",
      "kind": "abstract class",
      "description": "Room 数据库主入口，提供所有 DAO 的获取方法"
    },
    {
      "name": "conversationDao",
      "kind": "abstract fun",
      "description": "返回 ConversationDAO 实例"
    },
    {
      "name": "memoryDao",
      "kind": "abstract fun",
      "description": "返回 MemoryDAO 实例"
    },
    {
      "name": "genMediaDao",
      "kind": "abstract fun",
      "description": "返回 GenMediaDAO 实例"
    },
    {
      "name": "messageNodeDao",
      "kind": "abstract fun",
      "description": "返回 MessageNodeDAO 实例"
    },
    {
      "name": "managedFileDao",
      "kind": "abstract fun",
      "description": "返回 ManagedFileDAO 实例"
    },
    {
      "name": "favoriteDao",
      "kind": "abstract fun",
      "description": "返回 FavoriteDAO 实例"
    },
    {
      "name": "workspaceDao",
      "kind": "abstract fun",
      "description": "返回 WorkspaceDAO 实例"
    }
  ]
}

### `app/src/main/java/me/rerere/rikkahub/data/db/DatabaseMigrationTracker.kt`

跟踪数据库迁移状态，暴露空闲/迁移中状态流。

- `MigrationState`：密封类，含 `Idle`、`Migrating`
- `DatabaseMigrationTracker`：单例，管理迁移状态
- `state`：`StateFlow<MigrationState>`
- `onMigrationStart`/`onMigrationEnd`：更新状态

### `app/src/main/java/me/rerere/rikkahub/data/db/dao/ConversationDAO.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/data/db/dao/ConversationDAO.kt",
  "summary": "Room DAO 接口，定义对话实体的所有数据库操作。提供按置顶/时间排序的流式查询、分页数据源、按助手/文件夹/搜索过滤，以及增删改、置顶、文件夹移动、游标分页同步与统计功能，是对话列表页和数据同步的核心数据层。",
  "symbols": [
    {
      "name": "ConversationDAO",
      "kind": "interface",
      "description": "对话表的 Room DAO，封装全部查询与变更操作"
    },
    {
      "name": "getAll",
      "kind": "function",
      "description": "返回按置顶和时间排序的全部对话流"
    },
    {
      "name": "getAllPaging",
      "kind": "function",
      "description": "提供全部对话的分页数据源"
    },
    {
      "name": "getConversationsOfAssistant",
      "kind": "function",
      "description": "获取指定助手下的对话流"
    },
    {
      "name": "searchConversationsPaging",
      "kind": "function",
      "description": "按标题搜索并返回分页数据源"
    },
    {
      "name": "insert",
      "kind": "function",
      "description": "插入新对话"
    },
    {
      "name": "updatePinStatus",
      "kind": "function",
      "description": "更新对话置顶状态"
    },
    {
      "name": "resetConversationNodes",
      "kind": "function",
      "description": "重置对话节点内容为空数组"
    }
  ]
}

### `app/src/main/java/me/rerere/rikkahub/data/db/dao/FavoriteDAO.kt`

Room DAO，管理收藏的增、删、查及存在性判断。

- `FavoriteDAO`：收藏数据访问接口
- `upsert`：插入或替换收藏
- `listAll`：返回全部收藏流
- `listByType`：按类型返回收藏流
- `getRefKeysByType`：获取某类型的所有引用键
- `getFavoriteNodeIdsOfConversation`：获取会话内收藏节点ID
- `getByRefKey`：按引用键查询收藏
- `existsByRefKey`：判断引用键是否存在
- `deleteByRefKey`：按引用键删除
- `deleteById`：按ID删除

### `app/src/main/java/me/rerere/rikkahub/data/db/dao/FolderDAO.kt`

定义 Room DAO 接口，操作 conversation_folder 表。
- `getFoldersOfAssistant`：按助手 ID 查询文件夹流
- `getFolderById`：按 ID 查单个
- `insert`：插入或替换
- `update`：更新实体
- `rename`：按 ID 重命名
- `delete`：删除实体
- `deleteById`：按 ID 删除

### `app/src/main/java/me/rerere/rikkahub/data/db/dao/GenMediaDAO.kt`

对 `genmediaentity` 表的数据访问对象，提供分页、插入、删除操作。
- `GenMediaDAO`：Room DAO 接口
- `getAll()`：返回分页数据源
- `getAllMedia()`：返回所有媒体列表
- `insert()`：插入一条媒体记录
- `delete()`：删除指定 ID 的媒体

### `app/src/main/java/me/rerere/rikkahub/data/db/dao/ManagedFileDAO.kt`

管理文件数据库的DAO接口，提供增删改查及流式列表。
- `ManagedFileDAO`：文件表DAO
- `insert`/`update`：写入/更新文件
- `getById`/`getByPath`：按ID/路径查询
- `listByFolder`：按文件夹流式返回列表
- `deleteById`/`deleteByPath`/`deleteByFolder`：按条件删除

### `app/src/main/java/me/rerere/rikkahub/data/db/dao/MemoryDAO.kt`

管理 MemoryEntity 的 Room DAO 接口，提供 CRUD 与多条件查询。
- `MemoryDAO`：包含插入、更新、删除及按助手/状态/种类查询方法，支持 Flow 和挂起函数。

### `app/src/main/java/me/rerere/rikkahub/data/db/dao/MessageNodeDAO.kt`

Room DAO 管理 message_node 表，提供消息增删改查与 Token 统计。
- `MessageNodeDAO`：定义表 CRUD 及统计查询。
- `getTokenStats`：扩展函数，统计 Token 使用总量。
- `getMessageCountPerDay`：扩展函数，按日统计用户消息数。
- `MessageTokenStats`：数据类，Token 统计结果。
- `MessageDayCount`：数据类，每日消息数。
- `TOKEN_STATS_SQL`：常量，Token 统计原始 SQL。

### `app/src/main/java/me/rerere/rikkahub/data/db/dao/PhoneWorkDAO.kt`

手机工作会话与事件的 Room DAO 接口。
- `PhoneWorkDAO`：管理会话和事件表
- `observeActiveSessions`：观察活跃会话流
- `replaceSessions`：事务替换所有会话
- `upsertEvents`：批量更新事件
- `maxSeq`/`eventsAfter`：支持增量事件同步

### `app/src/main/java/me/rerere/rikkahub/data/db/dao/WorkspaceDAO.kt`

定义工作区表的数据访问操作（Room DAO）
- `listFlow`：按更新时间降序流式获取列表
- `getById`：按 ID 查询
- `upsert`：插入或替换
- `getAll`：获取全部（挂起）
- `updateShellStatus`：更新 shell 状态
- `deleteById`：按 ID 删除

### `app/src/main/java/me/rerere/rikkahub/data/db/entity/ConversationEntity.kt`

Room 数据库会话实体，映射 conversations 表。

- `ConversationEntity`：会话数据类，包含 ID、标题、节点、时间戳等字段。

### `app/src/main/java/me/rerere/rikkahub/data/db/entity/FavoriteEntity.kt`

定义收藏夹的Room实体，映射到favorites表。
- `FavoriteEntity`：收藏夹数据实体类

### `app/src/main/java/me/rerere/rikkahub/data/db/entity/FolderEntity.kt`

会话文件夹实体，存储助手内分组。
- `FolderEntity`：会话文件夹表实体，含id、assistantId等字段。

### `app/src/main/java/me/rerere/rikkahub/data/db/entity/GenMediaEntity.kt`

Room 实体，映射 AI 生成/编辑媒体的记录。
- `GenMediaEntity`：Room 实体，字段含路径、模型 ID、提示词、类型、源路径等。
- `TYPE_IMAGE_GENERATION`：常量，图片生成类型。
- `TYPE_IMAGE_EDIT`：常量，图片编辑类型。

### `app/src/main/java/me/rerere/rikkahub/data/db/entity/ManagedFileEntity.kt`

管理文件数据库实体，映射 `managed_files` 表。
- `ManagedFileEntity`：Room 实体，含文件元数据字段。

### `app/src/main/java/me/rerere/rikkahub/data/db/entity/MemoryEntity.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/data/db/entity/MemoryEntity.kt",
  "summary": "定义 Room 持久化记忆实体，将 Memory 模型映射为数据库表，支持按助手、类型、状态查询，并记录证据、置信度等元数据，用于长期记忆存储与检索。",
  "symbols": [
    {
      "name": "MemoryEntity",
      "kind": "data class",
      "description": "表示助手记忆的 Room 实体表"
    }
  ]
}

### `app/src/main/java/me/rerere/rikkahub/data/db/entity/MessageNodeEntity.kt`

Room实体，定义消息节点表，关联会话表。
- `MessageNodeEntity`：消息节点数据类，含外键关联会话，存储消息JSON与选择索引。

### `app/src/main/java/me/rerere/rikkahub/data/db/entity/PhoneWorkEntities.kt`

定义手机端工作会话和事件的Room实体。
- `PhoneWorkSessionEntity`：工作会话表实体
- `PhoneWorkEventEntity`：工作事件表实体

### `app/src/main/java/me/rerere/rikkahub/data/db/entity/WorkspaceEntity.kt`

定义Room实体映射workspaces表，含工具审批覆盖与转换方法。
- `WorkspaceEntity`：Room实体，字段id/name/root/shellStatus/createdAt/updatedAt/lastAccessAt/toolApprovals
- `toolApprovalOverrides()`：解析toolApprovals为Map<String, Boolean>
- `toWorkspace()`：转换为Workspace领域模型

### `app/src/main/java/me/rerere/rikkahub/data/db/fts/MessageFtsManager.kt`

管理消息全文搜索索引与执行中文分词查询。
- `MessageSearchResult`：搜索结果数据类
- `MessageSearchSort`：排序方式枚举
- `MessageFtsManager`：索引与搜索管理器
- `indexConversation`：重建对话全文索引
- `deleteConversation`：删除指定对话索引
- `deleteAll`：清空全部索引
- `search`：关键词搜索并返回结果
- `extractFtsText`：从消息提取待索引文本

### `app/src/main/java/me/rerere/rikkahub/data/db/fts/SimpleDictManager.kt`

管理 assets 中简单词典的解压与版本控制，确保本地词典目录最新。

- `SimpleDictManager`：词典管理单例
- `extractDict`：解压词典到 files 目录，版本匹配时直接返回路径
- `copyAssetDir`：递归复制 assets 目录
- `DICT_ASSET_DIR`/`VERSION_FILE`/`CURRENT_VERSION`：定义词典资源路径、版本文件和当前版本号

### `app/src/main/java/me/rerere/rikkahub/data/db/migrations/MigrationUtils.kt`

数据库迁移工具：将旧版消息部分的类型名映射为简化字符串。
- `partTypeMapping`：旧类型名到新类型名的映射表
- `migrateMessagesJson`：迁移消息JSON字符串
- `migrateMessagesElement`：迁移消息数组元素
- `migratePartsArray`：递归迁移parts数组

### `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_11_12.kt`

数据库迁移：将会话节点数据由JSON字段拆分为独立 message_node 表。
- `Migration_11_12`：Room 迁移对象，版本 11→12，创建 message_node 表并迁移节点数据。

### `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_13_14.kt`

Room数据库迁移13→14，转换UIMessagePart类型至@SerialName。
- `Migration_13_14`：迁移13→14，遍历消息节点并更新JSON中的UIMessagePart类型字段。

### `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_14_15.kt`

数据库迁移，从14到15，创建收藏表及其索引。
- `Migration_14_15`：Room迁移对象，执行建表及索引，并记录迁移事件。

### `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_15_16.kt`

Room 数据库 15→16 迁移：合并工具调用节点到助手消息。
- `Migration_15_16`：Room Migration 实例，实现节点与工具消息迁移逻辑。

### `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_16_17.kt`

Room 数据库迁移：删除 ConversationEntity 表的 truncate_index 列。
- `Migration_16_17`：自动迁移规格，删除指定列。

### `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_22_23.kt`

从 `workspaces` 表删除 `shell_enabled` 列的 Room 自动迁移。
- `Migration_22_23`：带 `@DeleteColumn` 注解的自动迁移规范

### `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_32_33.kt`

数据库迁移：删除停用的 Happy/App Server/Relay Work 相关表。
- `Migration_32_33`：Room 迁移，删除 21 个旧表（如 codex_*、work_*）。

### `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_33_34.kt`

数据库迁移：从版本33升级到34，为phone_work_sessions表添加archived_at列。  
- `Migration_33_34`：Room迁移对象，执行ALTER TABLE添加archived_at TEXT列。

### `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_6_7.kt`

数据库迁移 6→7：将 `messages` 列转为 `nodes` 列，格式从 `UIMessage` 迁移到 `MessageNode`。
- `Migration_6_7`：执行迁移的 Room Migration 对象

### `app/src/main/java/me/rerere/rikkahub/data/db/migrations/Migration_8_9.kt`

Room 自动迁移，从 ConversationEntity 表删除 usage 列。
- `Migration_8_9`：实现 AutoMigrationSpec，删除列 `usage`

### `app/src/main/java/me/rerere/rikkahub/data/event/AppEvent.kt`

定义应用内全局事件（语音、OAuth、聊天流式更新等）。
- `AppEvent`：密封事件基类
- `Speak`：语音文本
- `OpenUsageAccessSettings`：打开使用权限设置
- `McpOAuthCallback`：MCP OAuth回调
- `ChatGenerationUpdate`：聊天流式更新
- `ChatGenerationEnded`：聊天完成/失败

### `app/src/main/java/me/rerere/rikkahub/data/event/AppEventBus.kt`

事件总线，基于 `SharedFlow` 分发应用事件，支持挂起与缓冲丢弃发送。
- `AppEventBus`：事件总线单例类
- `events: SharedFlow<AppEvent>`：外部订阅的事件流
- `emit(event)`：挂起发送事件
- `tryEmit(event): Boolean`：非挂起发送，缓冲满时丢弃并返回 false

### `app/src/main/java/me/rerere/rikkahub/data/export/ExportHooks.kt`

提供数据导出/导入的 Composable 钩子与状态管理。
- `ExporterState<T>`：管理导出，含序列化、分享、写文件
- `ImporterState<T>`：管理导入，从文件读取并解析
- `rememberExporter`：Composable，创建 ExporterState
- `rememberImporter`：Composable，创建 ImporterState

### `app/src/main/java/me/rerere/rikkahub/data/export/ExportSerializer.kt`

定义数据导出/导入接口及序列化器，支持模式注入与世界书格式转换。
- `ExportData`：导出数据容器（版本、类型、JSON数据）
- `ExportSerializer<T>`：通用序列化器接口，提供DefaultJson实例
- `ModeInjectionSerializer`：ModeInjection专有序列化器
- `LorebookSerializer`：世界书序列化器，兼容SillyTavern格式导入

### `app/src/main/java/me/rerere/rikkahub/data/favorite/FavoriteAdapter.kt`

定义收藏适配器接口，将目标类型转换为收藏实体。
- `FavoriteAdapter`：收藏适配器接口
- `type`：收藏类型
- `buildRefKey`：构建引用键
- `buildFavoriteEntity`：构建收藏实体

### `app/src/main/java/me/rerere/rikkahub/data/favorite/NodeFavoriteAdapter.kt`

节点收藏适配器，负责实体与引用/元数据转换。
- `NodeFavoriteAdapter`：单例，实现`FavoriteAdapter<NodeFavoriteTarget>`
- `type`：`FavoriteType.NODE`
- `buildRefKey`：生成引用键
- `buildFavoriteEntity`：创建收藏实体
- `decodeRef`：解码引用
- `decodeMeta`：解码元数据

### `app/src/main/java/me/rerere/rikkahub/data/files/FileUtils.kt`

文件工具类：生成UUID文件名、解析相对路径、从Uri获取名称/MIME，用魔数嗅探类型，压缩位图。
- `object FileUtils`：工具单例
- `buildUuidFileName`：基于显示名/MIME构造UUID文件名
- `buildRelativePath`：拼接文件夹路径和文件名
- `getRelativePathInFilesDir`：获取文件在filesDir下的相对路径
- `getFileNameFromUri`：从Uri查询显示名称
- `getFileMimeType`：从Content URI获取MIME类型
- `guessMimeType`：扩展名或魔数嗅探MIME类型
- `compressBitmapToPng`：压缩位图为PNG字节数组

### `app/src/main/java/me/rerere/rikkahub/data/files/FilesManager.kt`

管理应用文件存储、同步及聊天文件操作，提供CRUD与数据库跟踪。

- `FilesManager`：核心文件管理器，处理保存、删除、同步、聊天文件创建。
- `SyncResult`：同步结果数据类。
- `FileFolders`：定义文件夹常量。
- `saveUploadFromUri/FromBytes/Text`：便捷上传扩展函数。

### `app/src/main/java/me/rerere/rikkahub/data/files/SkillManager.kt`

管理本地技能目录的读写、解析、原子保存与清理。  
- `SkillManager`：技能文件管理器  
- `listSkills`：列出所有技能  
- `readSkillBody`：读取技能正文  
- `saveSkill`：原子保存技能  
- `deleteSkill`：删除并清理设置  
- `pruneOrphanedEnabledSkills`：清除孤儿技能引用  
- `saveSkillFilesAtomically`：原子写入多文件  
- `SkillMetadata`：技能元数据数据类  
- `SkillFrontmatterParser`：解析 YAML 前言

### `app/src/main/java/me/rerere/rikkahub/data/files/SkillPaths.kt`

文件用于安全解析技能目录和文件路径，防止路径遍历。
- `SkillPaths`：内部对象，提供路径安全解析
- `resolveSkillDir`：解析技能子目录，校验合法性
- `resolveSkillFile`：解析技能文件，确保在技能目录内

### `app/src/main/java/me/rerere/rikkahub/data/github/GitHubIssueClient.kt`

GitHub Issue 创建 API 客户端，封装鉴权与请求。
- `CreatedGitHubIssue`：Issue 响应数据类（编号、URL）
- `GitHubIssueApiException`：携带状态码的 API 异常
- `GitHubIssueClient`：创建 Issue 的客户端类
- `createIssue`：发起 Issue 创建请求的挂起函数
- `githubErrorMessage`：根据状态码生成错误消息
- `JSON_MEDIA_TYPE`：请求体 JSON 媒体类型

### `app/src/main/java/me/rerere/rikkahub/data/github/GitHubIssueCredentialStore.kt`

使用 Android KeyStore 加密持久化 GitHub Token。

- `GitHubIssueTokenProvider`：定义 `getToken()` 接口
- `GitHubIssueCredentialStore`：实现 token 存储与加密
- `saveToken`：加密并写入文件
- `getToken`：解密读取 token
- `hasToken`：检查 token 存在
- `clear`：删除凭证
- `getOrCreateKey`：生成/获取 AES 密钥

### `app/src/main/java/me/rerere/rikkahub/data/knowledge/KnowledgeSpaceService.kt`

知识库文档导入服务，处理文件规范化与导入。
- `KnowledgeSpaceService`：导入文档/上传文件
- `importDocument`：从流导入并规范化
- `importUpload`：从上传目录导入
- `normalizeKnowledgeDocument`：按扩展名/MIME提取文本
- 常量：MAX_NORMALIZE_BYTES/DOCX_MIME/PPTX_MIME/TEXT_EXTENSIONS

### `app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt",
  "summary": "定义AI助手相关的核心数据模型，包括助手配置、记忆、正则替换、提示词注入和世界书，支持个性化对话、动态注入与流式文本过滤。",
  "symbols": [
    {
      "name": "Assistant",
      "kind": "data class",
      "description": "助手配置，包含模型、记忆、工具等字段"
    },
    {
      "name": "AssistantRegex",
      "kind": "data class",
      "description": "正则替换规则，用于流式文本过滤"
    },
    {
      "name": "PromptInjection",
      "kind": "sealed class",
      "description": "提示词注入基类，定义注入属性"
    },
    {
      "name": "PromptInjection.RegexInjection",
      "kind": "data class",
      "description": "基于内容匹配的注入，世界书条目"
    },
    {
      "name": "Lorebook",
      "kind": "data class",
      "description": "管理多个正则注入的世界书"
    },
    {
      "name": "String.replaceRegexes",
      "kind": "extension function",
      "description": "根据助手规则替换字符串文本"
    },
    {
      "name": "isTriggered",
      "kind": "function",
      "description": "检查正则注入是否被上下文触发"
    },
    {
      "name": "getTriggeredInjections",
      "kind": "function",
      "description": "获取所有触发的注入并排序"
    }
  ]
}

### `app/src/main/java/me/rerere/rikkahub/data/model/Avatar.kt`

定义头像的密封数据模型，含虚拟、Emoji和图片三种类型。
- `Avatar`：头像密封类
- `Avatar.Dummy`：虚拟头像对象
- `Avatar.Emoji`：含`content`字符串的表情头像
- `Avatar.Image`：含`url`字符串的图片头像

### `app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt`

定义对话会话与消息节点的数据模型及序列化配置。
- `Conversation`：对话数据类，含消息节点、文件、助手等。
- `MessageNode`：消息节点，含多版本消息和选中索引。
- `Conversation.ofId`：工厂方法创建 Conversation。
- `MessageNode.of`：工厂方法从 UIMessage 创建 MessageNode。
- `toMessageNode()`：UIMessage 扩展转为 MessageNode。
- `collectAllParts()`：递归收集所有消息部分。
- `fileUri()`：提取部分中的本地文件 URI。

### `app/src/main/java/me/rerere/rikkahub/data/model/Favorite.kt`

收藏数据模型与预览构建工具。
- `FavoriteType`：枚举，区分节点/消息收藏，含兼容旧值的序列化名与工厂方法。
- `FavoriteMeta`：标题、副标题、预览文本。
- `NodeFavoriteRef`：对话ID与节点ID引用。
- `NodeFavoriteTarget`：完整节点收藏目标。
- `buildFavoritePreview`：从UIMessage/MessageNode提取预览文本。

### `app/src/main/java/me/rerere/rikkahub/data/model/Folder.kt`

定义会话文件夹数据模型
- `Folder`：助手内分组实体，含id、assistantId、name、sortIndex、createAt

### `app/src/main/java/me/rerere/rikkahub/data/model/Leaderboard.kt`

定义排行榜数据模型
- `LeaderboardModel`：单条排行榜条目（排名、模型名、分数等）
- `Leaderboard`：按文本/视觉分类的排行榜列表

### `app/src/main/java/me/rerere/rikkahub/data/model/Tag.kt`

用于表示标签的数据模型。  
- `Tag`：序列化数据类，含 id 与 name 属性。

### `app/src/main/java/me/rerere/rikkahub/data/profile/ProfileMaintenanceScheduler.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/data/profile/ProfileMaintenanceScheduler.kt",
  "summary": "负责根据用户配置，使用WorkManager调度定期或一次性的个人资料维护任务，支持启用/禁用、追赶执行和手动触发。",
  "symbols": [
    {
      "name": "ProfileMaintenanceScheduler",
      "kind": "class",
      "description": "调度个人资料维护工作"
    },
    {
      "name": "sync",
      "kind": "function",
      "description": "同步工作调度至WorkManager"
    },
    {
      "name": "runNow",
      "kind": "function",
      "description": "立即执行维护工作"
    }
  ]
}

### `app/src/main/java/me/rerere/rikkahub/data/profile/ProfileMaintenanceService.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/data/profile/ProfileMaintenanceService.kt",
  "summary": "用户画像维护服务，在后台根据最近对话变化，调用 AI 模型生成或更新画像候选，并依据配置进行自动应用或标记为待审核，实现对话驱动的用户画像持续演化。",
  "symbols": [
    {
      "name": "ProfileMaintenanceService",
      "kind": "class",
      "description": "协调画像维护流程，读取配置、获取对话并调用模型生成候选"
    },
    {
      "name": "ProfileMaintenanceResult",
      "kind": "data class",
      "description": "封装单次维护运行的结果统计信息"
    },
    {
      "name": "ProfileCandidate",
      "kind": "data class",
      "description": "模型生成的单个画像创建或编辑候选"
    },
    {
      "name": "ProfileMaintenanceResponse",
      "kind": "data class",
      "description": "模型返回的画像候选列表容器"
    },
    {
      "name": "parseProfileMaintenanceResponse",
      "kind": "function",
      "description": "从模型原始输出中提取并解析 JSON 响应"
    },
    {
      "name": "validateProfileCandidate",
      "kind": "function",
      "description": "校验候选的维度、内容长度、置信度和证据满足最低要求"
    }
  ]
}

### `app/src/main/java/me/rerere/rikkahub/data/profile/ProfileMaintenanceWorker.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/data/profile/ProfileMaintenanceWorker.kt",
  "summary": "WorkManager 的 CoroutineWorker，用于在后台定期执行 ProfileMaintenanceService 的维护任务，并支持失败重试。",
  "symbols": [
    {
      "name": "ProfileMaintenanceWorker",
      "kind": "class",
      "description": "后台维护任务的 Worker"
    },
    {
      "name": "doWork",
      "kind": "function",
      "description": "执行维护服务并处理重试逻辑"
    }
  ]
}

### `app/src/main/java/me/rerere/rikkahub/data/provider/WorkspaceDocumentsProvider.kt`

通过 SAF 暴露 workspace 文件目录给系统文件管理器。
- `WorkspaceDocumentsProvider`：DocumentsProvider 实现，提供 CRUD 和查询。
- `queryChildDocuments`：列出子文档。
- `openDocument`：打开文件。
- `createDocument`/`deleteDocument`/`renameDocument`：文件操作。

### `app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/data/repository/ConversationRepository.kt",
  "summary": "ConversationRepository 是会话数据仓库，封装了会话的增删改查、分页查询、搜索、置顶和文件夹归属等操作，内部组合 Room 数据库、FTS 全文索引和文件管理器，为上层提供 Flow 与挂起函数。",
  "symbols": [
    {
      "name": "ConversationRepository",
      "kind": "class",
      "description": "会话数据仓库核心类"
    },
    {
      "name": "getConversationById",
      "kind": "function",
      "description": "根据 ID 获取完整会话"
    },
    {
      "name": "insertConversation",
      "kind": "function",
      "description": "插入新会话及消息节点"
    },
    {
      "name": "updateConversation",
      "kind": "function",
      "description": "更新会话及消息节点"
    },
    {
      "name": "deleteConversation",
      "kind": "function",
      "description": "删除会话并清理文件"
    },
    {
      "name": "searchMessages",
      "kind": "function",
      "description": "全文搜索消息内容"
    },
    {
      "name": "getConversationsOfAssistantPaging",
      "kind": "function",
      "description": "分页获取助手会话列表"
    },
    {
      "name": "getPinnedConversations",
      "kind": "function",
      "description": "获取置顶会话流"
    }
  ]
}

### `app/src/main/java/me/rerere/rikkahub/data/repository/FavoriteRepository.kt`

收藏数据仓库，封装 DAO 提供收藏的增删改查及节点收藏操作。
- `FavoriteRepository`：收藏仓库类。
- `listAll()`：获取所有收藏 Flow。
- `listByType(type)`：按类型获取收藏 Flow。
- `getByRefKey(refKey)`：按引用键获取收藏。
- `existsByRefKey(refKey)`：检查引用键是否存在。
- `deleteByRefKey(refKey)`：按引用键删除。
- `deleteById(id)`：按 ID 删除。
- `upsert(entity)`：插入或更新收藏实体。
- `addNodeFavorite(target)`：添加节点收藏。
- `removeNodeFavorite(conversationId, nodeId)`：删除节点收藏。
- `isNodeFavorited(conversationId, nodeId)`：检查节点收藏状态。

### `app/src/main/java/me/rerere/rikkahub/data/repository/FilesRepository.kt`

文件仓库，封装 `ManagedFileDAO` 操作，提供文件增删改查方法。
- `FilesRepository`：文件数据仓库类
- `insert`：插入文件实体并返回带ID的实体
- `update`：更新文件实体
- `getById`：按ID查询文件
- `getByPath`：按相对路径查询文件
- `listByFolder`：按文件夹流式查询文件列表
- `deleteById`：按ID删除文件
- `deleteByPath`：按路径删除文件
- `deleteByFolder`：按文件夹删除文件

### `app/src/main/java/me/rerere/rikkahub/data/repository/FolderRepository.kt`

文件夹仓库，封装 FolderDAO/ConversationDAO 提供文件夹 CRUD。
- `FolderRepository`：文件夹数据操作
- `getFoldersOfAssistant`：按助理 ID 获取文件夹流
- `getFolderById`：按 ID 查文件夹
- `createFolder`：创建文件夹
- `renameFolder`：重命名
- `deleteFolder`：删除并清空会话归属

### `app/src/main/java/me/rerere/rikkahub/data/repository/GenMediaRepository.kt`

生成媒体数据的仓库，提供分页查询与增删操作。
- `GenMediaRepository`：封装生成媒体数据访问的仓库类
- `getAllMedia`：返回分页数据源
- `insertMedia`：插入媒体记录
- `deleteMedia`：按ID删除媒体

### `app/src/main/java/me/rerere/rikkahub/data/repository/MemoryRepository.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/data/repository/MemoryRepository.kt",
  "summary": "助手记忆数据仓库，封装 MemoryDAO 访问，提供记忆的 CRUD、状态管理、自动/手动记忆处理与提示构建，支持 Flow 和挂起函数。",
  "symbols": [
    {
      "name": "MemoryRepository",
      "kind": "class",
      "description": "记忆数据仓库，提供 CRUD 操作"
    },
    {
      "name": "getPromptMemories",
      "kind": "function",
      "description": "获取用于提示的活跃记忆"
    },
    {
      "name": "getMemoriesOfAssistantFlow",
      "kind": "function",
      "description": "获取助手记忆列表流"
    },
    {
      "name": "getGlobalMemoriesFlow",
      "kind": "function",
      "description": "获取全局记忆列表流"
    },
    {
      "name": "addMemory",
      "kind": "function",
      "description": "添加新记忆"
    },
    {
      "name": "updateContent",
      "kind": "function",
      "description": "手动更新记忆内容并锁定"
    },
    {
      "name": "addAutoProfile",
      "kind": "function",
      "description": "自动添加个人资料记忆"
    },
    {
      "name": "updateAutoProfile",
      "kind": "function",
      "description": "更新自动个人资料记忆"
    }
  ]
}

### `app/src/main/java/me/rerere/rikkahub/data/repository/WorkspaceRepository.kt`

封装工作区增删改查、文件操作与知识库管理。
- `WorkspaceRepository`：工作区仓库
- `create`：创建工作区
- `installRootfs`：安装 rootfs
- `listFiles`：列出文件
- `executeCommand`：执行命令
- `delete`：删除工作区

### `app/src/main/java/me/rerere/rikkahub/data/sync/S3Sync.kt`

负责 S3 备份与恢复，打包/解压 zip 管理数据。
- `S3Sync`：S3 备份/恢复，含测试、列表、删除备份
- `S3BackupItem`：备份条目数据类

### `app/src/main/java/me/rerere/rikkahub/data/sync/importer/ChatboxImporter.kt`

Chatbox 备份 JSON 导入器，将 Chatbox 会话转换为应用内 Conversation 与 ProviderSetting。  
- `ChatboxImporter`：单例，提供 `import`、`importStreaming` 等入口。  
- `ChatboxImportPayload`：导入结果，含 providers 与 conversations 统计。  
- `ChatboxStreamingImportResult`：流式导入结果。  
- `ChatboxSessionParseResult` / `ChatboxPartParseResult`：内部解析数据结构。

### `app/src/main/java/me/rerere/rikkahub/data/sync/importer/CherryStudioProviderImporter.kt`

从 Cherry Studio 备份 zip 解析并导入 Provider 配置。

- `CherryStudioProviderImporter`：负责从备份中提取 providers 列表
- `importProviders`：读取 zip 内 data.json，解析持久化存储，返回 `List<ProviderSetting>`
- `parseProvider`：将 JSON 对象转为 `ProviderSetting`（OpenAI/Claude/Google 等）
- `parseModels`：解析模型数组为 `List<Model>`
- `normalizeBaseUrl`：规范化 API 基础 URL
- `importedProviderKey`：生成去重 key

### `app/src/main/java/me/rerere/rikkahub/data/sync/s3/AwsSignatureV4.kt`

实现 AWS Signature V4 签名，生成 S3 请求的认证头和 URL。
- `AwsSignatureV4`：签名工具对象
- `sign`：生成签名请求的主函数
- `SignedRequest`：签名结果数据类（headers 和 url）

### `app/src/main/java/me/rerere/rikkahub/data/sync/s3/S3Client.kt`

S3 兼容存储客户端，封装对象增删查操作。
- `S3Client`：S3 操作类，依赖 `S3Config` 和 `HttpClient`。
- `putObject`：上传字节数组或文件。
- `getObject`：获取对象字节数组。
- `getObjectStream`：获取对象流。
- `downloadObjectToFile`：下载到文件。
- `deleteObject`：删除对象。
- `headObject`：获取对象元数据。
- `listObjects`：列出对象（支持分页）。
- `objectExists`：检查对象是否存在。
- `getPublicUrl`：生成公开 URL。
- `S3Object`、`S3ObjectMetadata`、`S3ListResult`：数据类。
- `S3Exception`：自定义异常。
- `File.sha256Hex`：文件 SHA-256 扩展。

### `app/src/main/java/me/rerere/rikkahub/data/sync/s3/S3Config.kt`

定义 S3 同步配置的可序列化数据模型。
- `S3Config`：S3 连接与备份配置数据类
- `BackupItem`：备份项枚举（DATABASE/FILES）
- `host`：提取端点主机名
- `isHttps`：判断是否使用 HTTPS
- `bucketUrl()`：生成桶的完整 URL

### `app/src/main/java/me/rerere/rikkahub/data/sync/webdav/WebDavClient.kt`

WebDAV客户端，封装文件上传、下载、删除等操作。
- `WebDavClient`：WebDAV客户端类，支持put/get/delete/mkcol/propfind等方法
- `WebDavResourceInfo`：资源元数据数据类
- `WebDavException`：WebDAV异常类

### `app/src/main/java/me/rerere/rikkahub/data/sync/webdav/WebDavSync.kt`

WebDAV备份恢复管理器，处理zip打包上传下载。
- `WebDavSync`：同步类，提供testConnection、backup、restore、listBackupFiles、deleteBackupFile、restoreFromLocalFile等操作
- `WebDavBackupItem`：备份项数据类（href, displayName, size, lastModified）

### `app/src/main/java/me/rerere/rikkahub/data/work/PhoneWorkApiClient.kt`

封装 Work Core API 的 HTTP 客户端，支持会话/消息/附件/流式事件。
- `PhoneWorkApiClient`：提供 runners、repos、sessions、消息、附件上传、流式事件等方法。
- `PhoneWorkApiException`：自定义 API 异常。
- `PhoneWorkStreamUpdate`：流式事件密封接口。
- `CreateSessionRequest`：创建会话请求数据类。

### `app/src/main/java/me/rerere/rikkahub/data/work/PhoneWorkCatalogStore.kt`

管理手机工作目录的本地持久化存储。
- `PhoneWorkCatalogStore`：使用 SharedPreferences 保存/加载 PhoneWorkCatalog 数据
- `load()`：从 SharedPreferences 反序列化读取目录
- `save(catalog)`：序列化并存储目录
- `PREFERENCES`/`KEY_CATALOG`：存储文件名与键常量

### `app/src/main/java/me/rerere/rikkahub/data/work/PhoneWorkCredentialStore.kt`

提供手机工作凭据的加密存储与状态管理
- `PhoneWorkCredentialStore`：使用 Android Keystore 加密保存/清除 token，并通过 `connection` 状态流暴露连接状态
- `save(baseUrl, token)`：加密保存凭据并启动追踪服务
- `clear()`：清除凭据并停止追踪服务
- `token()`：解密并返回 token
- `connection: StateFlow<PhoneWorkConnection>`：当前连接状态

### `app/src/main/java/me/rerere/rikkahub/data/work/PhoneWorkModels.kt`

定义与 PhoneWork API 交互的序列化数据类。
- `PhoneWorkRunner`：运行器信息
- `PhoneWorkRepo`：仓库信息
- `PhoneWorkSession`：会话状态
- `PhoneWorkEvent`：事件流
- `PhoneWorkAttachment`：附件
- `PhoneWorkUserMessagePayload`：用户消息
- `PhoneWorkQuestion`/`Answer`：问答
- `PhoneWorkAskPayload`：提问
- `PhoneWorkReportPayload`/`HtmlReportPayload`：报告
- `PhoneWorkRunStatePayload`：运行状态
- `PhoneWorkCatalog`：运行器/仓库目录
- `PhoneWorkConnection`：连接配置

### `app/src/main/java/me/rerere/rikkahub/data/work/PhoneWorkRepository.kt`

手机工作会话数据仓库，整合API、本地DB和实时事件流。
- `PhoneWorkRepository`：数据仓库
- `catalog`：仓库目录状态
- `observeSessions`：监听会话变化
- `createSession`：创建会话
- `sendMessage`：发送消息
- `liveEvents`：实时事件流

### `app/src/main/java/me/rerere/rikkahub/di/AppModule.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/di/AppModule.kt",
  "summary": "Koin 依赖注入模块，提供应用全局单例（JSON、高亮、事件总线、本地工具、聊天服务、WebServer 等）的定义，确保跨组件解耦和生命周期管理。"
}

### `app/src/main/java/me/rerere/rikkahub/di/DataSourceModule.kt`

数据源依赖注入模块，提供数据库、网络、AI、同步等单例。
- `dataSourceModule`：Koin 模块，定义所有数据源相关的单例依赖。

### `app/src/main/java/me/rerere/rikkahub/di/RepositoryModule.kt`

Koin DI 模块，注册所有 Repository 及 Workspace 服务单例。
- `repositoryModule`：Koin 模块，提供 ConversationRepository、FolderRepository、MemoryRepository、GenMediaRepository、FilesRepository、FavoriteRepository、WorkspaceManager、RootfsInstaller、WorkspaceRepository、KnowledgeSpaceService、FilesManager、SkillManager 单例。

### `app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/di/ViewModelModule.kt",
  "summary": "Koin依赖注入模块，集中注册应用中所有ViewModel实例及其依赖关系，支持构造函数参数传递，解决ViewModel创建与生命周期管理的问题。"
}

### `app/src/main/java/me/rerere/rikkahub/service/ChatNotificationManager.kt`

管理聊天生成时的系统通知（进度更新和完成通知）
- `ChatNotificationManager`：订阅事件，发送进度/完成通知
- `LIVE_UPDATE_NOTIFICATION_THROTTLE_MS`：通知节流间隔
- `handleGenerationUpdate`：处理流式更新，节流后发送 Live Update
- `handleGenerationEnded`：取消 Live Update，发送完成通知
- `determineNotificationContent`：根据消息部分决定状态和内容

### `app/src/main/java/me/rerere/rikkahub/service/ChatService.kt`

聊天服务：管理会话生命周期、消息生成、工具调用、翻译、标题/建议生成等。
- `ChatService`：核心聊天服务类
- `ChatError`：错误数据结构
- `ChatErrorSolution`：错误修复建议枚举
- `backgroundTextGenerationParams`：构建后台生成参数
- `sendMessage`：发送用户消息并触发补全
- `regenerateAtMessage`：基于指定消息重新生成
- `handleToolApproval`：处理工具审批
- `handleMessageComplete`：执行消息补全主流程
- `generateTitle`：自动生成对话标题
- `generateSuggestion`：生成对话建议
- `compressConversation`：压缩对话历史
- `translateMessage`：翻译消息
- `editMessage`：编辑消息版本
- `forkConversationAtMessage`：从消息处分支新对话
- `deleteMessage`：删除消息
- `stopGeneration`：停止当前生成任务

### `app/src/main/java/me/rerere/rikkahub/service/ConversationSession.kt`

管理会话状态、引用计数与空闲超时清理的会话封装类。
- `ConversationSession`：会话容器，含状态、引用计数、空闲检测与生成任务
- `acquire`/`release`：增减引用计数，控制空闲检查
- `withRef`/`withRefSuspend`：同步/异步作用域引用管理
- `setJob`：管理生成任务，结束触发空闲检查
- `cleanup`：取消任务并清理
- `IDLE_TIMEOUT_MS`：空闲超时 5 秒
- `state`/`processingStatus`：会话状态流

### `app/src/main/java/me/rerere/rikkahub/service/PhoneWorkTrackingService.kt`

后台轮询Work会话并推送通知的前台服务。
- `PhoneWorkTrackingService`：轮询跟踪服务，管理会话状态与通知
- `EXTRA_WORK_SESSION_ID`：传递会话ID的Intent键
- `start`/`stop`：控制服务启停的静态方法
- `track`：协程轮询刷新会话与事件
- `notifyEvent`：根据事件类型派发通知

### `app/src/main/java/me/rerere/rikkahub/service/WebServerService.kt`

管理 Web 服务器前台服务及通知的 Android Service。
- `WebServerService`：前台服务，处理启动/停止与状态监听
- `ACTION_START`/`ACTION_STOP`：Intent 动作常量
- `EXTRA_PORT`/`EXTRA_LOCALHOST_ONLY`：Intent 参数键
- `startForegroundCompat()`：兼容不同 API 的前台启动
- `startObservingState()`：观察状态并更新通知/停止服务

### `app/src/main/java/me/rerere/rikkahub/telemetry/AppTelemetry.kt`

定义应用遥测接口，默认空实现不记录事件。
- `AppTelemetry`：日志事件接口
- `NoOpAppTelemetry`：空实现，默认不记录

### `app/src/main/java/me/rerere/rikkahub/ui/activity/McpOAuthCallbackActivity.kt`

接收 MCP OAuth 授权回调，解析 code/state/error 并通过事件总线转发。
- `McpOAuthCallbackActivity`：透明 Activity，处理 deep link，发出 `AppEvent.McpOAuthCallback`。

### `app/src/main/java/me/rerere/rikkahub/ui/activity/SafeModeActivity.kt`

安全模式活动，崩溃后切换助手并查看日志。  
- `SafeModeActivity`：安全模式页面，提供切换助手、进入应用、复制崩溃日志。  
- `AssistantPickerSheet`：底部弹窗，支持按标签筛选并选择助手。

### `app/src/main/java/me/rerere/rikkahub/ui/activity/ShortcutHandlerActivity.kt`

处理快捷方式，请求相机权限并拍照，将照片 URI 传递给 RouteActivity。
- `ShortcutHandlerActivity`：快捷方式入口 Activity
- `onCreate`：启动时申请相机权限
- `launchCamera`：创建缓存文件并启动拍照
- `requestPermissionLauncher`：处理权限结果
- `takePictureLauncher`：拍照成功后传递 URI 并跳转

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/AsrButton.kt`

语音识别按钮组件，根据ASR状态切换图标/动画/波形。
- `AsrButton`：根据状态渲染空闲/连接/活跃界面，含颜色动画
- `AudioLevelDots`：音频振幅波形指示器，动态条形高度
- `AsrDisplayState`：内部枚举，定义 Idle/Connecting/Active 三种显示状态

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/AssistantPicker.kt`

提供助手选择器，导航抽屉项触发底部弹出列表，支持标签过滤和编辑跳转。
- `AssistantPicker`：主组件，导航抽屉项，触发选择器
- `AssistantPickerSheet`：底部弹出，列出助手，支持标签过滤
- `AssistantItem`：单个助手列表项，含编辑按钮

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/AttachmentChips.kt`

展示聊天输入附件芯片的水平滚动行。
- `MediaFileInputRow`：渲染图片/视频/音频/文档的附件芯片行，支持移除
- `AttachmentChip`：单个附件芯片，含缩略图/图标、标题和删除按钮
- `AttachmentLeadingIcon`：附件前导图标组件
- `attachmentNameFromUrl`：从URL解析显示名称，优先匹配文件管理器数据

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ChatInput.kt`

AI 聊天输入组件，集成文本/媒体粘贴、语音输入及补全功能。
- `ChatInput`：主输入区，含发送、模型选择、搜索、ASR 集成
- `TextInputRow`：输入行，处理补全、快捷消息、全屏编辑入口
- `CompletionPopup`：补全列表弹出层
- `applyCompletion`：应用补全替换文本
- `QuickMessageButton`：快捷消息选择器
- `FullScreenEditor`：全屏输入编辑器

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/CompressContextDialog.kt`

对话上下文压缩对话框，设置目标 tokens、保留消息数、附加提示。
- `CompressContextDialog`：Composable 组件，接收 onDismiss 和 onConfirm 回调。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/CropLauncher.kt`

封装图片裁剪启动逻辑，结合 UCrop 处理裁剪结果。
- `useCropLauncher`：可组合函数，返回裁剪启动器与启动裁剪函数，支持宽高比、自由裁剪等选项。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ExtensionContent.kt`

AI 扩展内容列表 UI 组件，展示模式注入、知识库、技能、快捷消息等可开关项。

- `ModeInjectionsContent`：渲染模式注入列表，支持选中/开关/管理按钮。
- `LorebooksContent`：渲染知识库列表，支持选中/开关/管理按钮。
- `SkillsContent`：渲染技能列表，支持启用/开关/管理按钮。
- `QuickMessagesContent`：渲染快捷消息列表，支持选中/开关。
- `ManageButton`：内部管理按钮组件。
- `ExtensionEmptyState`：空状态提示组件。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/FilesPicker.kt`

提供AI聊天附件、工作区及扩展的配置与选择UI。
- `FilesPicker`：AI聊天附件、工作区、MCP和扩展入口
- `ChatAttachmentActions`：原生附件操作行（拍照/图片/视频/音频/文件）
- `ImagePickButton`：图片选择按钮
- `TakePicButton`：拍照按钮
- `VideoPickButton`：视频选择按钮
- `AudioPickButton`：音频选择按钮
- `FilePickButton`：文件选择按钮

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/McpPicker.kt`

MCP 服务器选择器 UI 组件，支持按钮和列表项两种触发方式。
- `McpPickerButton`：图标按钮触发 MCP 选择器底部弹窗
- `McpPickerListItem`：列表项触发 MCP 选择器底部弹窗
- `McpPicker`：服务器列表及开关，管理助手关联的 MCP

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ModelList.kt`

模型选择器UI组件，提供模型列表、筛选、收藏及拖拽排序功能。
- `ModelListState`：管理模型选择状态（可见性、当前模型等）
- `rememberModelListState`：创建并记忆 ModelListState
- `ModelSelector`：模型选择器入口，含清除按钮
- `ModelListSheet`：底部弹出模型列表
- `ModelTypeTag`：显示模型类型标签
- `ModelModalityTag`：显示输入/输出模态图标
- `ModelAbilityTag`：显示工具/推理能力标签

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/NativeChatAttachmentSheet.kt`

AI 聊天附件选择底部弹窗，处理拍照、文件选择、裁剪与权限。  
- `NativeChatAttachmentSheet`：附件选择 Composable 组件，统一管理拍照、图片/视频/音频/文件选取、裁剪、权限及文件验证。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ProviderBalanceText.kt`

显示AI服务商余额，带缓存和图标。
- `ProviderBalanceText`：带缓存和余额图标的余额文本组件

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/ReasoningPicker.kt`

推理级别选择器，包含按钮、底部弹窗和刻度条。
- `ReasoningButton`：触发推理级别选择器
- `ReasoningPicker`：底部弹窗，提供滑块和刻度选择
- `ReasoningScale`：可点击的刻度条，展示所有级别
- `ReasoningIcon`：根据级别显示对应图标
- `label`扩展：获取级别的本地化文本

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/SearchPicker.kt`

AI 对话中的搜索方式选择器 UI，支持内置搜索与第三方服务切换。
- `SearchPickerButton`：主按钮，触发底部弹出搜索选择器。
- `SearchPicker`：根据模型能力展示内置搜索或应用搜索设置。
- `AppSearchSettings`：显示搜索服务列表和启用开关。
- `BuiltInSearchSetting`：切换模型内置搜索的开关卡片。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/WorkspaceCwdPicker.kt`

工作区目录选择器底部弹窗组件，支持浏览、选择与重置 CWD。
- `WorkspaceCwdPickerSheet`：Composable 函数，展示工作区目录列表并处理选择。
- `toAbsolutePath`：相对路径转绝对路径。
- `fromAbsolutePath`：绝对路径转相对路径。
- `WORKSPACE_PREFIX`：工作区根路径常量。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/WorkspaceSelectSheet.kt`

选择助手工作区的底部抽屉组件。
- `WorkspaceSelectSheet`：展示工作区列表，支持选择、不绑定和管理导航。
- `WorkspaceSelectRow`：可选项行，显示名称、状态和选中图标。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/completion/ChatCompletionProvider.kt`

定义聊天补全的数据模型与提供者接口。
- `ChatCompletionContext`：补全上下文（文本与选区）
- `ChatCompletionList`：补全结果，含替换范围与项列表
- `ChatCompletionItem`：单个补全项（标签、插入文本等）
- `ChatCompletionProvider`：补全提供者接口，定义`id`与`complete`方法

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/completion/WorkspaceCompletionProvider.kt`

为AI聊天提供workspace文件路径补全。
- `WorkspaceCompletionProvider`：文件补全
- `complete`：生成补全
- `loadEntries`：加载缓存条目
- 常量`MAX_COMPLETION_ITEMS`等：配置

### `app/src/main/java/me/rerere/rikkahub/ui/components/ai/completion/WorkspaceIgnoreMatcher.kt`

处理工作区路径的 ignore 规则匹配器，支持 gitignore 语法。

- `WorkspaceIgnoreMatcher`：管理规则列表，提供 `isIgnored` 判断
- `Rule`：单条 gitignore 规则，含 pattern、regex、negated 等
- `normalizeWorkspacePath`：统一路径为 `/` 分隔并去除首尾斜杠
- `relativeToBaseOrNull`：计算相对路径，若不在 base 下则返回 null
- `toGitignoreRegex`：将 gitignore 模式转为正则
- `REGEX_SPECIAL_CHARS`：需转义的正则字符集

### `app/src/main/java/me/rerere/rikkahub/ui/components/easteregg/EmojiBurst.kt`

动画粒子爆发组件，接收emoji列表并生成物理模拟粒子。
- `EmojiBurstHost`：主组件，触发emoji爆发动画
- `EmojiParticle`：粒子数据类
- `BurstRequest`：爆发请求数据类

### `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessage.kt`

聊天消息UI组件，处理消息展示、操作交互与内容渲染。
- `ChatMessage`：渲染单条消息，包含头像、气泡、操作按钮与动画。
- `MessagePartsBlock`：根据消息部分类型渲染内容（文本、媒体、工具调用等）。

### `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageActions.kt`

聊天消息操作按钮和底部菜单组件。
- `ChatMessageActionButtons`：消息操作按钮（复制/重新生成/TTS/翻译/更多）
- `ChatMessageActionsSheet`：底部菜单（选择复制/编辑/分享/收藏/删除等）

### `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageAvatar.kt`

定义聊天消息中用户和助手头像的 Composable 组件。
- `ChatMessageUserAvatar`：显示用户头像及昵称
- `ChatMessageAssistantAvatar`：根据助手配置显示头像或模型图标

### `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageBranch.kt`

消息分支选择器，用于在多个消息版本间切换。
- `ChatMessageBranchSelector`：Composable 函数，渲染左右箭头及当前索引/总数，支持前后切换。

### `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageCopySheet.kt`

显示消息文本的底部复制面板，支持选择与全量复制。
- `ChatMessageCopySheet`：Composable，底部弹出复制界面，含取消、全选复制、文本选择容器。

### `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageCot.kt`

将消息部分分组为思考块和内容块，用于渲染顺序。
- `ThinkingStep`：思考步骤密封接口
- `ReasoningStep`：推理步骤
- `ToolStep`：工具调用步骤
- `MessagePartBlock`：消息块密封接口
- `ThinkingBlock`：连续思考步骤的块
- `ContentBlock`：非思考部分的内容块
- `groupMessageParts()`：分组函数

### `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageEditedFiles.kt`

显示消息中已编辑文件芯片列表，支持导出和分享。
- `EditedFilesList`：渲染已编辑文件 FlowRow，点击弹出底部面板提供导出/分享操作。
- `resolveWorkspacePath`：解析路径返回存储区域和相对路径。

### `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageNerdLine.kt`

显示消息的技术统计（token 用量、速度、耗时）
- `ChatMessageNerdLine`：展示 token 用量、生成速度、耗时
- `StatsItem`：图标+文本的统计项组件

### `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageReasoning.kt`

推理步骤卡片的 UI 组件，支持折叠/预览/展开状态及流式动画。  
- `ReasoningCardState`：卡片展开状态枚举（Collapsed/Preview/Expanded）。  
- `ChainOfThoughtScope.ChatMessageReasoningStep`：主入口 Composable，渲染推理步骤卡片。  
- `ReasoningState`：管理展开状态与耗时。  
- `rememberReasoningState`：记住并驱动状态更新。  
- `ReasoningContent`：推理内容渲染，含渐变遮罩与滚动。  
- `ReasoningTitle`：动画标题文本。

### `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTools.kt`

渲染聊天消息中的工具调用步骤，含审批、问答、拒绝对话框。
- `ChatMessageToolStep`：通用工具步骤UI，处理渲染、审批、预览
- `AskUserToolStep`：ask_user工具交互式问答流程
- `AskUserQuestionBody`：单个问题的选项、自由文本、讨论切换
- `ToolDenyReasonDialog`：拒绝原因输入对话框
- `AskUserQuestion`：问答数据类
- `AskQState`：问题状态枚举
- `ASK_USER_TOOL_NAME`：常量"ask_user"

### `app/src/main/java/me/rerere/rikkahub/ui/components/message/ChatMessageTranslation.kt`

实现翻译语言选择弹窗和可折叠翻译文本展示。
- `LanguageSelectionDialog`：底部弹窗选择翻译目标语言或清除翻译
- `CollapsibleTranslationText`：可折叠翻译卡片，含加载动画和 markdown 渲染

### `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/BuiltinToolUIs.kt`

定义内置工具 UI 渲染器集合，每个对象实现 ToolUIRenderer。
- `MemoryToolUI`：记忆增删改查 UI
- `SearchWebToolUI`：网络搜索 UI
- `ScrapeWebToolUI`：网页抓取 UI
- `GetTimeInfoToolUI`：获取时间 UI
- `ClipboardToolUI`：剪贴板读写 UI
- `TextToSpeechToolUI`：文本转语音 UI
- `UseSkillToolUI`：技能调用 UI
- `RecentChatsToolUI`：最近聊天 UI
- `ConversationSearchToolUI`：对话搜索 UI
- `GetScreenTimeToolUI`：屏幕使用时间 UI
- `CalendarQueryToolUI` / `CalendarCreateToolUI`：日历查询/创建 UI

### `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/GitHubIssueToolUI.kt`

为 GitHub Issue 工具提供 Compose UI 渲染（图标、标题、摘要）。
- `GitHubIssueToolUI`：实现 ToolUIRenderer，渲染提交 Issue 的图标、标题及状态摘要。

### `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/KnowledgeToolUIs.kt`

文件职责：定义知识库工具状态、搜索、读取、摄入的 UI 渲染组件
- `KnowledgeStatusToolUI`：渲染知识库初始化状态和文档计数
- `KnowledgeSearchToolUI`：渲染搜索查询及匹配结果摘要
- `KnowledgeReadToolUI`：渲染文件读取的文本内容
- `KnowledgeIngestToolUI`：渲染文件摄入操作标题

### `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/ToolUI.kt`

定义工具调用UI渲染器接口与注册表，提供默认预览。
- `ToolUIContext`：工具调用渲染上下文
- `ToolUIRenderer`：工具渲染器接口
- `ToolUIRegistry`：渲染器注册表
- `DefaultToolPreview`：默认工具详情预览

### `app/src/main/java/me/rerere/rikkahub/ui/components/message/tools/WorkspaceToolUIs.kt`

工作空间工具的UI渲染器：编辑、读取、写入文件及Shell执行结果的摘要与详情展示。

- `EditFileToolUI`：渲染workspace_edit_file工具，显示diff统计与视图
- `ReadFileToolUI`：渲染workspace_read_file工具，展示文件内容预览
- `WriteFileToolUI`：渲染workspace_write_file工具，展示待写入内容
- `ShellToolUI`：渲染workspace_shell工具，显示退出码与输出
- `languageOf`：根据路径扩展名推断语法高亮语言

### `app/src/main/java/me/rerere/rikkahub/ui/components/nav/BackButton.kt`

返回按钮组件，点击导航回退栈。
- `BackButton`：可组合返回按钮，点击执行 `popBackStack()`。

### `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/DiffView.kt`

渲染 unified diff 文本，按行前缀着色，支持横向滚动和折叠。
- `DiffView`：Composable，渲染 unified diff，可限制行数、隐藏文件头。
- `DiffLine`：私有 Composable，按 `+`/`-`/`@@` 等前缀着色单行。
- `DiffStats`：数据类，统计增删行数。
- `parseDiffStats`：解析 diff 字符串，返回 `DiffStats`。
- `DiffAddedColor`、`DiffRemovedColor`：新增/删除行颜色常量。

### `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/HighlightCodeBlock.kt`

代码块高亮展示组件，支持折叠、预览、复制、行号、导出。
- `HighlightCodeBlock`：主代码块组件
- `CodeBlockWithLineNumbersWrapped`：行号+自动换行
- `CodeBlockDefault`：默认代码显示
- `HighlightCodeActions`：操作栏（复制/保存/预览）
- `CodeBlockPreview`：HTML/SVG内联预览
- `HighlightCodeVisualTransformation`：高亮视觉转换
- `buildCodePreviewHtml`：构建预览HTML
- `COLLAPSE_LINES`、`PREVIEWABLE_LANGUAGES`：常量

### `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/LatexText.kt`

渲染 LaTeX 公式的 Compose 组件及工具函数。  
- `assumeLatexSize`：估算公式尺寸  
- `LatexText`：Composable 渲染 LaTeX，失败回退普通文本  
- `getLatexDrawable`：创建 JLatexMathDrawable 对象  
- `splitLatex`：按宽度拆分公式为多段 Drawable  
- `LatexDrawable`：渲染单个 LaTeX Drawable

### `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Markdown.kt`

Markdown 渲染 Composable，支持 GFM、表格、LaTeX 等
- `MarkdownBlock`：核心 Composable，渲染 Markdown 内容
- `THINKING_REGEX`：匹配思考块的正则常量
- `HeaderStyle`：提供标题样式与间距的对象

### `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownNew.kt`

Markdown 渲染组件，将 Markdown 文本解析为 HTML 并通过 Compose 展示。
- `MarkdownNew`：主 Composable，接收 Markdown 内容并渲染
- `HtmlBlockElement`：按 HTML 标签分发渲染块级元素
- `appendHtmlInlineElement`：构建内联 AnnotatedString
- 支持解析表格、代码块、数学公式、引用、列表等

### `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MarkdownWeb.kt`

构建Markdown预览HTML，注入颜色和内容。
- `buildMarkdownPreviewHtml`：读取模板，替换颜色占位符与Base64编码的Markdown。

### `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/MathBlock.kt`

渲染行内与块级 LaTeX 数学公式的可组合组件。

- `MathInline`：行内 LaTeX 渲染
- `MathBlock`：居中可横向滚动的块级 LaTeX 渲染

### `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/Mermaid.kt`

显示 Mermaid 图表并支持导出及全屏预览。
- `Mermaid`：Composable，渲染 Mermaid 图表。
- `MermaidInterface`：JS 接口，处理导出图片。
- `buildMermaidHtml`：构建图表 HTML，应用主题颜色。

### `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/SimpleHtmlBlock.kt`

将 HTML 字符串解析为 Compose 富文本组件。
- `SimpleHtmlBlock`：入口，解析 HTML 并渲染。
- `RenderNode`：递归渲染节点，处理文本、元素。
- `RenderList`：渲染有序/无序列表。
- `RenderDetails`：可折叠细节块。
- `RenderImage`：异步图片支持缩放。
- `RenderProgress`：进度条。
- `RenderTable`：表格。
- `buildAnnotatedStringFromElement`：构建带样式的字符串。
- `parseInlineStyle`/`parseColor`/`parseFontWeight`：解析内联样式。

### `app/src/main/java/me/rerere/rikkahub/ui/components/richtext/ZoomableAsyncImage.kt`

可点击放大查看的异步图片组件。
- `ZoomableAsyncImage`：异步加载图片，点击弹出全屏预览。

### `app/src/main/java/me/rerere/rikkahub/ui/components/table/DataTable.kt`

自定义表格布局组件，支持横向滚动、行内等高与列宽自适应。
- `DataTable`：多列表格布局，可设表头/行/斑马纹/列宽范围/拉伸等
- `CellBox`：私有辅助组件，提供单元格样式容器
- `DataTablePreview`：展示示例的预览函数

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/AIIcon.kt`

定义AI图标组件，根据名称自动显示SVG或文本头像，含SiliconFlow品牌图标。
- `AIIcon`：渲染SVG，支持颜色和加载动画
- `AutoAIIcon`：按名称自动选图标，无匹配时退化文本头像
- `SiliconFlowPowerByIcon`：按暗/亮模式显示品牌图标
- `computeAIIconByName`：通过名称计算图标路径
- `rememberAvatarShape`：获取加载中头像形状

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/BackupReminderCard.kt`

备份提醒卡片组件，到期时显示提醒并支持关闭。
- `BackupReminderCard`：根据备份配置显示提醒卡片，可点击和关闭。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/BitmapComposer.kt`

将任意 Composable 渲染为 Bitmap 的工具类。
- `LocalExportContext`：标记导出上下文的 CompositionLocal
- `BitmapComposer`：通过 `composableToBitmap` 将 Compose 内容绘制为 Bitmap

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/CardGroup.kt`

一个卡片组组件，以 DSL 方式构建圆角卡片列表项。
- `CardGroup`：可组合卡片组，接收标题和内容 DSL。
- `CardGroupScope`：DSL 接口，提供 `item` 方法添加列表项。
- `CardGroupDsl`：DSL 标记注解。
- `CardGroupListItem`：私有组件，渲染单个带圆角动画的列表项。
- `CardGroupItem`：数据类，存储列表项配置。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ChainOfThought.kt`

展示思考步骤时间线卡片，支持折叠与展开。  
- `ChainOfThought`：主组件，渲染步骤列表及折叠控制。  
- `ChainOfThoughtScope`：提供步骤构建方法的作用域接口。  
- `ChainOfThoughtStep`：声明非受控步骤。  
- `ControlledChainOfThoughtStep`：声明受控步骤。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ConfirmDialog.kt`

一个可组合的确认对话框组件，封装 Material3 AlertDialog。
- `RikkaConfirmDialog`：显示带确认/取消按钮的对话框，支持自定义内容。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/DotLoading.kt`

显示一个脉冲透明度动画的加载点组件。
- `DotLoading`：带动画透明度的圆形加载指示器

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/Emoji.kt`

表情选择器组件，支持搜索、分类、皮肤变体选择。
- `EmojiPicker`：主选择器，集成搜索栏、分类标签与表情网格，支持长按选择变体。
- `EmojiItem`：单个表情项，支持点击与长按。
- `EmojiModifierPicker`：弹出皮肤色调选择器。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ErrorCard.kt`

显示错误卡片列表，支持自动消失、复制错误、跳转模型设置。
- `ErrorCardsDisplay`：展示错误列表，含清除全部按钮
- `ErrorCard`：单错误卡片，5秒自动消失，可复制、导航至设置

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/Export.kt`

导出对话框组件，提供文件导出和分享两个选项。  
- `ExportDialog`：泛型对话框，接受ExporterState<T>，调用导出到文件或分享操作。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ExtensionSelector.kt`

助手扩展选择器，含4个选项卡页（快速消息/模式注入/知识库/技能）。
- `ExtensionSelector`：可翻页的扩展选择组件，接收助手/设置/会话等参数并回调更新。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/Favicon.kt`

显示网站favicon图标及重叠排列组件。
- `Favicon`：根据URL加载并显示单个favicon图标
- `FaviconRow`：去重后重叠排列最多3个favicon图标

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/FloatingWindow.kt`

```markdown
提供悬浮窗 Composable，管理窗口生命周期与可见性。
- `FloatingWindow`：管理悬浮窗的显示/隐藏
```

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/Form.kt`

表单行组件，封装标签、描述、内容区与尾部。
- `FormItem`：Composable 表单行布局，支持标签、描述、内容和尾部插槽

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/Greeting.kt`

定义一个基于当前时间动态显示问候文本的 Composable 组件。
- `Greeting`：根据小时展示不同问候语

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ImagePreviewDialog.kt`

显示全屏图片预览对话框，支持图片下载。
- `ImagePreviewDialog`：全屏图片预览对话框，支持下载图片到本地。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/Input.kt`

提供 Material3 数字输入 Composable，支持多种数字类型验证。
- `OutlinedNumberInput`：外框样式数字输入框
- `NumberInput`：填充样式数字输入框
- `isValidNumberInput`：校验数字字符串格式

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/JsonTree.kt`

渲染 JSON 树的可折叠 Compose 组件，支持语法高亮与字符串点击查看。
- `JsonTree`：可组合，接收 JSON 元素、展开层级和点击回调
- `JsonNode`：按节点类型分发渲染
- `JsonObjectNode`：对象节点，可折叠
- `JsonArrayNode`：数组节点，可折叠
- `JsonPrimitiveNode`：原始值节点，高亮显示
- `KeyText`/`ValueText`：渲染键与值文本

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/KeepScreenOn.kt`

屏幕常亮控件，进入时保持屏幕，离开时恢复。
- `KeepScreenOn`：Composable 组件，添加 FLAG_KEEP_SCREEN_ON 标志。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ListSelectableItem.kt`

可选的列表项组件，带复选框和内容，支持多选状态管理。
- `ListSelectableItem`：可组合函数，渲染带复选框的列表行，根据 `selectedKeys` 控制选中并触发 `onSelectChange`。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/QRCode.kt`

显示二维码的 Composable 组件。
- `QRCode`：传入文本、尺寸、颜色，渲染 ZXing 二维码图片。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/RabbitLoading.kt`

自定义加载指示器，根据设置切换兔子动画或Material3圆圈。
- `RabbitLoadingIndicator`：Composable，通过`useAppIconStyleLoadingIndicator`配置，显示兔子动画或`ContainedLoadingIndicator`。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/Select.kt`

可复用的Material3下拉选择器Composable组件。
- `Select`：泛型下拉选择框，支持自定义选项文本、前置图标、展开/收起状态。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ShareSheet.kt`

分享AI提供者配置的底部弹窗，支持二维码和系统分享。
- `ShareSheet`：展示分享弹窗，含二维码和系统分享按钮。
- `ShareSheetState`：管理弹窗显示状态与当前提供者。
- `rememberShareSheetState`：创建并记住ShareSheetState。
- `ProviderSetting.encodeForShare`：编码提供者配置为分享字符串。
- `decodeProviderSetting`：解码分享字符串还原配置。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/StickyHeader.kt`

为 sticky header 提供统一文本样式的 Composable 组件。
- `StickyHeader`：渲染固定标题，应用次要色标题小字体样式，包裹内容。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/Switch.kt`

自定义 Compose 开关组件，支持多尺寸、颜色和动画。
- `SwitchSize`：开关尺寸枚举 (Small/Medium/Large)
- `Switch`：可组合开关，支持选中、尺寸、颜色、禁用等
- `SwitchDimensions`：私有数据类，定义轨道和拇指的尺寸参数
- `SwitchPreview`：预览函数，展示各种状态和尺寸的开关

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/TTSController.kt`

TTS语音播放控制及速度调节悬浮窗。
- `TTSController`：悬浮窗主控，绑定播放状态，可见性联动。
- `PlayPauseButton`：播放/暂停切换，双进度环。
- `FastForwardButton`：快进 5 秒。
- `SpeedButton`：倍速循环切换（0.8x/1.0x/1.2x/1.5x）。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/Tag.kt`

定义可点击的标签组件，支持五种颜色类型。
- `TagType`：枚举五种标签类型
- `Tag`：可组合标签组件，支持点击和自定义内容
- `TagPreview`：预览函数

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/TagList.kt`

```markdown
标签输入组件，展示已选标签并提供添加对话框，支持选择已有或创建新标签。
- `TagsInput`：可组合函数，管理标签选择与创建。
```

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/TextArea.kt`

多行文本输入组件，支持文件导入和全屏编辑。
- `TextArea`：显示带标签、导入按钮和全屏触发的多行输入框
- `FullScreenTextEditor`：全屏编辑对话框，保存后同步回状态

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ToggleSurface.kt`

可切换外观的点击Surface，根据选中状态动态改变内容颜色
- `ToggleSurface`：可组合函数，提供透明背景的可点击Surface，内部文字颜色根据`checked`切换为primary或onSurface

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/Tooltip.kt`

封装 Material3 提示框的 Composable 组件。
- `Tooltip`：提示框组件，封装 `TooltipBox` 与 `PlainTooltip`。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/UIAvatar.kt`

显示和编辑用户头像的 Compose 组件，支持文字、图片、Emoji 和程序生成头像。
- `TextAvatar`：文字头像组件
- `UIAvatar`：完整可编辑头像组件
- `ProceduralAvatar`：基于名字的渐变头像
- `vercelAvatarColors`：名字生成颜色对
- `hslToColor`：HSL 转 Color

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/UpdateCard.kt`

显示新版本更新卡片，支持错误提示、详情展示和下载功能  
- `UpdateCard`：检查更新状态，渲染卡片、错误提示、新版本信息及详情下载底部弹窗

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/ViewText.kt`

将 Compose TextStyle 转为原生 TextView 渲染的适配组件。
- `ViewText`：Composable，用 AndroidView 渲染 TextView
- `setComposeTextStyle`：扩展函数，将 TextStyle 应用到 TextView
- `getAndroidTypefaceStyle`：映射 FontWeight/FontStyle 到 Android 样式

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/icons/DiscordIcon.kt`

定义 Discord 图标（16x16dp）的 Compose ImageVector 资源。
- `DiscordIcon`：公开的 Discord 图标 ImageVector 属性，含完整路径数据。
- `_DiscordIcon`：私有缓存变量，避免重复构建。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/icons/Heart.kt`

定义心形填充矢量图标（16dp）。
- `HeartIcon`：公共心形图标 ImageVector 属性，惰性初始化并缓存
- `_heartIcon`：私有缓存实例

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/icons/Reasoning.kt`

定义推理能力图标，提供低、中、高三种强度样式。
- `ReasoningLow`：低强度推理图标
- `ReasoningMedium`：中强度推理图标
- `ReasoningHigh`：高强度推理图标

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/icons/TencentQQIcon.kt`

定义腾讯QQ图标矢量资源（Compose ImageVector）
- `TencentQQIcon`：腾讯QQ图标，16dp 矢量图
- `_tencentQQ`：私有缓存实例

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/permission/PermissionManager.kt`

权限管理器 Composable，自动处理权限请求对话框。
- `PermissionManager`：权限管理器组件，根据状态显示说明对话框并渲染内容。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/permission/PermissionRationaleDialog.kt`

权限请求说明对话框，展示权限列表及操作按钮。
- `PermissionRationaleDialog`：弹出对话框，含权限列表、永久拒绝处理、按钮组。
- `PermissionItem`：单项权限组件，显示名称、必需标识、永久拒绝标签。

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/permission/PermissionState.kt`

```markdown
管理 Android 运行时权限请求、状态与理由对话框。
- `PermissionState`：权限状态管理核心类
- `permissionStates`：权限状态映射
- `allPermissionsGranted`：所有权限是否已授权
- `requestPermissions()`：请求未授权权限
- `refreshPermissionStates()`：强制刷新状态
- `openAppSettings()`：跳转应用设置
- `handlePermissionResult()`：处理请求结果
```

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/permission/PermissionTypes.kt`

定义权限数据类、状态枚举及预置权限实例。
- `PermissionInfo`：权限信息数据类
- `PermissionStatus`：权限状态枚举
- `PermissionResult`：单权限请求结果
- `MultiplePermissionResult`：多权限请求结果
- `PermissionCamera`：相机权限实例
- `PermissionRecordAudio`：录音权限实例
- `PermissionNotification`：通知权限（API≥33）
- `PermissionLocalNetwork`：本地网络权限（API≥37）

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/permission/README.md`

Android 权限管理库使用文档与 API 说明  
- `PermissionInfo`：权限信息数据类  
- `PermissionState`：权限状态管理类  
- `PermissionStatus`：权限状态枚举  
- `rememberPermissionState`：创建权限状态的 Composable 函数  
- `PermissionManager`：权限请求对话框管理器  
- `PermissionCheck`：根据权限状态显示内容的 Composable

### `app/src/main/java/me/rerere/rikkahub/ui/components/ui/permission/RememberPermissionState.kt`

创建并记住权限状态，处理权限请求与生命周期刷新
- `rememberPermissionState`：支持多权限/单权限请求，绑定生命周期刷新状态

### `app/src/main/java/me/rerere/rikkahub/ui/components/webview/WebView.kt`

封装 Compose WebView 组件，管理加载状态、导航、JS 接口与设置。

- `WebView`：带进度条和状态管理的 WebView Composable
- `WebViewState`：持有 URL、加载进度、导航状态等
- `WebContent`：定义 Url、Data、NavigatorOnly 三种内容
- `rememberWebViewState`：创建并记忆 WebViewState
- `MyWebChromeClient`/`MyWebViewClient`：更新进度、标题、导航状态

### `app/src/main/java/me/rerere/rikkahub/ui/components/webview/WebViewContentCache.kt`

WebView 内容缓存存储与加载，SHA-256 标识，7 天过期自动清理。
- `WebViewContentCache`：单例缓存对象
- `store(cacheDir, content): String`：存储内容，返回哈希 ID
- `load(cacheDir, id): String?`：通过 ID 加载内容
- `sha256()`：字符串 SHA-256 扩展
- `isSha256()`：校验哈希格式扩展

### `app/src/main/java/me/rerere/rikkahub/ui/context/LocalASRState.kt`

定义 Compose 本地 ASR 状态提供者。
- `LocalASRState`：提供 `CustomAsrState` 的 composition local

### `app/src/main/java/me/rerere/rikkahub/ui/context/LocalSettings.kt`

定义 Compose 静态 CompositionLocal 以提供 Settings 实例。
- `LocalSettings`：提供 Settings 实例的 CompositionLocal，未提供则报错。

### `app/src/main/java/me/rerere/rikkahub/ui/context/LocalTTSState.kt`

提供 TTS 状态的 CompositionLocal 键。
- `LocalTTSState`：CompositionLocal 访问点，默认抛错

### `app/src/main/java/me/rerere/rikkahub/ui/context/NavContext.kt`

定义导航栈控制器与选项构建器，并提供 Compose Local。
- `Navigator`：管理 backStack，实现 navigate/clearAndNavigate/popBackStack
- `NavigateOptionsBuilder`：配置 popUpTo 与 launchSingleTop
- `PopUpToBuilder`：设置 popUpTo 是否 inclusive
- `LocalNavController`：CompositionLocal 提供 Navigator

### `app/src/main/java/me/rerere/rikkahub/ui/context/SharedElement.kt`

提供 Compose 共享元素过渡 Scope 的 CompositionLocal。
- `LocalSharedTransitionScope`：CompositionLocal，在未提供时抛出错误。

### `app/src/main/java/me/rerere/rikkahub/ui/context/ToasterContext.kt`

为 Sonner 库提供全局 Toaster 状态的 CompositionLocal。

- `LocalToaster`：提供 ToasterState 实例，未提供时抛错。

### `app/src/main/java/me/rerere/rikkahub/ui/hooks/ASR.kt`

管理ASR状态的Composable Hook，支持多供应商切换。
- `rememberCustomAsrState`：Composable Hook，返回`CustomAsrState`实例
- `CustomAsrState`：接口，定义ASR状态流、启动/停止/清理
- `CustomAsrStateImpl`：私有实现，管理ASR控制器与音频焦点，按设置切换供应商

### `app/src/main/java/me/rerere/rikkahub/ui/hooks/AvatarShape.kt`

头像形状生成钩子，根据加载状态返回动画六边形或圆形。
- `rememberAvatarShape`：加载中返回旋转六边形，否则返回圆形。

### `app/src/main/java/me/rerere/rikkahub/ui/hooks/ChatInputState.kt`

管理聊天输入状态，包括文本、附件、编辑消息。
- `ChatInputState`：输入状态类
- `clearInput()`：清空输入
- `setContents()`：设置编辑内容
- `getContents()`：获取合并内容
- `addImages/addVideos/addAudios/addFiles`：添加附件
- `shouldDeleteFileOnRemove()`：判断是否删除临时文件

### `app/src/main/java/me/rerere/rikkahub/ui/hooks/ColorMode.kt`

管理颜色模式偏好的 Composable 钩子。  
- `rememberColorMode`：提供可读写颜色模式状态  
- `rememberCurrentColorMode`：获取当前颜色模式值  
- `rememberAmoledDarkMode`：AMOLED 暗色模式开关状态  
- `toColorMode`：字符串转 ColorMode 枚举  
- `COLOR_MODE_KEY`：存储键名

### `app/src/main/java/me/rerere/rikkahub/ui/hooks/Debounce.kt`

提供 Compose 防抖与节流 Hook 函数。
- `useDebounce`：防抖包装器，延迟执行
- `useThrottle`：节流包装器，间隔执行并保留最新参数

### `app/src/main/java/me/rerere/rikkahub/ui/hooks/HeroAnimation.kt`

为导航共享元素动画提供 Modifier 扩展函数。
- `heroAnimation`：Composable 扩展，基于 key 实现共享元素过渡动画。

### `app/src/main/java/me/rerere/rikkahub/ui/hooks/ImeAutoScroller.kt`

键盘弹出时自动滚动LazyList保持输入框可见。
- `ImeLazyListAutoScroller`：监听键盘高度变化，自动滚动LazyListState

### `app/src/main/java/me/rerere/rikkahub/ui/hooks/Lifecycle.kt`

跟踪当前 Lifecycle 的状态并返回可观察 State。
- `rememberAppLifecycleState`：Composable 函数，返回当前 Lifecycle.State 的 State

### `app/src/main/java/me/rerere/rikkahub/ui/hooks/PlayStore.kt`

Composable Hook，判断当前是否从 Play 商店安装。
- `rememberIsPlayStoreVersion`：返回布尔值，表示是否 Play 商店版本。

### `app/src/main/java/me/rerere/rikkahub/ui/hooks/Settings.kt`

提供用户设置响应式状态。
- `rememberUserSettingsState`：Composable 函数，返回 `State<Settings>`

### `app/src/main/java/me/rerere/rikkahub/ui/hooks/SharedPreferences.kt`

提供 Compose 观察 SharedPreferences 的钩子与扩展函数。
- `rememberSharedPreferenceString`：获取字符串偏好状态
- `rememberSharedPreferenceBoolean`：获取布尔偏好状态
- `Context.writeStringPreference`：写字符串偏好
- `Context.readStringPreference`：读字符串偏好
- `Context.writeBooleanPreference`：写布尔偏好
- `Context.readBooleanPreference`：读布尔偏好
- `SharedPreferences.getStringFlowForKey`：字符串偏好流
- `SharedPreferences.getBooleanFlowForKey`：布尔偏好流

### `app/src/main/java/me/rerere/rikkahub/ui/hooks/TTS.kt`

提供自定义 TTS 状态管理，支持多提供商朗读、分块与播放控制。
- `rememberCustomTtsState`：Composable 函数，创建并配置 TTS 状态实例。
- `CustomTtsState`：定义 TTS 控制接口，暴露播放状态与操作方法。
- `CustomTtsStateImpl`：内部实现，封装 TTS 控制器与状态流。

### `app/src/main/java/me/rerere/rikkahub/ui/hooks/UseAssistant.kt`

管理当前 AI 助手的选择状态与持久化。
- `rememberAssistantState`：Composable 工厂，创建 AssistantState。
- `AssistantState`：持有当前助手及设置更新回调。
- `currentAssistant`：当前选中的助手。
- `setSelectAssistant`：切换助手并持久化到 Settings。

### `app/src/main/java/me/rerere/rikkahub/ui/hooks/UseEditState.kt`

管理 Compose 编辑状态的钩子，提供打开/确认/取消操作。
- `useEditState`：创建编辑状态实例
- `EditState`：编辑状态接口
- `EditStateContent`：渲染编辑界面的扩展函数

### `app/src/main/java/me/rerere/rikkahub/ui/modifier/Clickable.kt`

文件职责：定义 Modifier 的 Composable 扩展 onClick，简化点击事件配置。
- `Modifier.onClick`：扩展函数，为 Modifier 添加点击回调，带 enabled 参数和按钮角色。

### `app/src/main/java/me/rerere/rikkahub/ui/modifier/Shimmer.kt`

提供 Compose Shimmer 加载动画 Modifier。
- `Modifier.shimmer`：为 Composable 添加闪烁加载效果

### `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/AssistantPage.kt`

助手管理页面：搜索、标签过滤、拖拽排序、创建/克隆/删除助手。
- `AssistantPage`：助手列表页，含搜索、标签过滤、拖拽排序、创建/操作菜单。
- `AssistantTagsFilterRow`：标签过滤行，支持拖拽排序。
- `AssistantCreationSheet`：创建助手底部弹窗。
- `AssistantItem`：助手卡片，显示名称、记忆、标签。
- `AssistantActionSheet`：操作菜单（克隆、删除）。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/AssistantVM.kt`

管理助手设置的 ViewModel，提供增删复制及记忆查询。
- `AssistantVM`：管理助手数据的 ViewModel
- `settings`：当前设置状态流
- `updateSettings`：更新设置
- `addAssistant`：添加助手
- `removeAssistant`：删除助手并清理文件、记忆、会话
- `copyAssistant`：克隆助手
- `getMemories`：合并全局与作用域记忆流

### `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantBasicPage.kt`

助理详情的基本设置页面（Compose UI）。
- `AssistantBasicPage`：助理详情页，绑定ViewModel并展示基础设置表单。
- `AssistantBasicContent`：内部组合项，渲染头像、名称、标签、工作区、模型、温度、TopP、上下文长度、流式输出、推理预算、最大令牌、背景等可编辑控件。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantDetailPage.kt`

显示助手详情页，含导航入口与头部信息。
- `AssistantDetailPage`：助手详情主页面，接收id参数，展示可滚动配置卡片组。
- `AssistantHeader`：助手头部，显示头像、名称和系统提示摘要。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantDetailVM.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantDetailVM.kt",
  "summary": "该ViewModel为助手详情页提供全面状态管理，通过组合设置、记忆、标签、工作区等多数据流，支持修改助手属性、记忆内容、标签及个人资料维护配置，是详情交互的核心枢纽。",
  "symbols": [
    {
      "name": "AssistantDetailVM",
      "kind": "class",
      "description": "助手详情页状态管理ViewModel"
    },
    {
      "name": "assistant",
      "kind": "property",
      "description": "当前助手配置的流"
    },
    {
      "name": "profileMemories",
      "kind": "property",
      "description": "活跃的个人资料记忆流"
    },
    {
      "name": "update",
      "kind": "function",
      "description": "更新助手属性并清理旧文件"
    },
    {
      "name": "addMemory",
      "kind": "function",
      "description": "添加记忆（全局或作用域）"
    },
    {
      "name": "updateProfileMaintenanceConfig",
      "kind": "function",
      "description": "更新并同步个人资料维护配置"
    },
    {
      "name": "updateTags",
      "kind": "function",
      "description": "更新助手标签并清理未使用标签"
    },
    {
      "name": "workspaces",
      "kind": "property",
      "description": "工作区实体列表流"
    }
  ]
}

### `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantExtensionsPage.kt`

- `AssistantExtensionsPage`：助手扩展配置页，展示快捷消息/模式注入/知识书/技能四个标签页，支持开关选择与跳转编辑。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantImporter.kt`

导入SillyTavern角色卡（PNG/JSON）并转为Assistant。
- `AssistantImporter`：导入按钮行
- `SillyTavernImporter`：选择PNG/JSON文件导入
- `TavernCardParser`：解析接口
- `CharaCardV2Parser`/`CharaCardV3Parser`：v2/v3解析器
- `importAssistantFromUri`：按MIME读取并解析
- `parseAssistantFromJson`：根据spec字段选解析器
- `TAVERN_PARSERS`：解析器映射表

### `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantLocalToolPage.kt`

助手本地工具开关页面，管理7种本地工具启用/禁用及权限请求。
- `AssistantLocalToolPage`：页面入口，接收助手 ID 初始化 ViewModel。
- `AssistantLocalToolContent`：展示工具列表，处理权限请求与开关切换。
- `toggleLocalTool`：检查权限，更新助手本地工具集。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantMcpPage.kt`

显示助手 MCP 服务器配置的页面
- `AssistantMcpPage`：Composable 页面，接收助手 ID，展示 MCP 服务器选择器

### `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantMemoryPage.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantMemoryPage.kt",
  "summary": "该文件是助手详情页的记忆管理界面，提供记忆开关、全局记忆、上下文记忆、画像记忆（按维度）和归档记忆的查看与操作，支持画像自动维护设置，帮助用户精细化控制助手的记忆行为。",
  "symbols": [
    {"name": "AssistantMemoryPage", "kind": "Composable", "description": "助手记忆管理入口页面"},
    {"name": "AssistantMemoryContent", "kind": "Composable", "description": "记忆内容展示与操作逻辑"},
    {"name": "MemorySection", "kind": "Composable", "description": "记忆分组列表，支持添加/编辑/归档等"},
    {"name": "MemoryItem", "kind": "Composable", "description": "记忆条目卡片，显示内容与操作按钮"},
    {"name": "ProfileMaintenanceCard", "kind": "Composable", "description": "画像自动维护开关与状态卡片"},
    {"name": "ProfileMaintenanceSettingsDialog", "kind": "Composable", "description": "维护策略配置对话框"},
    {"name": "profileMaintenanceSummary", "kind": "function", "description": "生成维护状态摘要字符串"},
    {"name": "profileDimensionLabel", "kind": "function", "description": "维度ID转中文标签"}
  ]
}

### `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantPromptPage.kt`

助理提示词配置页面，管理系统提示词、消息模板、预置消息及正则替换。

- `AssistantPromptPage`：助手提示词配置页面入口
- `AssistantPromptContent`：提示词表单内容布局
- `AssistantRegexCard`：正则替换规则卡片组件

### `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/AssistantRequestPage.kt`

Assistant请求编辑页，展示与修改Assistant的自定义请求头和请求体。
- `AssistantRequestPage`：请求页顶层组合项，接收id并绑定VM
- `AssistantRequestContent`：内容组件，编辑headers和bodies

### `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/BackgroundPicker.kt`

助手聊天背景选择组件，支持相册和URL输入。
- `BackgroundPicker`：可组合项，管理背景图片设置与预览。
- 弹窗：相册/URL/移除背景选项。
- 依赖：`FilesManager`处理本地文件，`AsyncImage`显示预览。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/assistant/detail/PropertyEditor.kt`

提供自定义 HTTP 请求头和 JSON 请求体的 Compose 编辑组件。

- `CustomHeaders`：编辑列表 `List<CustomHeader>`，支持增删改。
- `CustomBodies`：编辑列表 `List<CustomBody>`，支持 JSON 值解析与语法高亮。
- `jsonLenient`：宽松 JSON 解析器配置。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/BackupPage.kt`

备份页面，含WebDav/S3/导入导出/提醒四个标签页的切换。
- `BackupPage`：备份功能主页面，集成四个子标签和重启对话框。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/BackupVM.kt`

管理备份/恢复/导入的 ViewModel，支持 WebDAV/S3/Chatbox/CherryStudio。
- `BackupVM`：核心 VM，协调备份、恢复、导入
- `ChatboxRestoreResult`：Chatbox 导入结果
- `backup`/`backupToS3`：执行备份
- `restore`/`restoreFromS3`：执行恢复
- `restoreFromChatBox`/`restoreFromCherryStudio`：外部导入
- `exportToFile`：导出备份文件

### `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/components/BackupDialog.kt`

备份恢复后提示重启应用的对话框组件  
- `BackupDialog`：无参数可组合函数，弹出重启提示对话框，点击确认按钮后调用 `exitProcess(0)` 退出应用。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/tabs/ImportExportTab.kt`

备份导出/导入与第三方应用数据导入UI选项卡。
- `ImportExportTab`：渲染备份导出、本地恢复及Chatbox/Cherry Studio导入的界面。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/tabs/ReminderTab.kt`

备份提醒设置的 Compose 标签页。
- `ReminderTab`：显示提醒开关、备份间隔选择及最后备份时间。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/tabs/S3Tab.kt`

S3 备份配置与操作界面，含设置表单、备份/恢复/测试按钮及文件列表弹窗。
- `S3Tab`：S3 备份主界面 Composable，管理配置、备份/恢复操作及文件列表。
- `BackupStatusCard`：显示上次备份时间与文件数摘要。
- `S3BackupItemCard`：单个备份文件卡片，提供删除和恢复操作。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/backup/tabs/WebDavTab.kt`

WebDAV 备份恢复设置界面，管理配置、测试连接、备份还原操作。

- `WebDavTab`：主界面，配置 WebDAV 连接与备份项，触发备份/还原
- `BackupStatusCard`：展示最近备份时间与文件数
- `WebDavBackupItemCard`：备份文件项卡片，支持删除/恢复

### `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/Background.kt`

文件职责：根据助手设置渲染聊天背景（渐变或图片+遮罩）。  
- `AssistantBackground`：Composable，按设置切换渐变背景或带透明遮罩的图片背景。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawer.kt`

聊天页面侧边抽屉 UI，含对话列表、文件夹管理、助手切换及快捷操作。
- `ChatDrawerContent`：抽屉主界面
- `DrawerActions`：搜索/历史入口
- `FolderBar`：文件夹筛选栏
- `FolderChip`：文件夹标签
- `AssistantItem`：助手列表项

### `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatDrawerVM.kt`

聊天抽屉 ViewModel，管理文件夹与对话分页列表状态。
- `ChatDrawerVM`：聊天抽屉 ViewModel
- `folders`：当前助手的文件夹列表
- `conversations`：带分隔符的会话分页数据流
- `selectedFolderId`：当前选中文件夹
- `createFolder` / `renameFolder` / `deleteFolder` / `moveConversationToFolder`：增删改文件夹及移动会话
- `saveScrollPosition` / `scrollIndex` / `scrollOffset`：保存/恢复滚动位置

### `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatList.kt`

聊天消息列表 Composable，支持普通/预览模式、消息选择、导出、跳转及错误提示。
- `ChatList`：主入口，切换模式
- `ChatListNormal`：普通列表，含选择、错误、跳转
- `ChatListPreview`：预览搜索模式
- `ChatSuggestionsRow`：快捷建议条
- `MessageJumper`：快速跳转按钮组
- `extractMatchingSnippet`：提取匹配片段
- `buildHighlightedText`：构建高亮文本

### `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatPage.kt`

聊天页面，管理导航抽屉、输入、消息列表及响应式布局。  
- `ChatPage`：聊天页入口，接收会话ID、文本、文件等，处理大屏/小屏布局  
- `ChatPageContent`：聊天内容区，整合输入、消息列表、文件选择等  
- `ChatFilesPickerSheet`：文件附件选择面板  
- `TopBar`：顶部栏，含标题编辑、菜单、新聊天按钮

### `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatSizeChecker.kt`

检测对话节点数与 token 量，超标时弹出警告对话框。

- `MESSAGE_NODE_WARNING_THRESHOLD`：节点数阈值
- `LAST_ASSISTANT_INPUT_TOKEN_WARNING_THRESHOLD`：输入 token 阈值
- `ConversationSizeInfo`：对话规模信息数据类
- `rememberConversationSizeInfo`：计算当前对话规模信息
- `ConversationSizeWarningDialog`：超标警告弹窗

### `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ChatVM.kt`

管理聊天对话状态，处理消息发送、编辑、生成、收藏及会话操作。  
- `ChatVM`：核心 ViewModel  
- `handleMessageSend`：发送消息并触发生成  
- `stopGeneration`：停止当前生成  
- `updateSettings`：更新用户设置  
- `toggleMessageFavorite`：切换消息收藏  
- `moveConversationToAssistant`：移动对话至其他助手  
- `regenerateAtMessage`：重新生成消息  
- `conversation`：当前对话流  
- `settings`：用户设置流

### `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ConversationList.kt`

Compose 对话列表组件，支持分组、置顶、长按操作菜单。
- `ConversationListItem`：定义列表项类型（日期头、置顶头、对话项）
- `ConversationList`：主列表，处理分页、滚动到当前项
- `ConversationItem`：单条对话，置顶图标、加载状态、右键菜单

### `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/ConversationSystemPromptCard.kt`

聊天页系统提示词编辑按钮，支持展开/收起编辑器。
- `ConversationSystemPromptButton`：可展开编辑系统提示词，提供保存/清除功能。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/Export.kt`

聊天消息导出为 Markdown 或图片的 UI 与逻辑
- `ChatExportSheet`：格式选择底部弹窗
- `exportToMarkdown`：导出为 Markdown 文件
- `exportToImage`：导出为图片的 suspend 函数
- `ImageExportOptions`：图片导出选项
- `ExportedChatImage`：渲染导出图片的 Composable

### `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/MeshGradientBackground.kt`

Gemini 风格动态渐变背景，底层线性渐变叠加漂移光斑。
- `MeshGradientBackground`：Composable，绘制渐变背景并容纳内容。
- `drawBlob`：绘制径向渐变柔光斑的私有扩展函数。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/NativeChatScaffold.kt`

聊天页面共享壳与顶部栏框架。
- `NativeChatScaffold`：聊天页壳，组合背景、Material3 Scaffold。
- `NativeChatTopBar`：聊天页顶栏，支持导航、标题和操作。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/NativeChatTimeline.kt`

聊天与工作对话共享的 LazyColumn 滚动容器。
- `NativeChatTimeline`：统一 Chat/Work 时间线布局，封装 LazyColumn 配置。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/chat/TTSAutoPlay.kt`

自动播放 TTS 的 Composable，监听生成完成流后按设置朗读助手消息。

- `TTSAutoPlay`：Composable，自动播放 TTS

### `app/src/main/java/me/rerere/rikkahub/ui/pages/debug/DebugPage.kt`

调试页面，含主调试、颜色预览、设置修改等选项卡。
- `DebugPage`：调试入口，含分页导航
- `MainPage`：主调试功能区（UI组件、toast、崩溃测试、设置修改）
- `ColorsPage`：展示Material3颜色令牌
- `ColorTokenItem`：单个颜色项组件
- `Color.toHexString`：颜色转十六进制字符串

### `app/src/main/java/me/rerere/rikkahub/ui/pages/debug/DebugVM.kt`

调试页 ViewModel，提供设置读写、对话计数及创建超大/批量对话测试。
- `DebugVM`：调试页 ViewModel
- `settings`：Settings 流
- `conversationCount`：对话数量状态
- `refreshConversationCount()`：刷新对话计数
- `updateSettings(settings)`：更新设置
- `createOversizedConversation(sizeMB)`：创建超大对话测试 CursorWindow
- `createConversationWithMessages(messageCount)`：按消息数创建对话

### `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/ExtensionsPage.kt`

显示扩展功能入口页面，含快捷消息、提示、技能、工作区卡片。
- `ExtensionsPage`：Composable 页面，提供导航至 QuickMessages、Prompts、Skills、Workspaces 的入口。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/PromptPage.kt`

提示词注入和 Lorebook 管理页面，包含模式注入/词条编辑的完整 UI 与交互逻辑。

- `PromptPage`：入口 Composable，分页切换模式注入和 Lorebook 标签。
- `ModeInjectionTab`：模式注入列表，支持拖拽排序、增删改、导入导出。
- `LorebookTab`：Lorebook 列表，支持增删改、导入导出，内含词条编辑。
- `useEditState`：复用编辑状态管理。
- `rememberExporter`/`rememberImporter`：导入导出序列化。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/PromptVM.kt`

管理设置数据的 ViewModel，暴露设置状态流与更新操作。
- `PromptVM`：接收 `SettingsStore` 的 ViewModel
- `settings`：`Settings` 状态流
- `updateSettings`：持久化新设置

### `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/QuickMessagesPage.kt`

快捷消息管理页面，支持列表展示与增删改操作。
- `QuickMessagesPage`：快捷消息列表页，含添加、编辑及删除逻辑。
- `QuickMessageCard`：快捷消息卡片组件。
- `EditQuickMessageDialog`：添加/编辑快捷消息的对话框。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/QuickMessagesVM.kt`

管理快捷消息的 ViewModel，提供增删改操作并同步持久化。

- `QuickMessagesVM`：快捷消息管理 ViewModel
- `settings`：设置数据流状态
- `addQuickMessage`：添加消息
- `updateQuickMessage`：更新消息
- `deleteQuickMessage`：删除消息
- `updateQuickMessages`：私有，批量更新并清理关联 ID

### `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/skills/SkillDetailPage.kt`

显示技能文件树，支持编辑、添加、删除文件。

- `SkillDetailPage`：技能详情页，管理文件树与操作
- `FileTree`：递归渲染文件/目录节点
- `FileItem`：文件项，提供编辑/删除
- `DirItem`：目录项，可展开/折叠
- `EditFileDialog`：编辑文件内容弹窗
- `AddFileDialog`：新建文件弹窗

### `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/skills/SkillDetailVM.kt`

技能详情页 ViewModel，管理文件树与读写操作。
- `SkillFile`：封装文件对象与相对路径
- `SkillFileNode`：文件树节点，含文件/目录两种子类
- `SkillDetailVM`：通过 `skillManager` 加载树、读/存/删文件，暴露 `tree` 状态流

### `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/skills/SkillsPage.kt`

技能管理页面，负责展示技能列表与导入/添加/删除操作。
- `SkillsPage`：技能管理主页
- `SkillCard`：技能卡片组件
- `SkillImportSheet`：导入方式选择底部弹窗
- `SkillImportSheetItem`：导入选项条目
- `AddSkillDialog`：手动添加技能对话框
- `ImportSkillDialog`：从GitHub导入技能对话框

### `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/skills/SkillsVM.kt`

管理技能列表与导入（本地/ZIP/GitHub）的 ViewModel
- `SkillsVM`：技能管理 ViewModel
- `skills`：技能元数据状态流
- `saveSkill`：保存单技能
- `deleteSkill`：删除技能
- `importSkillFromFile`：从 URI 导入
- `importSkillFromGitHub`：从 GitHub 仓库导入

### `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceDetailPage.kt`

工作区详情页，管理基础信息、文件、Shell和知识空间。
- `WorkspaceDetailPage`：页面入口，含顶部栏、底部导航和分页器
- `WorkspaceBasicPage`：展示基本信息、Shell安装、知识空间、工具审批
- `WorkspaceFilesPage`：文件浏览，支持区域切换、路径导航、文件操作
- `InstallRootfsDialog`：Rootfs下载安装弹窗
- `KnowledgeSpaceCard`：知识空间初始化与导入
- `WorkspaceToolApprovalCard`：工具审批开关列表
- `DEFAULT_ROOTFS_URL`：默认Ubuntu base镜像URL

### `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceDetailVM.kt`

管理扩展工作区文件浏览、终端命令与知识空间导入的 ViewModel。  
- `WorkspaceDetailVM`：处理文件操作、终端、知识空间初始化  
- `WorkspaceDetailState`：界面状态（工作区、区域、路径、文件列表等）  
- `WorkspaceTerminalState`：终端输入、运行状态与历史  
- `WorkspaceTerminalEntry`：终端历史条目（命令/结果/错误）

### `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceFileEditorPage.kt`

工作区文件编辑/预览页，FILES区可编辑保存，LINUX区只读。
- `WorkspaceFileEditorPage`：编辑/预览文本文件的Composable页面。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceFileType.kt`

定义工作区文件类型枚举及扩展名检测与分类。
- `WorkspaceFileType`：枚举(TEXT/IMAGE/OTHER)
- `detectFileType`：扩展函数，按扩展名返回类型
- `IMAGE_EXTENSIONS`/`TEXT_EXTENSIONS`：私有扩展名集合

### `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspacePage.kt`

工作区列表页面，支持创建、重命名、删除及导航详情。
- `WorkspacePage`：页面主组件，绑定ViewModel。
- `WorkspaceVM`：管理工作区数据与操作。
- `EmptyWorkspaceState`：空列表占位。
- `WorkspaceCard`：单个工作区条目卡片。
- `EditWorkspaceDialog`：创建/重命名对话框。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceTerminalPage.kt`

提供工作区终端页面，含终端视图与额外按键栏。
- `WorkspaceTerminalPage`：页面入口，绑定工作区详情
- `WorkspaceTerminalContent`：核心终端内容，管理会话与输入
- `TerminalExtraKeysBar`：ESC/TAB/方向键等辅助按键
- `TerminalExtraKey`：单个辅助按键控件
- `TerminalSession.writeText`：向终端发送文本
- `TerminalSessionUiState`：终端会话状态密封接口

### `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceTerminalSession.kt`

管理终端会话创建、准备及客户端交互  
- `createWorkspaceTerminalSession`：创建终端会话  
- `prepareWorkspaceTerminalSession`：准备根文件系统  
- `workspaceRootfsReady`：检查根文件系统就绪  
- `WorkspaceTerminalSessionClient`：终端会话客户端回调  
- `WorkspaceTerminalViewClient`：终端视图客户端处理  
- `activeDnsServers`：获取活跃 DNS 列表

### `app/src/main/java/me/rerere/rikkahub/ui/pages/extensions/workspace/WorkspaceVM.kt`

管理 workspace 列表的 ViewModel，提供创建、重命名、删除操作。
- `WorkspaceVM`：workspace 操作 VM
- `workspaces`：workspace 列表状态流
- `create(name)`：创建
- `rename(workspace, name)`：重命名
- `delete(workspace)`：删除

### `app/src/main/java/me/rerere/rikkahub/ui/pages/favorite/FavoritePage.kt`

收藏夹页面，展示收藏的对话节点，支持滑动删除与撤销。
- `FavoritePage`：收藏列表页面组件
- `SwipeableFavoriteCard`：可滑动删除的收藏卡片
- `FavoriteCard`：收藏项卡片视图

### `app/src/main/java/me/rerere/rikkahub/ui/pages/favorite/FavoriteVM.kt`

管理节点收藏的ViewModel，提供列表、删除与恢复。
- `NodeFavoriteListItem`：节点收藏列表项数据类
- `FavoriteVM`：管理节点收藏
- `nodeFavorites`：流式列表状态
- `removeFavorite`：按refKey删除
- `getEntityByRefKey`：获取实体
- `restoreFavorite`：恢复收藏

### `app/src/main/java/me/rerere/rikkahub/ui/pages/history/HistoryPage.kt`

- 显示对话历史列表，支持滑动删除、固定与搜索，提供撤销删除和清空功能。
- `HistoryPage`：历史页主界面，绑定 HistoryVM
- `SwipeableConversationItem`：可左滑删除的对话卡片
- `ConversationItem`：展示对话标题、时间和固定图标

### `app/src/main/java/me/rerere/rikkahub/ui/pages/history/HistoryVM.kt`

管理历史对话页面的 ViewModel，提供对话列表、删除、置顶与恢复功能。
- `HistoryVM`：历史对话 ViewModel
- `assistant`：当前助理状态流
- `conversations`：当前助理对话列表流
- `deleteConversation`：删除单个对话
- `deleteAllConversations`：删除全部对话
- `togglePinStatus`：切换置顶状态
- `getPinnedConversations`：获取置顶对话流
- `restoreConversation`：恢复已删除对话
- `getFullConversation`：获取完整对话详情

### `app/src/main/java/me/rerere/rikkahub/ui/pages/imggen/ImgGenPage.kt`

图片生成与画廊的 Compose UI 页面。
- `ImageGenPage`：页面入口，含生成与画廊两个标签页。
- `ImageGenScreen`：生成主界面，含生成按钮、预览、设置。
- `ImageGalleryScreen`：历史图片网格，支持复制提示词、保存、删除。
- `SettingsBottomSheet`：图片数量与尺寸设置。
- `InputBar`：提示词输入、参考图、模型选择。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/imggen/ImgGenVM.kt`

管理图片生成与编辑的UI状态、参数及分页历史。
- `ImgGenVM`：图片生成/编辑ViewModel
- `generateImage()`：发起图片生成
- `editImage()`：发起图片编辑
- `generatedImages`：历史图片分页流
- `deleteImage()`：删除指定图片
- `prompt`：提示词状态
- `referenceImages`：参考图列表
- `startNewSession()`：重置会话
- `cancelGeneration()`：取消生成

### `app/src/main/java/me/rerere/rikkahub/ui/pages/log/LogPage.kt`

展示应用日志，含文本/请求日志，支持清空和请求日志开关。

- `LogPage`：主页面，管理日志列表和请求日志开关
- `UnifiedLogList`：按时间排序日志，处理请求日志详情弹窗
- `RequestLoggingSwitchCard`：请求日志记录开关卡
- `RequestLogCard`：请求日志项，可点击查看详情
- `RequestLogDetail`：底部弹窗展示请求/响应详情
- `TextLogCard`：文本日志卡片
- `DetailSection`/`HeaderItem`：辅助布局组件

### `app/src/main/java/me/rerere/rikkahub/ui/pages/search/SearchPage.kt`

搜索消息页面，支持查询、排序、索引重建及结果高亮。
- `SearchPage`：搜索页主组件
- `SortMenuButton`：排序下拉菜单
- `SearchResultItem`：搜索结果条目

### `app/src/main/java/me/rerere/rikkahub/ui/pages/search/SearchVM.kt`

搜索页面的 ViewModel，管理搜索词、排序、结果及索引重建。

- `SearchVM`：搜索 ViewModel，处理查询防抖、结果加载、排序切换、索引重建。
- `performSearch`：执行全文搜索，更新结果列表。
- `rebuildIndex`：触发全量索引重建并显示进度。
- `SORT_ORDER_PREF_KEY`：排序偏好存储键。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingAboutPage.kt`

设置中“关于”页面，展示版本、系统信息、链接，配置 GitHub Issue Token。
- `SettingAboutPage`：关于页面主组件，含 logo 点击彩蛋、版本/系统信息、网站/许可证链接、Issue Token 配置。
- `GitHubIssueTokenDialog`：用于输入或清除 GitHub Issue Token 的对话框。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingFilesPage.kt`

设置页中管理已上传文件的页面，支持浏览、删除和清空
- `SettingFilesPage`：文件管理页面入口
- `FolderRow`：文件夹筛选 Chip 行
- `folderDisplayName`：文件夹名映射
- `FileItem`：文件卡片（缩略图、名称、大小、删除）

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingMcpPage.kt`

MCP 服务器配置页面，支持添加、编辑、删除、导入及工具管理。
- `SettingMcpPage`：主页面，展示服务器列表并提供添加/导入入口。
- `McpServerItem`：单个服务器卡片，显示状态，支持滑动删除和编辑。
- `McpServerConfigModal`：编辑/创建弹窗，分基本设置和工具管理两个标签页。
- `McpCommonOptionsConfigure`：配置启用、名称、传输类型、URL、自定义请求头。
- `McpToolsConfigure`：工具列表，可切换启用与需要审批。
- `McpToolCard`：工具卡片，可展开查看描述和参数标签。
- `McpImportModal`：通过 JSON 文本导入配置。
- `parseMcpServersFromJson`：解析 JSON 字符串为配置对象列表。
- `isValidMcpName`：校验名称仅含字母数字。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingModelPage.kt`

AI 模型设置页面，双标签页管理各场景模型选择与提示词配置。
- `SettingModelPage`：主页面，含模型/提示词标签页
- `ModelSettingsPage`：聊天、快速、标题等各场景模型选择列表
- `SuggestionModelSettingItem`：建议模型开关与选择
- `ModelSettingItem`：单个模型选择条目，支持清除

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingModelPromptPage.kt`

```
负责设置页面的提示词编辑界面
- `PromptSettingsPage`：列出翻译/标题/建议/OCR/压缩提示词配置项
- `PromptSettingItem`：单个提示词设置项，含编辑弹窗与推理级别控制
```

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPage.kt`

设置页面，聚合常规、模型服务、Work Core连接、数据与关于等设置项。
- `SettingPage`：主设置界面，展示各类设置分组
- `WorkConnectionDialog`：Work Core 地址与令牌配置对话框
- `ProviderConfigWarningCard`：API 未配置时的警告卡片

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesGeneralPage.kt`

定义通用偏好设置页面，包含显示与TTS选项。
- `SettingPreferencesGeneralPage`：通用设置Composable，配置多项开关和滑块。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesNotificationPage.kt`

设置通知首选项（显示更新、消息生成通知等）的 Compose 页面。
- `SettingPreferencesNotificationPage`：通知设置页面的 Composable 函数

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesPage.kt`

设置偏好设置页面的 Composable，列出主题、通知等子设置入口。
- `SettingPreferencesPage`：渲染带顶部栏的设置偏好页，包含导航到主题、通知、通用、UI 设置项的卡片列表。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesThemePage.kt`

主题偏好设置页面，提供动态颜色、主题选择、AMOLED暗色模式开关。
- `SettingPreferencesThemePage`：设置页面的主题偏好页面Composable组件。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingPreferencesUIPage.kt`

显示设置偏好页面，提供消息/代码显示选项与自定义字体导入。
- `SettingPreferencesUIPage`：主 Composable 界面
- `CustomFontMimeTypesUI`：字体文件 MIME 类型
- `CustomFontExtensionsUI`：允许的字体扩展名
- `ImportedChatFontUI`：导入字体数据类
- `labelUI`：字体家族标签
- `toFontFamilyUI`：字体家族映射
- `importCustomChatFontInternal`：字体导入逻辑
- `replaceCustomChatFontInternal`：字体替换逻辑
- `deleteCustomChatFontInternal`：字体删除逻辑

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderDetailPage.kt`

提供商详情页，管理配置与模型列表。
- `SettingProviderDetailPage`：提供商详情主页面，含配置/模型双页
- `ModalAbilitySelector`：模型能力多选组件

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingProviderPage.kt`

设置AI提供商列表页，支持添加、导入、推荐、搜索及拖拽排序。
- `SettingProviderPage`：主页面
- `ProviderItem`：提供商卡片
- `AddButton`、`ImportProviderButton`、`RecommendProviderButton`：添加/导入/推荐
- `handleQRResult`、`handleImageQRCode`：处理二维码解析
- `RECOMMENDED_PROVIDERS`：推荐列表

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSearchDetailPage.kt`

搜索服务详情配置与测试页面。
- `SettingSearchDetailPage`：搜索服务详情页，含配置编辑和测试功能
- `SearchServiceOptionsEditor`：根据服务类型分发到具体选项编辑组件
- `SearchTestSection`：搜索测试区域，支持输入查询并展示结果
- 各选项组件（如`TavilyOptions`、`ExaOptions`等）：编辑特定搜索服务的配置参数

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSearchPage.kt`

搜索设置页面，管理搜索服务列表（增删改排序）与通用选项。
- `SettingSearchPage`：主页面 Composable
- `AddProviderDialog`：添加服务提供者对话框
- `SearchProviderCard`：服务提供者卡片
- `SearchAbilityTagLine`：搜索能力标签
- `CommonOptions`：通用选项设置卡片

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSpeechPage.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingSpeechPage.kt",
  "summary": "语音设置页面，管理TTS和ASR提供者，支持添加、编辑、删除、排序和选择当前提供者，提供TTS测试播放，通过底部导航切换TTS与ASR标签页。",
  "symbols": [
    {
      "name": "SettingSpeechPage",
      "kind": "function",
      "description": "语音设置页面入口，管理TTS与ASR提供者"
    }
  ]
}

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingThemePage.kt`

主题设置页面，管理预设和自定义主题的创建、编辑、导入导出。
- `SettingThemePage`：主题设置主页面
- `CustomThemeItem`：自定义主题列表项
- `CustomThemeEditSheet`：主题编辑弹窗
- `ImportThemeDialog`：导入主题对话框
- `ColorPickerRow`：HSL 颜色选择行
- `ThemePreview`：主题预览
- `ColorSwatch`：色块预览

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingVM.kt`

设置页 ViewModel，持有并更新应用设置及 MCP 配置。
- `SettingVM`：设置页 ViewModel，注入 SettingsStore 和 McpManager
- `settings`：暴露的设置状态流 (StateFlow<Settings>)
- `updateSettings`：协程更新设置

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/SettingWebPage.kt`

Web服务器设置页面的Composable组件，管理启动/停止、端口、密码等配置。
- `SettingWebPage`：Web服务器设置页面的主Composable组件

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ASRProviderConfigure.kt`

ASR提供商配置表单UI，根据设定展示不同提供商配置。

- `ASRProviderConfigure`：根据ASR提供商类型展示配置表单
- `OpenAIRealtimeASRConfiguration`：OpenAI实时配置
- `DashScopeASRConfiguration`：DashScope配置
- `VolcengineASRConfiguration`：火山引擎配置
- `MiMoASRConfiguration`：小米MiMo配置
- `StepASRConfiguration`：Step配置

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/BalanceOption.kt`

设置页面余额查询配置 Composable 组件，支持展开/折叠、API 路径和 JSON 表达式编辑。
- `SettingProviderBalanceOption`：渲染余额选项展开表单，含启用开关、路径输入和重置按钮。
- `ApiPathRegex`：校验 API 路径格式（以 `/` 开头）。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/CustomThemeButton.kt`

主题选择按钮组组件，展示主题色块并支持切换
- `CustomThemeButtonGroup`：可组合函数，水平滚动主题列表并响应选择
- `CustomThemeButton`：私有按钮组件，绘制主题色预览与选中图标

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/PresetThemeButton.kt`

定义预置主题选择按钮及按钮组 UI 组件。
- `PresetThemeButton`：展示单个主题色盘与选中状态
- `PresetThemeButtonGroup`：水平滚动主题选择组
- `PresetThemeButtonPreview`：组件预览

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigure.kt`

UI 组件，用于配置 AI 提供商（OpenAI/Google/Claude）设置。
- `ProviderConfigure`：主 Composable 配置入口
- `convertTo`：提供商类型转换
- `ProviderConfigureOpenAI/Claude/Google`：各提供商配置 UI
- `defaultBaseUrlForReset`/`resetBaseUrlToDefault`/`isUsingDefaultBaseUrl`：Base URL 工具
- `OFFICIAL_PROVIDER_HOSTS`：官方主机名单

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConnectionTester.kt`

提供一个连接测试对话框，用于验证 AI 提供者的非流式、流式及工具调用能力。
- `ProviderConnectionTester`：渲染测试入口按钮与对话框，执行三种测试。
- `TestResultItem`：显示单项测试结果，支持错误详情弹窗。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/TTSProviderConfigure.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/ui/pages/setting/components/TTSProviderConfigure.kt",
  "summary": "提供TTS提供商配置的统一UI，支持动态切换多家TTS服务（OpenAI、Gemini、MiniMax等），并根据所选提供商展示对应的配置字段（API密钥、语音、参数等）。关键导出为TTSProviderConfigure组合函数。",
  "symbols": [
    {
      "name": "TTSProviderConfigure",
      "kind": "function",
      "description": "动态渲染TTS提供商配置表单"
    }
  ]
}

### `app/src/main/java/me/rerere/rikkahub/ui/pages/share/handler/ShareHandlerPage.kt`

`ShareHandlerPage` 是分享处理页面，让用户选择助手将分享文本和可选图片发送到聊天。  
- `ShareHandlerPage`：Composable 函数，接收分享文本和图片，展示助手列表，点击后跳转聊天页。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/share/handler/ShareHandlerVM.kt`

管理分享文本和设置的 ViewModel，支持助手更新。
- `ShareHandlerVM`：分享处理 ViewModel，持有文本与设置
- `shareText`：分享的文本内容
- `settings`：设置状态的响应式流
- `updateAssistant`：更新当前助手 ID

### `app/src/main/java/me/rerere/rikkahub/ui/pages/stats/StatsPage.kt`

显示应用使用统计，含热力图与会话/消息/代币等指标卡片。
- `StatsPage`：统计页面入口，展示数据加载状态。
- `HeatmapCard`：热力图容器，含图例。
- `ChatHeatmap`：53周热力图网格，带月份标签与星期标签。
- `HeatmapCell`：单日热力图单元，颜色透明度表示活跃度。
- `StatsGrid`：统计指标网格布局。
- `StatCard`：单个统计卡片，含图标、值、标签。
- `formatCount`/`formatTokens`：格式化数值为K/M/B。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/stats/StatsVM.kt`

App 统计 ViewModel，提供会话/消息/Token/启动次数统计。
- `AppStats`：统计状态数据类
- `StatsVM`：ViewModel，暴露 `stats` 状态流
- `loadStats()`：从 DAO 加载统计数据

### `app/src/main/java/me/rerere/rikkahub/ui/pages/translator/TranslatorPage.kt`

翻译页面，支持文本输入、语言选择、翻译与复制。
- `TranslatorPage`：页面主组件
- `BottomBar`：底部栏，含语言选择与翻译按钮
- `LanguageSelector`：目标语言下拉选择器
- `Locales`：支持的语言列表

### `app/src/main/java/me/rerere/rikkahub/ui/pages/translator/TranslatorVM.kt`

管理翻译页面状态与翻译调用逻辑。
- `TranslatorVM`：翻译页面 ViewModel
- `settings`：用户设置流
- `translating`：翻译进行状态
- `inputText`：输入文本
- `translatedText`：译文结果
- `targetLanguage`：目标语言
- `errorFlow`：错误事件流
- `updateSettings`：更新设置
- `updateInputText`：更新输入
- `updateTargetLanguage`：更新目标语言
- `translate`：执行翻译
- `cancelTranslation`：取消翻译

### `app/src/main/java/me/rerere/rikkahub/ui/pages/webview/WebViewPage.kt`

WebView 页面组件，支持 URL 加载、导航、控制台日志。
- `WebViewPage`：WebView 页面 Composable，接收 `url` 和 `contentId` 参数，提供前进/后退/刷新、浏览器打开、控制台日志弹窗。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/work/PhoneWorkHomePage.kt`

展示 Work 会话列表，支持新建、同步、归档与恢复。
- `PhoneWorkHomePage`：会话列表主页
- `WorkSessionRow`：单个会话卡片
- `EmptyWorkState`：空状态提示
- `displayStatus`：状态文本映射

### `app/src/main/java/me/rerere/rikkahub/ui/pages/work/PhoneWorkHomeVM.kt`

管理手机Work会话列表的ViewModel
- `PhoneWorkHomeVM`：提供会话列表、归档开关、刷新与状态变更
- `sessions`：根据归档标志动态切换的会话列表
- `connection`：连接凭证状态
- `refresh()`：刷新目录和会话
- `complete()`：完成指定会话
- `archive()`/`unarchive()`：归档/恢复会话

### `app/src/main/java/me/rerere/rikkahub/ui/pages/work/PhoneWorkReportPage.kt`

手机端工作报表页面，通过 WebView 加载缓存 HTML 并禁用网络与脚本。  
- `PhoneWorkReportPage`：Composable 函数，接收 contentId 与 title，从缓存加载 HTML，在安全隔离的 WebView 中渲染。

### `app/src/main/java/me/rerere/rikkahub/ui/pages/work/PhoneWorkSessionPage.kt`

{
  "path": "app/src/main/java/me/rerere/rikkahub/ui/pages/work/PhoneWorkSessionPage.kt",
  "summary": "Phone Work 会话的 Compose UI 页面，负责渲染聊天式交互界面，展示会话事件流（用户消息、助手回复、报告、提问卡片等），集成仓库/模型选择、消息发送、报告查看等操作，并管理草稿与已创建会话的导航。"
}

### `app/src/main/java/me/rerere/rikkahub/ui/pages/work/PhoneWorkSessionVM.kt`

管理 PhoneWork 会话的 ViewModel，处理创建、消息、实时重连。
- `PhoneWorkSessionVM`：会话状态与操作
- `session`：当前会话状态
- `events`：事件列表
- `catalog`：仓库目录
- `send`：发送消息/创建会话
- `answer`：提交回答
- `stop`：停止会话
- `DEFAULT_MODELS`：默认模型

### `app/src/main/java/me/rerere/rikkahub/ui/theme/ChatFont.kt`

提供聊天字体主题支持，根据设置选择系统/自定义字体。
- `LocalChatFontFamily`：存储当前聊天字体的 CompositionLocal
- `ChatFontProvider`：注入聊天字体的 Composable 包装
- `rememberChatFontFamily`：按设置缓存解析字体
- `resolveChatFontFamily`：将枚举映射为 `FontFamily`

### `app/src/main/java/me/rerere/rikkahub/ui/theme/CodeColor.kt`

定义 Atom One 主题的代码高亮颜色调色板。
- `AtomOneDarkPalette`：暗色主题高亮色板
- `AtomOneLightPalette`：亮色主题高亮色板

### `app/src/main/java/me/rerere/rikkahub/ui/theme/Color.kt`

定义扩展颜色调色板及 Material3 组件颜色配置
- `ExtendColors`：扩展颜色数据类，含红/橙/绿/蓝/灰各10阶
- `lightExtendColors()`：浅色主题扩展颜色值
- `darkExtendColors()`：深色主题扩展颜色值
- `CustomColors`：对象，提供 `topBarColors`、`cardColors`、`cardColorsOnSurfaceContainer`、`listItemColors` 等 Composable 属性

### `app/src/main/java/me/rerere/rikkahub/ui/theme/CustomTheme.kt`

定义可序列化自定义主题数据类，生成动态 Material3 颜色方案。
- `CustomTheme`：主题数据类，含主/次/第三色 ARGB 值
- `generateColorScheme(dark)`：根据亮暗生成 `ColorScheme`

### `app/src/main/java/me/rerere/rikkahub/ui/theme/GoogleSans.kt`

定义Google Sans Flex可变字体配置，提供多尺寸与粗细样式。
- `ExtendedFontVariation`：扩展可变字体轴，提供 `round` 函数。
- `GoogleSansFlex`：字体族预设对象，包含 Display、Headline、Title、Body、Label 等风格族。

### `app/src/main/java/me/rerere/rikkahub/ui/theme/PresetTheme.kt`

定义预设主题数据类与查找逻辑，聚合多套预设配色方案。

- `PresetTheme`：预设主题数据类，含 id、名称、亮/暗色方案
- `PresetThemes`：延迟加载的预设主题列表
- `findPresetTheme`：按 id 查找预设主题，默认返回 Spring
- `findThemeById`：支持预设与自定义主题查找，转换为 PresetTheme

### `app/src/main/java/me/rerere/rikkahub/ui/theme/Theme.kt`

定义 Material3 动态主题与颜色模式
- `RikkahubTheme`：应用主题入口，支持动态颜色和自定义主题
- `ColorMode`：系统/亮色/暗色枚举
- `LocalExtendColors`：扩展颜色 CompositionLocal
- `LocalDarkMode`：暗色模式 CompositionLocal
- `MaterialTheme.extendColors`：获取扩展颜色的扩展属性

### `app/src/main/java/me/rerere/rikkahub/ui/theme/Type.kt`

定义 Material3 排版与 Jetbrains Mono 字体资源。
- `base`：默认 Typography 实例
- `Typography`：另一个默认 Typography 实例
- `JetbrainsMono`：基于 Jetbrains Mono 字体的 FontFamily

### `app/src/main/java/me/rerere/rikkahub/ui/theme/presets/AutumnTheme.kt`

定义秋季主题的 Material3 颜色方案预设。
- `AutumnThemePreset`：秋季主题预设，包含亮/暗色方案。

### `app/src/main/java/me/rerere/rikkahub/ui/theme/presets/BlackTheme.kt`

黑色主题预设，提供亮/暗 Material 3 配色方案。
- `BlackThemePreset`：id="black" 的主题预设实例。

### `app/src/main/java/me/rerere/rikkahub/ui/theme/presets/OceanTheme.kt`

定义Ocean主题预设，提供亮/暗 Material3 配色方案。  
- `OceanThemePreset`：懒加载的PresetTheme实例，id="ocean"，绑定亮暗色方案。

### `app/src/main/java/me/rerere/rikkahub/ui/theme/presets/SakuraTheme.kt`

定义樱花主题预设的亮色与暗色配色方案。
- `SakuraThemePreset`：懒加载的 `PresetTheme` 实例，提供 id="sakura" 的预设主题

### `app/src/main/java/me/rerere/rikkahub/ui/theme/presets/SpringTheme.kt`

定义春季主题颜色方案，包含亮色和暗色配色。
- `SpringThemePreset`：春季主题预设实例

### `app/src/main/java/me/rerere/rikkahub/utils/AIIconMatcher.kt`

根据 AI 服务名称匹配图标文件名，带缓存。
- `computeAIIconByName`：通过正则匹配返回图标文件名
- `iconCache`：缓存匹配结果

### `app/src/main/java/me/rerere/rikkahub/utils/CacheUtil.kt`

轻量级线程安全缓存，支持过期与 Builder 模式。

- `SimpleCache`：带过期时间的线程安全缓存类
- `getIfPresent`：获取非过期值，过期则移除并返回 null
- `put`：存入缓存
- `invalidate`/`invalidateAll`：移除单个或全部条目
- `cleanUp`：清理所有过期条目
- `size`：返回当前条目数
- `Builder`：链式构建器，设置过期时间
- `companion.builder`：创建 Builder 实例

### `app/src/main/java/me/rerere/rikkahub/utils/ChatUtil.kt`

聊天工具函数，提供导航到聊天页、复制消息、文件类型检查。
- `navigateToChatPage`：导航到聊天页面，可传初始文本、文件、节点ID。
- `copyMessageToClipboard`：将消息内容复制到剪贴板。
- `isAllowedFileType`：根据文件名和MIME判断文件是否允许上传。

### `app/src/main/java/me/rerere/rikkahub/utils/ClipboardUtil.kt`

Android `ClipData` 扩展工具，提供文本提取功能。
- `ClipData.getText()`：拼接所有剪贴项文本。

### `app/src/main/java/me/rerere/rikkahub/utils/CollectionUtils.kt`

扩展函数，比较集合差异返回新增与移除元素。
- `checkDifferent`：比较两集合，返回(add, remove)

### `app/src/main/java/me/rerere/rikkahub/utils/ComposeExt.kt`

Compose 扩展工具函数，提供内边距合并、单位转换、颜色序列化及文本插入。
- `PaddingValues.plus`：合并两个 PaddingValues
- `Color.toCssHex`：转为 CSS 十六进制颜色字符串
- `Dp.toSp`：Dp 转换为 Sp
- `TextUnit.toDp`：Sp 转换为 Dp
- `TextFieldState.insertAtCursor`：在光标处插入文本

### `app/src/main/java/me/rerere/rikkahub/utils/ContextUtil.kt`

提供 Android Context 扩展函数，包括剪贴板、权限、URL 打开和图片导出。
- `readClipboardText`：读取剪贴板文本
- `writeClipboardText`：写入剪贴板文本
- `hasUsageStatsPermission`：检查使用统计权限
- `openUsageAccessSettings`：打开使用统计设置页
- `openUrl`：用自定义标签页打开 URL
- `getActivity`：从 Context 提取 Activity
- `getComponentActivity`：提取 ComponentActivity
- `exportImage`：导出 Bitmap 到相册
- `exportImageFile`：导出图片文件到相册

### `app/src/main/java/me/rerere/rikkahub/utils/CoroutineUtils.kt`

提供将 `Flow` 转为 `MutableStateFlow` 的扩展函数，失败时终止进程。
- `toMutableStateFlow`：扩展函数，在协程作用域中收集流并写入状态，异常时记录日志并退出进程。

### `app/src/main/java/me/rerere/rikkahub/utils/CrashHandler.kt`

捕获全局未处理异常并持久化崩溃信息。
- `CrashHandler`：单例崩溃处理器
- `install`：注册全局异常捕获
- `hasCrashed`：检查是否发生过崩溃
- `getStackTrace`：获取崩溃堆栈
- `clearCrashed`：清除崩溃记录

### `app/src/main/java/me/rerere/rikkahub/utils/DatabaseUtil.kt`

通过反射修改数据库游标窗口大小。
- `DatabaseUtil`：单例工具对象
- `setCursorWindowSize(size: Int)`：反射设置 CursorWindow 大小

### `app/src/main/java/me/rerere/rikkahub/utils/DiffUtils.kt`

文本差异工具，生成 unified diff 字符串。
- `generateUnifiedDiff`：根据新旧文本生成 unified diff，内容相同时返回 null
- `DEFAULT_CONTEXT_LINES`：默认上下文行数常量

### `app/src/main/java/me/rerere/rikkahub/utils/EmojiUtils.kt`

加载并解析emoji JSON数据，处理变体分组与编码转换。
- `EmojiUtils`：工具单例，加载、分组、转换。
- `loadEmoji`：解析JSON为EmojiData。
- `codeToEmoji`：码点转emoji字符。
- `areEmojiVariants`：判断变体。
- `groupEmojisByVariants`：按变体分组。
- `EmojiData`：含版本、分类，提供变体分组。
- `EmojiCategory`：含子分类，获取变体。
- `EmojiSubCategory`：含emoji列表。
- `Emoji`：emoji数据(name, emoji, code)。

### `app/src/main/java/me/rerere/rikkahub/utils/ImageUtils.kt`

图片处理工具类，含压缩、旋转修正、HEIF转换、二维码解析、角色卡元数据提取。

- `ImageUtils`：工具单例，提供所有图片处理功能
- `loadOptimizedBitmap`：避免OOM加载图片
- `calculateInSampleSize`：计算采样率
- `correctImageOrientation`：EXIF旋转修正
- `isHeifImage`：判断HEIF格式
- `convertHeifToJpeg`：HEIF转JPEG
- `decodeQRCodeFromBitmap` / `decodeQRCodeFromUri`：二维码解析
- `recycleBitmapSafely`：安全回收Bitmap
- `getImageInfo`：获取图片尺寸/MIME
- `getTavernCharacterMeta`：提取酒馆角色卡元数据
- `ImageInfo`：图片宽高和MIME数据类

### `app/src/main/java/me/rerere/rikkahub/utils/Json.kt`

提供预配置的 Kotlinx 序列化 Json 实例及扩展属性
- `JsonInstant`：忽略未知键、编码默认值的 Json 实例
- `JsonInstantPretty`：同上且美化输出的 Json 实例
- `JsonElement.jsonPrimitiveOrNull`：安全转换为 JsonPrimitive 扩展

### `app/src/main/java/me/rerere/rikkahub/utils/MarkdownUtils.kt`

Markdown 格式清洗与提取工具  
- `String.stripMarkdown()`：移除 Markdown 语法，返回纯文本  
- `String.extractThinkingTitle()`：从文本末尾提取加粗标题（`**标题**` 独占行）或返回 null

### `app/src/main/java/me/rerere/rikkahub/utils/NotificationUtil.kt`

Android 通知构建与发送工具，支持 DSL 配置、权限检查及扩展函数。
- `NotificationConfig`：通知配置 DSL，含标题、内容、图标、Intent 等属性。
- `NotificationUtil`：单例工具，提供通知发送、取消、权限检查。
- `notify`：DSL 风格发送通知，处理权限。
- `buildNotification`：构建 NotificationCompat.Builder。
- `hasNotificationPermission`：检查 POST_NOTIFICATIONS 权限。
- `cancel`/`cancelAll`：取消通知。
- `sendNotification` (扩展)：简化 Context 通知调用。
- `cancelNotification` (扩展)：简化取消。

### `app/src/main/java/me/rerere/rikkahub/utils/PlayStoreUtil.kt`

检查应用安装来源是否为Google Play商店。
- `PlayStoreUtil`：Play Store 工具类
- `isInstalledFromPlayStore`：检查是否从 Play Store 安装
- `getInstallerPackageName`：获取安装程序包名

### `app/src/main/java/me/rerere/rikkahub/utils/SoundEffectPlayer.kt`

使用 SoundPool 管理音效预加载与播放，支持加载中延迟播放。
- `SoundEffectPlayer`：封装音效播放，预加载和播放控制
- `preload`：预加载资源音效
- `play`：播放音效，未就绪时暂存待播放
- `release`：释放资源，清空状态

### `app/src/main/java/me/rerere/rikkahub/utils/StringUtils.kt`

字符串工具扩展函数，提供编码、格式化、提取等能力。
- `String.urlEncode()`：URL编码
- `String.urlDecode()`：URL解码
- `String.base64Encode()`：Base64编码
- `String.base64Decode()`：Base64解码
- `String.escapeHtml()`：HTML转义
- `String.unescapeHtml()`：HTML反转义
- `Number.toFixed(digits)`：数字保留小数位
- `String.applyPlaceholders(vararg)`：替换占位符
- `Long.fileSizeToString()`：字节转文件大小
- `Int.formatNumber()`：数字缩略（K/M/B）
- `Float.toFixed()/Double.toFixed()`：数字格式化
- `String.extractQuotedContent()`：提取引号内容
- `String.extractQuotedContentAsText()`：合并引号内容
- `String.removeBracketedContent()`：移除括号内容

### `app/src/main/java/me/rerere/rikkahub/utils/TimeUtil.kt`

提供时间格式化扩展函数，支持本地化日期/时间/消息时间显示。
- `toLocalDate`：Instant 转本地日期字符串
- `toLocalDateTime`：Instant 转本地日期时间字符串
- `toLocalTime`：Instant 转本地时间字符串
- `toLocalString`：LocalDateTime 转本地化字符串
- `toMessageTimeString`：当天显示时间，否则显示月日+时间
- `toLocalString(includeYear)`：LocalDate 转字符串，可省略年份

### `app/src/main/java/me/rerere/rikkahub/utils/UiState.kt`

封装UI异步状态（空闲、加载、成功、失败）。
- `UiState`：密封类，UI状态基类
- `Idle`：空闲状态
- `Loading`：加载状态
- `Success`：携带数据的成功状态
- `Error`：携带异常的错误状态
- `onSuccess`：处理成功状态的扩展函数
- `onError`：处理错误状态的扩展函数
- `onLoading`：处理加载状态的扩展函数

### `app/src/main/java/me/rerere/rikkahub/utils/UpdateChecker.kt`

检查更新并下载，含 SemVer 版本比较。
- `UpdateChecker`：检查更新（返回 Flow）与下载更新
- `UpdateInfo`：更新信息（版本、日期、更新日志、下载列表）
- `UpdateDownload`：下载项（名称、URL、大小、SHA256）
- `Version`：值类，实现 SemVer 比较，含预发布优先级

### `app/src/main/java/me/rerere/rikkahub/web/Exceptions.kt`

定义 HTTP API 异常类，映射到标准状态码。
- `ApiException`：密封异常基类，含状态码
- `BadRequestException`：400
- `NotFoundException`：404
- `UnauthorizedException`：401
- `ForbiddenException`：403
- `ConflictException`：409

### `app/src/main/java/me/rerere/rikkahub/web/NsdServiceRegistrar.kt`

通过 JmDNS 注册本地 HTTP 服务并广播 mDNS。
- `NsdServiceRegistrar`：管理注册与清理
- `register`：注册服务，获取 IP 并广播
- `unregister`：取消注册
- `RegisteredServiceInfo`：保存注册信息
- `DEFAULT_SERVICE_NAME`：默认服务名 "zhixing"

### `app/src/main/java/me/rerere/rikkahub/web/WebApiModule.kt`

配置 Ktor Web API 路由、JWT 认证与内容协商。
- `configureWebApi`：注册 `/api` 路由、JWT 认证、JSON 序列化与状态异常处理
- 常量：`WEB_JWT_ISSUER`、`WEB_JWT_AUDIENCE`、`WEB_JWT_SUBJECT`、`WEB_JWT_TTL_MILLIS`、`WEB_ACCESS_TOKEN_QUERY_KEY`、`WEB_AUTH_REALM`

### `app/src/main/java/me/rerere/rikkahub/web/WebServerManager.kt`

管理嵌入式 Web 服务器生命周期、状态与 mDNS 注册。
- `WebServerState`：状态数据类
- `WebServerManager`：服务器管理器
- `start`：启动
- `stop`：停止
- `restart`：重启
- `state`：公开状态流

### `app/src/main/java/me/rerere/rikkahub/web/dto/WebDto.kt`

定义 Web API 请求/响应/SSE 事件的序列化 DTO 及模型转换扩展。
- `SendMessageRequest`：发送消息请求体
- `ConversationDto`：会话详情响应体
- `ConversationNodeUpdateEvent`：节点更新 SSE 事件
- `Conversation.toDto`：领域模型转 DTO 扩展

### `app/src/main/java/me/rerere/rikkahub/web/routes/AIIconRoutes.kt`

定义 `/ai-icon` 路由，按名称返回图标或生成首字母 SVG 占位符。
- `aiIconRoutes`：注册 `/ai-icon` GET 路由，处理图标请求。
- `resolveContentType`：根据扩展名返回 ContentType。
- `buildFallbackSvg`：生成首字母 SVG 占位符。

### `app/src/main/java/me/rerere/rikkahub/web/routes/ConversationDiff.kt`

比较两个 ConversationDto 是否仅有一个消息节点变化。
- `NodeDiff`：存储变更节点索引与数据。
- `singleNodeDiffOrNull`：若仅一个消息节点变化则返回差异，否则返回 null。

### `app/src/main/java/me/rerere/rikkahub/web/routes/ConversationRoutes.kt`

对话路由处理：CRUD、分页、搜索、消息、分支、流、工具审批等。
- `Route.conversationRoutes()`：注册所有对话 API 端点
- `ConversationStreamPayload`：SSE 事件载荷密封接口
- `validateConversationInjectionIds`：校验注入 ID 有效性
- `applyInitialConversationInjections`：应用对话初始注入

### `app/src/main/java/me/rerere/rikkahub/web/routes/EventsRoutes.kt`

定义 Ktor SSE 路由，多路复用推送设置、对话列表失效、文件夹事件。
- `eventsRoutes`：挂载 `/events` SSE 端点，合并三个事件流。

### `app/src/main/java/me/rerere/rikkahub/web/routes/FilesRoutes.kt`

定义 Ktor 文件路由：上传、删除、按 ID/路径下载及 assets 提供。
- `filesRoutes`：`/files` 路由组，含 POST 上传、DELETE 按 ID 删除、GET 按 ID/路径获取文件
- `assetsRoutes`：`/assets` 路由，提供 app assets 资源
- `readPartBytes`：读取 multipart 文件字节，限制大小
- `sanitizeDisplayName`：清理文件名，移除控制字符
- `toUploadedFileDto`：转换实体为 DTO
- `MAX_UPLOAD_FILE_SIZE_BYTES`：20 MB 上传上限

### `app/src/main/java/me/rerere/rikkahub/web/routes/FolderRoutes.kt`

定义文件夹 REST API 路由（列表、创建、重命名、删除）。
- `Route.folderRoutes`：注册 `/folders` 下的 GET/POST/DELETE 路由

### `app/src/main/java/me/rerere/rikkahub/web/routes/RouteUtils.kt`

```markdown
将可空字符串参数转换为 UUID 并校验。
- `toUuid`：将 `String?` 转为 `Uuid`，缺失或格式错误时抛出异常。
```

### `app/src/main/java/me/rerere/rikkahub/web/routes/SettingsRoutes.kt`

定义应用设置更新的 Ktor 路由端点。
- `Route.settingsRoutes`：注册 `/settings` 下多个 POST 路由，处理助手、模型、搜索、工具、收藏等配置更新。
- `parseBuiltInTool`：工具名字符串转 `BuiltInTools` 枚举。

### `app/src/main/res/drawable/afdian.xml`

爱发电图标矢量图形（24dp）
- 根元素 `<vector>`：宽高24dp，视口24x24
- `<path>`：黑色填充，描述品牌logo路径

### `app/src/main/res/drawable/deepthink.xml`

## 深度思考（deepthink）矢量图标定义  
- `<vector>`：定义 200dp x 200dp 的 SVG 路径，支持自动镜像  
- `<path>`：绘制图标的主体形状，包含两个重叠的对话框和星形图案

### `app/src/main/res/drawable/docx.xml`

- **DOCX 文件图标矢量图形**
- `pathData`：定义文档外框、折角、文字区域
- `fillColor`：#FF000000 黑色边框/#FFFFFF 白色文字底色
- `viewportWidth/Height`：1024x1024

### `app/src/main/res/drawable/ic_lucide_camera.xml`

相机图标矢量图，24dp。
- `ic_lucide_camera`：24x24dp 相机图标，含镜头圆和机身路径。

### `app/src/main/res/drawable/patreon.xml`

定义 Patreon 品牌图标的矢量图形。
- `<vector>`：24dp 尺寸，视口 24x24。
- `<path>`：黑色填充，描绘 Patreon 标志轮廓。

### `app/src/main/res/drawable/pdf.xml`

PDF文件图标矢量图

- 矢量图标：PDF文件，颜色#666666，尺寸200dp，由两个路径组成

### `app/src/main/res/drawable/rabbit.xml`

Android 兔子矢量动画，眼睛会眨眼并左右上下移动。
- `eyes_group`：应用眨眼（scaleY）、左右（translateX）和向上（translateY）动画的组合。

### `app/src/main/res/drawable/small_icon.xml`

Android 矢量图标，描绘蝴蝶结/领结图案。
- `vector`: 200dp×200dp，视口1133.9×1133.9
- `path1`: 蝴蝶结主体，fill=@color/black，strokeWidth=20
- `path2`: 两个矩形（眼睛），fill=@color/black，strokeWidth=20

### `app/src/main/res/drawable/zhixing_launcher_background.xml`

启动器矩形渐变背景，深绿到深绿渐变。
- 形状：`rectangle`
- 渐变：`angle=315`，`startColor=#183C36`，`endColor=#0B1F1C`

### `app/src/main/res/drawable/zhixing_launcher_foreground.xml`

Android 启动器前景矢量图，展示“知行”品牌图标。
- `fillColor #FFF7E8`：主字形路径底色
- `fillColor #F3B64A`：右上角装饰路径色

### `app/src/main/res/drawable/zhixing_launcher_monochrome.xml`

知行启动器单色图标矢量图，定义108dp白色文字形状。  
关键条目：`vector` 尺寸108dp，视口108；`path` 填充白色，绘制“知行”字样。

### `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`

自适应图标定义，组合背景、前景与单色图层。
- `background`：引用 `zhixing_launcher_background`
- `foreground`：引用 `zhixing_launcher_foreground`
- `monochrome`：引用 `zhixing_launcher_monochrome`

### `app/src/main/res/resources.properties`

指定Android资源默认区域为en-US。  
- `unqualifiedResLocale`：设为en-US

### `app/src/main/res/values/colors.xml`

定义应用主题颜色常量。
- `purple_200`：浅紫色
- `purple_500`：主紫色
- `purple_700`：深紫色
- `teal_200`：浅青色
- `teal_700`：深青色
- `black`：黑色
- `white`：白色

### `app/src/main/res/values/themes.xml`

定义应用主题，继承 Material3 日夜间无 ActionBar。
- `Theme.Rikkahub`：应用主题，继承 Material3 日夜无 ActionBar

### `app/src/main/res/xml/backup_rules.xml`

定义 Android 自动备份规则，仅包含 `file` 域 `upload/` 目录。
- `<include domain="file" path="upload/">`：备份文件域 upload/ 目录下的数据。

### `app/src/main/res/xml/data_extraction_rules.xml`

定义 Android 12+ 数据备份与设备传输规则。
- `<cloud-backup>`：云备份控制（空示例）
- `<device-transfer>`：设备间传输控制（已注释）

### `app/src/main/res/xml/file_paths.xml`

定义 FileProvider 可访问的缓存、外部及内部文件路径。
- `cache-path`：缓存目录（name="cache"）
- `external-files-path`：外部文件目录（name="external_files"）
- `files-path`：内部文件上传目录（name="upload"）

### `app/src/main/res/xml/remote_config_defaults.xml`

Remote Config 默认配置，存放 API 密钥和免费模型列表。
- `silicon_cloud_api_key`：硅云 API 密钥（编码）
- `silicon_cloud_free_models`：免费模型名称列表

### `app/src/main/res/xml/shortcuts.xml`

定义相机快捷方式，指向 ShortcutHandlerActivity 处理。
- `camera` 快捷方式：图标 `ic_lucide_camera`，标签 `shortcut_take_picture`，意图 `zhixing://shortcut`，类别 `android.shortcut.conversation`。

### `app/src/test/java/me/rerere/rikkahub/AppIdentityTest.kt`

验证应用标识 `AppIdentity` 的关键属性符合自主托管要求。  
- `AppIdentityTest`：单元测试类  
- `zhixing identity does not depend on upstream hosted services`：验证产品名、遥测开关、更新源与源码地址均指向自建仓库

### `app/src/test/java/me/rerere/rikkahub/ExampleUnitTest.kt`

示例单元测试，验证测试框架可用性。
- `ExampleUnitTest`：示例测试类
- `addition_isCorrect`：验证2+2=4的基础断言

### `app/src/test/java/me/rerere/rikkahub/ShareSheetTest.kt`

验证 ProviderSetting 编解码分享逻辑的单元测试类。
- `ShareSheetTest`：测试 `encodeForShare`/`decodeProviderSetting` 对 OpenAI/Google/Claude 及余额选项的编解码正确性与异常处理。

### `app/src/test/java/me/rerere/rikkahub/data/ai/GenerationPromptsTest.kt`

测试 GenerationPrompts 的记忆提示构建与选择逻辑。
- `GenerationPromptsTest`：测试类
- `memoryPromptSeparatesProfileAndContextAndExcludesArchivedRecords`：验证提示分离 profile/context 并排除归档记录
- `promptSelectionAppliesIndependentProfileAndContextLimits`：验证 profile 与 context 独立限制

### `app/src/test/java/me/rerere/rikkahub/data/ai/mcp/McpConnectionKeyTest.kt`

测试 McpConnectionKey 生成逻辑，确保连接键仅依赖传输/URL/头/认证。
- `McpConnectionKeyTest`：验证连接键不受工具元数据影响，受URL、传输类型、headers、OAuth影响。

### `app/src/test/java/me/rerere/rikkahub/data/ai/tools/KnowledgeToolsTest.kt`

测试知识工具审批逻辑：只读工具免审批，`knowledge_ingest` 需审批，但工作区配置可覆盖关闭。
- `KnowledgeToolsTest`：测试类
- `readOnlyKnowledgeToolsDoNotNeedApprovalButIngestDoes`：验证只读工具免审批，ingest 需审批
- `workspaceOverrideCanDisableIngestApproval`：验证工作区覆盖可关闭 ingest 审批

### `app/src/test/java/me/rerere/rikkahub/data/ai/tools/MemoryToolsTest.kt`

验证 `buildMemoryTools` 生成单一模型端内存工具。
- `MemoryToolsTest`：测试类
- `structuredMemoryRemainsOneModelFacingTool`：校验工具名称、描述、动作枚举及参数

### `app/src/test/java/me/rerere/rikkahub/data/ai/tools/SearchToolsTest.kt`

测试多源搜索工具的并发、容错与结果合并逻辑。
- `SearchToolsTest`：测试类
- `selected providers start concurrently`：并发
- `one provider failure keeps successful results`：容错
- `all provider failures surface an aggregate error`：聚合错误
- `duplicate normalized urls merge provider identities`：去重合并

### `app/src/test/java/me/rerere/rikkahub/data/ai/tools/TextReplacersTest.kt`

TextReplacersTest 测试 `replaceText` 的精确、行修剪、块锚点替换策略。
- `TextReplacersTest`：验证替换函数的多种匹配与异常场景。

### `app/src/test/java/me/rerere/rikkahub/data/ai/tools/local/GitHubIssueToolTest.kt`

测试 GitHub Issue 工具的功能、标签与字段校验。
- `GitHubIssueToolTest`：测试类
- `direct submission still requires tool approval`：验证直接提交需审批
- `feature request uses enhancement label and product sections`：验证特性请求标签与内容
- `bug report uses bug sections without leaking unrelated feature fields`：验证缺陷报告字段隔离
- `invalid type and blank required fields are rejected`：验证无效类型与空字段拒绝

### `app/src/test/java/me/rerere/rikkahub/data/ai/transformers/PromptInjectionTransformerTest.kt`

测试 PromptInjectionTransformer 的注入转换逻辑，涵盖模式注入、世界书、正则匹配等场景。
- `PromptInjectionTransformerTest`：核心测试类，包含多种注入位置、优先级、扫描深度等测试用例。

### `app/src/test/java/me/rerere/rikkahub/data/ai/transformers/TimeReminderTransformerTest.kt`

对`applyTimeReminder`函数的单元测试，验证时间提醒注入逻辑。
- `TimeReminderTransformerTest`：测试类
- `userMessage`：创建测试用UIMessage
- `getMessageText`：提取消息文本
- 多个@Test方法：覆盖首次注入、间隔<1h不注入、=1h不注入、>1h注入、天格式、多间隔、空消息

### `app/src/test/java/me/rerere/rikkahub/data/ai/transformers/WorkspaceReminderTransformerTest.kt`

数据层 `buildWorkspacePrompt` 的单元测试。
- `WorkspaceReminderTransformerTest`：测试提示生成逻辑
- `initializedKnowledgeToolsArePromptedWithoutRootfs`：知识就绪时提示含知识工具，无shell
- `shellInstructionsOnlyAppearWhenRootfsIsReady`：rootfs就绪时提示含shell和cwd
- `workspace`：构建测试用WorkspaceEntity

### `app/src/test/java/me/rerere/rikkahub/data/datastore/DefaultProvidersTest.kt`

测试默认提供者中Vercel AI Gateway配置
- `DefaultProvidersTest`：测试`DEFAULT_PROVIDERS`常量
- 验证项：name、baseUrl、enabled、builtIn、balanceOption字段

### `app/src/test/java/me/rerere/rikkahub/data/datastore/SearchServiceSelectionTest.kt`

测试搜索服务选择的旧索引迁移、ID优先、回退及空列表逻辑。
- `SearchServiceSelectionTest`：测试类，包含4个测试用例。

### `app/src/test/java/me/rerere/rikkahub/data/datastore/migration/PreferenceStoreV4MigrationTest.kt`

用于测试偏好存储 V4 迁移逻辑，移除退休提供商与嵌套覆盖，并重置关联选择。
- `PreferenceStoreV4MigrationTest`：测试类，验证退休条目清理与设置迁移。
- `retired providers and nested overrides are removed before deserialization`：测试移除退休提供商及嵌套 providerOverwrite。
- `settings backup migration clears retired selections`：测试迁移清除退休选择并重置 chatModelId。

### `app/src/test/java/me/rerere/rikkahub/data/files/SkillPathsTest.kt`

测试 SkillFrontmatterParser 与 SkillPaths 的路径安全与解析  
- `SkillPathsTest`：包含三个安全测试  
- `parse supports CRLF frontmatter`：验证 CRLF 换行的前置元数据解析  
- `resolve skill dir rejects traversal and nested names`：拒绝目录穿越与嵌套名称  
- `resolve skill file rejects sibling prefix escape`：拒绝兄弟目录前缀逃逸

### `app/src/test/java/me/rerere/rikkahub/data/github/GitHubIssueClientTest.kt`

测试 `GitHubIssueClient` 的创建 issue 及授权失败映射。
- `GitHubIssueClientTest`：测试类
- `creates issue with GitHub API contract`：验证 API 契约
- `maps authorization failure without exposing response body`：授权失败映射

### `app/src/test/java/me/rerere/rikkahub/data/knowledge/KnowledgeSpaceServiceTest.kt`

知识空间文档标准化逻辑的单元测试。  
- `KnowledgeSpaceServiceTest`：测试类  
- `textDocumentsAreNormalizedLocally`：验证文本文件正常标准化  
- `unsupportedBinaryIsPreservedButNotIndexed`：验证二进制文件返回 null 不索引

### `app/src/test/java/me/rerere/rikkahub/data/profile/ProfileMaintenanceServiceTest.kt`

{
  "path": "app/src/test/java/me/rerere/rikkahub/data/profile/ProfileMaintenanceServiceTest.kt",
  "summary": "JUnit4 测试类，验证 ProfileMaintenanceService 的解析与校验逻辑，包括 Markdown 包裹 JSON 的提取、候选证据数规则、置信度阈值及维度过滤等边界场景。"
}

### `app/src/test/java/me/rerere/rikkahub/data/repository/MemoryRepositoryTest.kt`

测试 `MemoryRepository` 未知持久化值降级为 CONTEXT 和 ACTIVE。
- `MemoryRepositoryTest`：测试类
- `unknownPersistedValuesDegradeToContextAndActive`：验证未知 kind/state 降级逻辑

### `app/src/test/java/me/rerere/rikkahub/service/ChatServiceTest.kt`

ChatService 背景参数生成测试。
- `ChatServiceTest`：验证 `backgroundTextGenerationParams` 包含模型自定义请求配置

### `app/src/test/java/me/rerere/rikkahub/ui/components/webview/WebViewContentCacheTest.kt`

WebViewContentCache 单元测试：验证大内容存储、相同内容复用和非法 ID 拒绝。
- `WebViewContentCacheTest`：测试类
- `store`/`load`：缓存读写方法
- `"stores large content outside navigation state"`：大内容存储测试
- `"reuses the same cache entry for identical content"`：相同内容复用测试
- `"rejects invalid cache ids"`：无效 ID 校验测试

### `app/src/test/java/me/rerere/rikkahub/ui/pages/setting/components/ProviderConfigureConvertToTest.kt`

测试 ProviderSetting 类型转换（convertTo）逻辑。  
- `ProviderConfigureConvertToTest`：包含5个测试用例，验证转换中字段保留、URL 重写、同类型返回等行为。

### `app/src/test/java/me/rerere/rikkahub/utils/DiffUtilsTest.kt`

测试 `generateUnifiedDiff` 工具函数的各种场景。
- `DiffUtilsTest`：测试类
- `returns null when texts are identical`：相同文本应返回null
- `generates unified diff for single line replacement`：单行替换生成正确diff
- `generates diff for appended lines`：追加行应包含+line3
- `splits distant changes into separate hunks`：远距离变更拆分为多个hunk
- `merges nearby changes into one hunk`：近距离变更合并为一个hunk
- `handles replace all style multiple replacements`：多处替换正确生成diff

### `app/src/test/java/me/rerere/rikkahub/utils/StringUtilsTest.kt`

StringUtils 扩展函数的单元测试。
- `StringUtilsTest`：测试 `extractQuotedContent`、`extractQuotedContentAsText`、`removeBracketedContent` 扩展函数，覆盖中英文引号、括号删除及空值处理。

### `app/src/test/java/me/rerere/rikkahub/utils/UpdateCheckerTest.kt`

文件职责：UpdateChecker 单元测试，验证更新清单解析与静默处理。

- `UpdateCheckerTest`：测试类
- `reads zhixing release manifest with product user agent`：验证成功解析更新信息及用户代理
- `missing release feed is a silent no-op`：验证404静默返回空下载列表

### `app/src/test/java/me/rerere/rikkahub/utils/VersionTest.kt`

测试 Version 类的语义版本比较与优先级逻辑。
- `VersionTest`：测试 Version 工具类的所有比较规则。
