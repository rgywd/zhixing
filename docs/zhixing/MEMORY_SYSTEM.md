# 记忆文档与历史对话检索 V3

状态：V3 现行契约（Issue #208；前台按需写入于 2026-08-14 更新）。

## 1. 两套机制，不能混用

知行把长期记忆和原始历史严格分开：

| 机制 | 事实来源 | 用途 | 写入方式 |
| --- | --- | --- | --- |
| 记忆文档 | Room `MemoryDocumentEntity` | 用户希望长期保留、可编辑的陈述事实 | 用户编辑器，或 App 活跃时由当前 chat Run 按需调用可见的 `memory_write` |
| 历史对话检索 | Room 会话/消息 + 可重建 FTS5 | 按关键词或最近时间找回原始聊天 | 只由会话持久化链维护，不自动写入记忆 |

`conversation_search` 的命中不能自动变成记忆，删除记忆文档也不会删除原始聊天；删除原始聊天或单条消息时，
只移除文档 frontmatter 中对应的 source 引用并推进文档版本，不擅自删除已经整理好的独立记忆正文。两套能力分别由
`Assistant.enableMemory` 和 `Assistant.enableRecentChatsReference` 控制。

## 2. 记忆文件模型

V3 是存储在 Room 中的虚拟 Markdown 文件系统，不把用户数据散落为 Android 外部目录中的裸文件。每个文档
包含：

- `scopeId`、规范化 `path`；
- `name`、决定按需召回的 `description`、`aliases`；
- Markdown `content`；
- `sources`：类型、会话 ID、消息 ID、精确用户原话和时间；
- 单调递增 `version`、状态和创建/更新时间。

允许模型写入的路径只有：

```text
/profile.md
/preferences.md
/areas/<slug>.md
/topics/<slug>.md
/people/<slug>.md
```

`/profile.md` 与 `/preferences.md` 始终属于全局 scope，且不能删除，只能清空正文。其他路径可位于全局或
当前助手 scope。迁移生成的 `/archive/legacy-memory*.md` 只读。

存储层把元数据拆成列以支持 Room 查询和 CAS，但 `memory_read` 与 pinned 注入返回的是完整虚拟 Markdown 文件：
YAML frontmatter 固定包含 `name / description / sources / aliases`，后接正文。正文允许使用 `[[name]]` 引用其他
文件；目标通过 listing 的路径和 aliases 定位，不会因此把被引用文件正文自动塞进上下文。

## 3. 上下文加载

每轮生成不再注入最多 28 条扁平 PROFILE/CONTEXT。固定注入内容只有：

1. 当前可见文档的 listing：`path + description + aliases + version`；
2. `/profile.md` 和 `/preferences.md` 的完整文件（frontmatter + 正文）。

`/areas`、`/topics`、`/people` 和 legacy archive 正文不会常驻。模型必须先调用 `memory_read(path)`，工具调用
与返回在聊天中可见。listing 和文档存储均有字符上限，pinned 虚拟文件不会被半截截断；所有记忆内容按不可信数据处理，不能
成为指令。

## 4. 写入与并发

模型侧只有两个能力：

- `memory_read(path)`：读取一份文档；
- `memory_write(action, ...)`：`write`、`str_replace`、`append`、`delete`。

`action` 必填且每种 action 只携带自己的字段：`write` 提交 path、if_version、name、description、可选
aliases、非空 content 和 sources；`str_replace` 提交 path、if_version、非空
old_text、允许为空的 new_text 和 sources；`append` 提交 path、if_version、非空 content 和 sources；
`delete` 只提交 path 与 if_version。

新建时 `if_version = 0`；其余操作必须使用 listing 或最近一次读取返回的当前版本。DAO 使用带 version 条件的
单条 SQL 更新；版本不一致时返回冲突和当前文档，不允许静默覆盖另一个 surface 的更新。删除整个文件仍需
用户确认；删除会清空正文、元数据和来源，只保留带新版本的 path tombstone 防止并发旧写复活，pinned 文档不允许删除。

记忆只在 App 活跃的当前 chat Run 内实时工作，不注册后台模型维护、回答后二次模型调用、定时整理或下次启动
补扫。`memory_write` 是可选 mutation，不是 Run 终态：只有当前 USER 消息包含明确陈述、长期有用且非敏感的
新事实或纠正，或者用户明确要求记住、纠正、删除时才调用；没有文档要变更时不调用任何写入工具，普通回答直接
完成。用户不需要固定说“记住”，但模糊、推断或一次性内容宁可不写。

参数、来源、内容或版本校验失败时返回 `success=false / changed=false / retryable / error / correction`；成功
mutation 返回 `success=true / changed=true`。用户明确要求的记忆变更在 `retryable=true` 时必须按 correction
修正后重试，成功前不得声称已经记住；机会式写入失败不能替代或阻塞用户原本请求的回答，也不能冒充保存成功。
一次 Run 可以按实际需要修改多份文档，DAO 的版本条件与工具校验负责并发和重复内容边界。

