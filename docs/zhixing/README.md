# 知行文档导航

这里是知行自有产品、架构与交付文档的唯一入口。第一次接触项目时，先读本页，再按任务选择文档；不要把
`archive/` 中的历史计划当作当前实现。

## 先读

| 文档 | 何时阅读 |
| --- | --- |
| [AI 助手产品愿景](./AI_ASSISTANT_VISION.md) | 判断产品方向、能力优先级和明确非目标 |
| [运行时与数据契约](./RUNTIME_CONTRACT.md) | 修改普通聊天、Provider、工具、持久化或外部边界 |
| [Git 分支、提交与发布流程](./RELEASE_FLOW.md) | 创建分支、提交、合并、冻结版本或发布 |
| [数据安全、升级与备份](./DATA_SAFETY_AND_BACKUP.md) | 修改数据库、文件、备份、恢复或覆盖升级 |

## 现行能力契约

| 领域 | 文档 | 状态 |
| --- | --- | --- |
| Codex Work | [架构](./CODEX_PHONE_LINE_ARCHITECTURE.md) · [协议](./CODEX_PHONE_LINE_CONTRACT.md) · [实施与验收](./CODEX_PHONE_LINE_IMPLEMENTATION_PLAN.md) | v1 已形成主闭环，仍有发布门验证项 |
| 用户画像 | [长期用户画像 V2](./AUTO_PROFILE_MAINTENANCE.md) | V2 已实现；以用户原话证据、纵向观察和每维单摘要为现行契约 |
| 知识空间 | [知识空间 v0.1](./KNOWLEDGE_SPACE.md) | 已实现；目录、引用和工具边界仍是有效契约 |
| 火山语音 | [Agent Plan TTS](./VOLCENGINE_TTS.md) | 已实现；记录协议和默认配置 |
| 普通聊天 | [消息生成链路](../references/chat-generation-pipeline.md) | 代码参考；修改前需核对 Wiki 的 `source_commit` |

## 文档生命周期

- **愿景**回答“为什么做、最终要变成什么”，不记录逐文件施工步骤。
- **契约**回答“现在必须如何工作”，即使功能已经实现也继续保留并随代码更新。
- **实施与验收**只保留仍有未完成项的计划；全部完成后移入 `archive/`。
- **Release Notes**位于根目录 `release-notes/`，是每个公开版本的交付记录，不移入本文档目录。
- **归档**见 [`archive/README.md`](./archive/README.md)，仅保存历史决策和验证证据，不再作为当前开发依据。

新增文档前先判断能否更新现有愿景或契约，避免为一次讨论再建一份孤立稿件。
