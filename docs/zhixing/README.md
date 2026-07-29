# 知行文档导航

这里是知行自有产品、架构与交付文档的唯一入口。第一次接触项目时，先读本页，再按任务选择文档；不要把
`archive/` 中的历史计划当作当前实现。

## 先读

| 文档 | 何时阅读 |
| --- | --- |
| [AI 助手产品愿景](./AI_ASSISTANT_VISION.md) | 判断产品方向、能力优先级和明确非目标 |
| [运行时与数据契约](./RUNTIME_CONTRACT.md) | 修改普通聊天、Provider、工具、持久化或外部边界 |
| [Git 分支、提交与发布流程](./RELEASE_FLOW.md) | 创建分支、提交、合并、冻结版本或发布 |
| [CI 与构建流水线](./CI_PIPELINE.md) | 修改 GitHub Actions、Gradle 缓存或发布构建 |
| [数据安全、升级与备份](./DATA_SAFETY_AND_BACKUP.md) | 修改数据库、文件、备份、恢复或覆盖升级 |

## 现行能力契约

| 领域 | 文档 | 状态 |
| --- | --- | --- |
| Work（Codex / Claude Code） | [架构](./CODEX_PHONE_LINE_ARCHITECTURE.md) · [协议](./CODEX_PHONE_LINE_CONTRACT.md) | v1 已实现；多运行时手机会话、三条电话线、图片、后台跟踪与归档均以这两份契约为准 |
| Staging 测试客户端 | [分发、受限 API 与验收](./STAGING_TEST_CLIENT.md) | 与正式版并存；用于本机机器验证，不进入正式 Release |
| 用户画像 | [长期用户画像 V2](./AUTO_PROFILE_MAINTENANCE.md) | V2 已实现；以用户原话证据、纵向观察和每维单摘要为现行契约 |
| 知识空间 | [OrbitOS CN Vault v0.3](./KNOWLEDGE_SPACE.md) | 已实现；以 `/workspace/vault`、知识库浏览、Git 绑定、收件箱导入、来源引用和 vault-local skills 为现行契约 |
| 火山语音 | [Agent Plan TTS](./VOLCENGINE_TTS.md) · [Agent Plan ASR](./VOLCENGINE_ASR.md) | 已实现；记录协议、接口和默认配置 |
| 事项与弱日历 | [简单待办、长期计划、系统日历投影与 AI 工具](./AGENDA_AND_CALENDAR.md) | v2 已实现；待办与长期计划均以 Room 为事实来源，日历为可选时间背景 |
| 月度收支 | [多渠道账单、AI 汇总工具与统计子 Tab](./MONTHLY_LEDGER.md) | v1；只保存整月与渠道汇总，不保存逐笔交易或附件内容 |
| 邮件与飞书监控 | [只读 AI 工具、云端薄代理与隐私边界](./INFORMATION_MONITOR.md) | v1 客户端与 Work Core 契约已实现；真实连接器和 Life Gateway 部署不在本轮范围 |
| 当前状态理解 | [本地事实、画像语境与确定性介入边界](./RUNTIME_CONTRACT.md#当前状态理解与介入) | 已实现；只解释值得注意的变化，无理由时保持安静 |
| GitHub CLI | [受控 `gh issue` 工具](./GITHUB_CLI_TOOL.md) | v1 已实现；复用 App Token 入口并按 Workspace 自动配置 Rootfs |
| Lenovo Watch Pro | [协议基线](./LENOVO_WATCH_PRO_PROTOCOL.md) | 只读 BLE 探针已实现并完成真机同步验证 |
| 普通聊天 | [消息生成链路](../references/chat-generation-pipeline.md) | 代码参考；修改前需与当前源码和测试核对 |

## 文档生命周期

- **愿景**回答“为什么做、最终要变成什么”，不记录逐文件施工步骤。
- **契约**回答“现在必须如何工作”，即使功能已经实现也继续保留并随代码更新。
- **实施与验收**只保留正在驱动当前工作的计划；交付完成或已被后续实现取代后移入 `archive/`。
- **Release Notes**位于根目录 `release-notes/`，是每个公开版本的交付记录，不移入本文档目录。
- **归档**见 [`archive/README.md`](./archive/README.md)，仅保存历史决策和验证证据，不再作为当前开发依据。

新增文档前先判断能否更新现有愿景或契约，避免为一次讨论再建一份孤立稿件。