模型写入还必须同时满足：

- 内容是当前用户明确说出的持久事实；删除仍必须由用户明确要求；
- 每条正文事实以 `- [stated] ` 开头；
- 至少一条 source；模型只提交当前 USER 消息 Text part 的精确 quote，应用从当前运行快照绑定可信的会话 ID
  和消息 ID，模型不能提供或覆盖这两个内部 ID；
- 不包含被禁止的敏感类别、credential 或精确财务数字；
- `/preferences.md` 不接受“永远别反驳/质疑”“扮演某角色”等控制身份或取消判断的指令。

用户在记忆页直接编辑等同于新的用户陈述，记录 `USER_EDIT` source。模型推断、旧自动画像摘要、助手回复、
工具结果和历史搜索 snippet 不能落入 active 文档。

## 5. V2 迁移和退役

数据库 43→44 新建 `MemoryDocumentEntity` 并初始化空的 profile/preferences。旧 `MemoryEntity` 数据不删除：

- 所有非 deleted 的 PROFILE、CONTEXT、OBSERVATION 按 scope 合并到只读 legacy archive；
- archive 行使用 `[legacy]`，不会冒充 `[stated]`，也不会自动注入；
- 旧表暂留一版，供回滚、审计和后续显式迁移使用。

V2 模型维护服务不再注册。应用升级后只执行一次无模型调用的旧 WorkManager 任务取消动作；同名 Worker 仅保留
立即成功的兼容壳，防止升级竞态造成类加载失败，它不读聊天、不写记忆、不调度或重试。V3 没有静默维护、
时间窗口或跨聊天证据晋升。

## 6. 历史检索

历史检索继续复用 `MessageFtsManager`：消息保存/编辑时重建会话索引，删除会话时同步移除索引。普通聊天只有
在“历史对话检索”开关开启时注册 `recent_chats` 与 `conversation_search`。结果返回原会话 ID、标题、日期和
snippet；这只是原始记录的检索投影，不是长期事实。

## 7. 竞品与技术取舍

截至 2026-08-11 的官方资料显示，ChatGPT、Claude、Copilot 和 Perplexity 都给 saved memory 与历史聊天引用
不同的控制或来源边界；Claude 的 chat search 还明确显示工具调用和旧聊天 citation。Gemini 的 past-chat
personalization 更依赖删除源聊天，独立条目管理较弱。项目/空间 scope、临时聊天不读不写、逐条可见来源是
共同的可信方向。

技术上借鉴 Letta 的 always-visible blocks + on-demand files、MemGPT 的上下文分页和 Zep 的 raw episode 与
curated fact 分层；不照搬模型自主改写、云向量库、图数据库或“只失效不硬删”。竞品帮助文档没有公开记忆
条目的 optimistic lock，因此 `if_version` 是知行为本地多 surface 明确增加的契约。

参考：

- [OpenAI Memory FAQ](https://help.openai.com/en/articles/8590148-memory-faq)
- [OpenAI Dreaming V3](https://openai.com/index/chatgpt-memory-dreaming/)
- [Claude chat search and memory](https://support.claude.com/en/articles/11817273-use-claude-s-chat-search-and-memory-to-build-on-previous-context)
- [Gemini past-chat memory](https://support.google.com/gemini/answer/16598469)
- [Letta context hierarchy](https://docs.letta.com/v1-sdk/memory/context-hierarchy)
- [MemGPT paper](https://arxiv.org/abs/2310.08560)
- [Graphiti](https://github.com/getzep/graphiti)

## 8. 验收

1. 非 pinned 正文不出现在开场 prompt，listing 能路由到正确路径。
2. 模型侧 source schema 不暴露会话 ID 或消息 ID；quote 不是当前 USER 消息的精确子串时写入失败，匹配成功时
   由应用绑定最近一条可信来源消息。
3. 非 `[stated]`、敏感类别和控制型 preference 写入失败。
4. 两个 writer 使用同一旧 version 时只有一个成功，另一个得到冲突。
5. 43→44 保留旧记录到 archive，不删除旧表或用户会话。
6. 记忆页可查看和编辑元数据/正文/版本/来源数，删除非 pinned 文件需要确认。
7. 历史检索关闭时不注册 history tools；开启后仍查询原始 FTS，不读取 MemoryDocument 表。
8. 无记忆变化的 Run 不调用 `memory_write`；有真实 mutation 时才出现可见工具调用。显式记忆请求的可重试
   失败会修正后重试，机会式写入失败不阻塞普通回答；响应不包含 Run `finalized` 语义，且没有后台或隐藏的
   二次模型调用。
9. 删除消息/会话会移除对应 source，删除助手会清理其 scope；旧 `MemoryEntity` 不再有当前 UI 写入口。
