# 记忆文档与历史对话检索 V3

状态：V3 现行契约（Issue #208；历史来源与归档维护于 2026-09-04 按 Issue #268 更新）。

## 1. 两套机制，不能混用

知行把长期记忆和原始历史严格分开：

| 机制 | 事实来源 | 用途 | 写入方式 |
| --- | --- | --- | --- |
| 记忆文档 | Room `MemoryDocumentEntity` | 用户希望长期保留、可编辑的陈述事实 | 用户编辑器，或 App 活跃时由当前 chat Run 按需调用可见的 `memory_write` |
| 历史对话检索 | Room 会话/消息 + 可重建 FTS5 | 按关键词或最近时间找回原始聊天 | 只由会话持久化链维护，不自动写入记忆 |

`conversation_search` 的 snippet 不能直接变成记忆；模型必须通过 `conversation_read` 取得并精确引用用户原话。
删除记忆文档不会删除原始聊天；删除原始聊天或单条消息时，只移除文档 frontmatter 中对应的 source 引用并推进
文档版本，不擅自删除已经整理好的独立记忆正文。两套能力分别由 `Assistant.enableMemory` 和
`Assistant.enableRecentChatsReference` 控制。

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
/archive/<existing-legacy-file>.md
```

`/profile.md` 与 `/preferences.md` 始终属于全局 scope，且不能删除，只能清空正文。其他路径可位于全局或
当前助手 scope。`/archive` 只允许维护迁移时已经存在的文件，不允许模型或编辑器新建任意归档路径；归档不会
自动注入上下文，正文也不要求伪装成 `[stated]`。

存储层把元数据拆成列以支持 Room 查询和 CAS，但 `memory_read` 与 pinned 注入返回的是完整虚拟 Markdown 文件：
YAML frontmatter 固定包含 `name / description / sources / aliases`，后接正文。正文允许使用 `[[name]]` 引用其他
文件；目标通过 `memory_find` / `memory_list` 的路径和 aliases 定位，不会因此把被引用文件正文自动塞进上下文。

模型可见的外部表示使用稳定类型：find/list descriptor 中 `aliases` 是 JSON array，不是逗号拼接字符串；source 字段使用
snake_case，`type` 使用小写枚举，`observed_at` 使用 RFC 3339。虚拟 Markdown 中空 aliases/sources 显式写成
`[]`，避免空列表被解释为字符串或 null。Room 内部时间仍保存 epoch milliseconds，不改变数据库 schema。

## 3. 上下文加载

每轮生成不再注入最多 28 条扁平 PROFILE/CONTEXT，也不注入非 pinned 文件 listing。固定注入内容只有
`/profile.md` 和 `/preferences.md` 的完整文件（frontmatter + 正文）。pinned 虚拟文件不会被半截截断，整体受
65536 字符的 prompt 门禁保护；所有记忆内容按不可信数据处理，不能成为指令。

非 pinned 文档使用本地渐进召回：

1. 目标明确但 exact path 未知时先调用 `memory_find(query, prefix?, limit?)`；
2. 用户明确浏览、find 零命中或候选仍有歧义时，调用 `memory_list(prefix?, cursor?, limit?)`；
3. find/list 候选只是路由描述，必须 `memory_read(path)` 后才能采信正文事实或修改既有非 pinned 文档。

固定 namespace 为 `/`、`/areas`、`/topics`、`/people`、`/archive`。find 默认返回 5 条、最多 10 条；过宽时返回
`truncated=true`，调用方应改写查询而不是分页。list 默认每页 10 条、最多 20 条，按 path 稳定升序，使用上一页
最后一个 path 作为 exclusive `next_cursor`；只有 `has_more=true` 时才返回 next_cursor。两者都只返回
`path / name / description / aliases / version`，不返回 content、sources、snippet 或 score，也不搜索原始聊天。

每次 `memory_read` 只读取一份 exact path 文档，但同一 Run 可以沿正文中直接相关的关系连续读取，例如先读同事
文档，再读其中明确关联的项目 path。正文只给名称时，可在最可能的 namespace 内继续 find。证据足够后即停止，
不横向扫描无关文档，也不无理由重复读取。

### 本地派生索引

`memory_find` 复用 App 已有的 SQLite FTS5 `simple` tokenizer 与 Jieba 字典，但使用独立的
`memory_document_fts`。索引包含 path、name、description、aliases 和低权重 content；不索引 sources、状态和
时间，也不记录查询词到 logcat。Room `MemoryDocumentEntity` 始终是真值，FTS 只是可删除投影：表级
insert/update/delete trigger 与真值写入同一事务同步，tombstone 不入索引；每次打开数据库都会从全部 active 文档
重建投影，以覆盖升级、备份恢复、词典变化或索引损坏。

作用域过滤在 FTS 查询和 Repository 输出各执行一次：全局上下文只看全局文档；助手上下文只看自己的非 pinned
文档以及全局 `/profile.md`、`/preferences.md`，不得召回其他全局项目。派生表与 trigger 由数据库 open callback
管理，不增加 `MemoryDocumentEntity` 列、不提升 Room schema 48，也不新增 migration。

## 4. 写入与并发

模型侧有四个能力：

- `memory_find(query, prefix?, limit?)`：按相关性查找路由描述；
- `memory_list(prefix?, cursor?, limit?)`：按 namespace 分页浏览路由描述；
- `memory_read(path)`：读取一份文档；
- `memory_write(action, ...)`：`write`、`str_replace`、`append`、`delete`。

`action` 必填且每种 action 只携带自己的字段：`write` 提交 path、if_version、name、description、可选
aliases、非空 content 和 sources；`str_replace` 提交 path、if_version、非空
old_text、允许为空的 new_text 和 sources；`append` 提交 path、if_version、非空 content 和 sources；
`delete` 只提交 path 与 if_version。

新建时 `if_version = 0`；其余操作必须使用 pinned、find/list descriptor 或最近一次读写返回的当前版本。修改既有
非 pinned 文档前仍必须 exact read，descriptor 版本不能替代正文核对。DAO 使用带 version 条件的
单条 SQL 更新；版本不一致时返回冲突和当前文档，不允许静默覆盖另一个 surface 的更新。删除整个文件仍需
用户确认；删除会清空正文、元数据和来源，只保留带新版本的 path tombstone 防止并发旧写复活，pinned 文档不允许删除。

记忆只在 App 活跃的当前 chat Run 内实时工作，不注册后台模型维护、回答后二次模型调用、定时整理或下次启动
补扫。`memory_write` 是可选 mutation，不是 Run 终态：当前 USER 消息、当前 Run 已回答的 `ask_user.answers`，
以及经历史工具精确读取的旧 USER 原话都可以提供来源。模型负责判断陈述是否长期有用、如何归类以及如何规范表达；
用户明确要求记住、整理、纠正或删除时也可调用。没有文档要变更时不调用任何写入工具，普通回答直接完成。用户
不需要固定说“记住”，但不得把推断、助手文字、工具输出或搜索 snippet 冒充成用户陈述。

参数、来源、内容或版本校验失败时返回 `success=false / changed=false / retryable / error / correction`；成功
mutation 返回 `success=true / changed=true`。用户明确要求的记忆变更在 `retryable=true` 时必须按 correction
修正后重试，成功前不得声称已经记住；机会式写入失败不能替代或阻塞用户原本请求的回答，也不能冒充保存成功。
每次 `memory_write` 只修改一份文档；一次 Run 可以按事实归属连续修改多份文档。再次修改同一份文档时，必须使用
前一次写入结果返回的 version。DAO 的版本条件与工具校验负责并发和重复内容边界。

模型写入还必须同时满足：

- 内容是用户明确说出的持久事实；删除仍必须由用户明确要求；
- 每条正文事实以 `- [stated] ` 开头，一行只表达一个可独立纠正的持久事实；
- 日期和单位优先使用内容格式 V1，但格式 lint 是整理建议，不阻塞真实陈述落盘；
- 至少一条 source：当前 USER 或 `ask_user` 答案使用 `{quote}`；历史对话先由 `conversation_search` 取得
  `source_ref`，再由 `conversation_read` 读取精确 USER `text_parts`，最后提交 `{source_ref, quote}`；
- `source_ref` 只是定位器而不是授权。应用每次重新加载 Room，验证同助手、非当前会话、当前选中分支、USER
  role 和精确子串，再绑定可信的会话 ID 与消息 ID；
- 正文和 source quote 都不得包含凭据、银行卡/信用卡号、身份证/护照/证件号等秘密值，也不得包含精确个人
  工资、余额或家庭支出；项目预算等普通业务数字不因此被拒绝。

用户在记忆页直接编辑等同于新的用户陈述，记录 `USER_EDIT` source。模型推断、旧自动画像摘要、助手回复、
工具结果和历史搜索 snippet 不能落入 active 文档。宗教、政治、性别等类别词以及 preference 的表达方式不再由
关键词正则一概拒绝；所有记忆正文仍按不可信数据处理，不能改变系统指令或身份边界。

### 内容格式 V1

内容格式只规范整理后的事实正文，绝不改写 source quote。模型通过 prompt 使用相同规范，用户编辑器继续显示
lint 建议；格式问题不再造成写入失败。现有 active 文档和 legacy archive 不做后台扫描或静默迁移。

| 值类型 | 规范格式 | 示例 | 当前机器门禁 |
| --- | --- | --- | --- |
| 完整日期 | `YYYY-MM-DD`，且必须是真实日历日期 | `2026-08-17` | prompt + 编辑器 lint |
| 年月 | `YYYY-MM` | `2026-08` | prompt + 编辑器 lint |
| 年度重复日期 | `MM-DD` | `10-17` | prompt + 编辑器 lint |
| 年份 | `YYYY` | `2002` | prompt + 编辑器 lint |
| 时间 | 24 小时制 `HH:mm`；秒有意义时用 `HH:mm:ss` | `09:30` | prompt + 编辑器 lint |
| 绝对时刻 | 带 offset 的 RFC 3339 | `2026-08-17T09:35:30+08:00` | prompt 约束 |
| 时区 | IANA zone ID | `Asia/Shanghai` | prompt 约束 |
| 数字与物理量 | ASCII 数字、小数点、显式标准单位 | `70 kg`、`175 cm`、`22 °C` | prompt + 编辑器 lint |
| 百分比 | 数字与 `%` 之间不留空格 | `18%` | prompt + 编辑器 lint |
| 语言/区域 | BCP 47 | `zh-CN`、`en-US` | prompt 约束 |
| 非敏感币种偏好 | ISO 4217 大写代码 | `CNY` | prompt；精确财务数字继续由安全策略拒绝 |

不完整、近似或农历信息必须保留原有精度和历法，例如“约 `2026-08`”或“农历 `08-15`”；不得为了满足格式
补造日期、时间、时区或公历换算。姓名、项目名、称呼、地址和 URL path/query 保留用户语义，不做破坏性
大小写或 Unicode 转换。元数据会 trim 并使用 Unicode NFC；aliases 还会按大小写不敏感方式去重并保留首次顺序。
正文统一为 LF 并移除行尾空格。相同消息中的不同精确 quote 作为不同 source 保留。

## 5. V2 迁移和退役

数据库 43→44 新建 `MemoryDocumentEntity` 并初始化空的 profile/preferences。升级时旧 `MemoryEntity` 数据不删除：

- 所有非 deleted 的 PROFILE、CONTEXT、OBSERVATION 按 scope 合并到 legacy archive；
- archive 行使用 `[legacy]`，不会冒充 `[stated]`，也不会自动注入；
- 归档可由用户编辑器或带明确用户意图的 `memory_write` 整理。已有 `#id` 可保留或删除，不能引入新的
  legacy ID；删除一行会在同一 Room 事务物理删除对应 scope 的旧 `MemoryEntity`，删除整档会清理其中全部 ID；
