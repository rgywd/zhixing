---
source_commit: c2ef85031701bb57c43e639bcd2eb032215efe90
generated: 2026-07-22
---

# 项目进展（按 commit）

项目近期聚焦于 Work 会话功能的稳定与完善，同时持续优化长期记忆、搜索和语音能力。当前版本已迭代至 0.3.5，整体交互体验与任务执行可靠性显著提升。

### 功能
- `5ed7c311` feat(work): 为 Work 会话自动生成标题
- `2efb44a0` feat(work): 支持自动发现本机工作目录
- `0f88479a` feat(work): 增强会话基础交互 (#84)
- `01a78d0e` feat(work): track tasks and archive sessions (#72)
- `44b1a779` feat(work): teach phone sessions to report progress
- `e65c96b9` feat(work): add image attachments and repository catalog
- `3a7e8bae` feat(chat): 增加事项侧边栏界面
- `2da36ab4` feat(memory): 收拢画像并分页展示 (#86)
- `4e60e8cf` feat(memory): add automatic profile maintenance
- `361bbbce` feat(speech): 接入火山引擎 TTS
- `4e684361` feat(search): support multiple search providers
- `047023ad` feat(search): add Doubao search provider

### 修复
- `c2ef8503` fix(work): 隔离会话页面状态
- `b4a52fd4` fix(work): 恢复旧版首次失败会话
- `48481832` fix(work): 补全恢复命令会话快照
- `8ed1e9eb` fix(work): 恢复首次会话失败后的重试 (#103)
- `1bd50afe` fix(work): 以 Codex 回合事件收口卡住会话
- `9975635c` fix(work): 置顶通知展示任务完成结果
- `02746411` fix(knowledge): 空知识空间不再干扰普通对话
- `57a1aae6` fix(memory): 重构长期用户画像准入链路

### 重构
- `ec6fadf8` feat(work): replace legacy stack with Codex phone lines (#69)
- `5719e8be` refactor(provider): retire Volcengine Agent Plan

### 杂项
- `00fe1b97` chore(release): 准备 0.3.5
- `873d8e6f` docs(product): 整理知行产品文档
- `36ad8eba` chore(wiki): 忽略本机 Obsidian 状态