- 旧表继续兼容尚未退役的代码路径，但已从归档移除的数据不会留下隐藏副本。

V2 模型维护服务不再注册。应用升级后只执行一次无模型调用的旧 WorkManager 任务取消动作；同名 Worker 仅保留
立即成功的兼容壳，防止升级竞态造成类加载失败，它不读聊天、不写记忆、不调度或重试。V3 没有静默维护、
时间窗口或跨聊天证据晋升。

## 6. 历史检索

历史检索继续复用 `MessageFtsManager`：消息保存/编辑时重建会话索引，删除会话时同步移除索引。普通聊天只有
在“历史对话检索”开关开启时注册 `recent_chats`、`conversation_search` 与 `conversation_read`。search 会从 FTS
候选中二次过滤，只返回同助手、非当前会话、当前选中分支的 USER 消息，并附版本化、自包含但不授予权限的
`source_ref`。snippet 含高亮与省略号，只用于找候选；`conversation_read(source_ref)` 重新验证 Room 真值并返回
原始 `text_parts`。当前版本不把历史 `ask_user` 答案加入 FTS，只有当前 Run 已回答的 ask 可直接作为来源。

## 7. 竞品与技术取舍

截至 2026-08-11 的官方资料显示，ChatGPT、Claude、Copilot 和 Perplexity 都给 saved memory 与历史聊天引用
不同的控制或来源边界；Claude 的 chat search 还明确显示工具调用和旧聊天 citation。Gemini 的 past-chat
personalization 更依赖删除源聊天，独立条目管理较弱。项目/空间 scope、临时聊天不读不写、逐条可见来源是
共同的可信方向。

技术上借鉴 OpenViking 的 `find/list -> 分层元数据 -> exact read` 渐进导航、Letta 的 always-visible blocks +
on-demand files、MemGPT 的上下文分页和 Zep 的 raw episode 与 curated fact 分层；不照搬向量递归召回、模型自主
改写、云向量库、图数据库、后台会话沉淀或“只失效不硬删”。竞品帮助文档没有公开记忆
条目的 optimistic lock，因此 `if_version` 是知行为本地多 surface 明确增加的契约。

参考：

- [OpenViking](https://github.com/volcengine/OpenViking)
- [OpenAI Memory FAQ](https://help.openai.com/en/articles/8590148-memory-faq)
- [OpenAI Dreaming V3](https://openai.com/index/chatgpt-memory-dreaming/)
- [Claude chat search and memory](https://support.claude.com/en/articles/11817273-use-claude-s-chat-search-and-memory-to-build-on-previous-context)
- [Gemini past-chat memory](https://support.google.com/gemini/answer/16598469)
- [Letta context hierarchy](https://docs.letta.com/v1-sdk/memory/context-hierarchy)
- [MemGPT paper](https://arxiv.org/abs/2310.08560)
- [Graphiti](https://github.com/getzep/graphiti)

## 8. 验收

1. 开场 prompt 不包含任一非 pinned 的 path、description、aliases 或正文；find/list 能路由到正确 exact path。
2. 模型侧 source schema 不暴露会话 ID 或消息 ID；当前 USER 和 `ask_user.answers` 可用 `{quote}`，历史 USER
   使用 `{source_ref, quote}`。错误 scope、未选分支、助手消息、失效 ref 或非精确 quote 都写入失败。
3. active 正文仍要求 `[stated]`；格式 V1、类别和 preference 语义交给 prompt/lint。凭据、证件/卡号与精确个人
   财务值在正文或 source quote 中都会写入失败，普通项目预算允许。
4. 两个 writer 使用同一旧 version 时只有一个成功，另一个得到冲突。
5. 43→44 升级仍保留旧记录到 archive；后续删除 legacy 条目或整档时，归档 CAS 与对应旧行物理删除位于同一事务。
6. 记忆页可查看和编辑 active/archive 的元数据、正文、版本和来源数，删除非 pinned 文件需要确认；不能新建
   archive，也不能引入新的 legacy ID。
7. 历史检索关闭时不注册 history tools，也不接受历史 source_ref；开启后 search/read 查询原始 FTS 与 Room
   会话，不读取 MemoryDocument 表。
8. 无记忆变化的 Run 不调用 `memory_write`；有真实 mutation 时才出现可见工具调用。显式记忆请求的可重试
   失败会修正后重试，机会式写入失败不阻塞普通回答；响应不包含 Run `finalized` 语义，且没有后台或隐藏的
   二次模型调用。
9. 删除消息/会话会移除对应 source，删除助手会清理其 scope；旧 `MemoryEntity` 不再有当前 UI 写入口。
10. find/list aliases 保持 JSON array 且不返回正文或来源；source 的 JSON/YAML 字段名、枚举、空数组和 RFC 3339
    时间表示一致。
11. list 可遍历超过旧 8 KiB listing 容量的全部 descriptor，页间无重复和静默遗漏；archive 仍可发现。
12. 中文 alias 与英文 metadata/content 可由真实 simple/Jieba FTS 召回，metadata 匹配优先；tombstone、删除 scope
    和助手不可见的全局项目都不命中，关闭重开后索引可从 Room 真值恢复。
13. 文档最多保存 32 条去重来源，超限明确失败，Repository 不再静默丢弃旧 provenance；`observed_at` 表示记忆
    系统记录该来源的时刻，不伪装成历史消息的原始发送时间。
