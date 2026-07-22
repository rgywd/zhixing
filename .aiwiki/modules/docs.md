---
source_commit: 762c37f2ba2c6f3f982c8b1ae5761fadb904b8af
generated: 2026-07-21
---


# 模块：docs

项目文档与规范知识库，统一提供架构、产品设计、发布流程等导航信息。

模块按 `references` 和 `zhixing` 组织，`zhixing` 下包含产品愿景、运行时契约、手机线架构、数据安全等规范，`archive` 保留历史决策。文档作为静态知识源，无运行时数据流，由 AI 编程 agent 按需检索。对外通过 `README` 提供统一入口，无代码级依赖，仅引用概念模型。

修改指引：新增或修订文档应遵循 `RELEASE_FLOW.md` 分支规范，在对应目录下直接编辑 Markdown 文件。


## 文件摘要

### `docs/references/chat-generation-pipeline.md`

描述聊天生成管道的完整数据流、核心类与处理阶段。
- `ChatService`：会话管理入口与编排
- `ConversationSession`：单个会话状态容器
- `GenerationHandler`：核心生成逻辑与Step循环
- `InputMessageTransformer`：发送前消息变换管道
- `OutputMessageTransformer`：接收后消息变换管道

### `docs/zhixing/AI_ASSISTANT_VISION.md`

定义知行 AI 助手的长期产品愿景、架构原则和演进路线。

### `docs/zhixing/AUTO_PROFILE_MAINTENANCE.md`

定义长期用户画像 V2 的数据层次、置信度、模型输出与调度等设计规范。

### `docs/zhixing/CODEX_PHONE_LINE_ARCHITECTURE.md`

定义手机端通过Core和Runner远程控制Codex的可靠架构与契约。
- `Android App`：管理会话、消息与通知
- `Work Core`：耐久消息队列与认证中心
- `Work Runner`：执行Codex并桥接状态
- `Phone-line MCP`：提供报告、提问与HTML
- `手机专属Stop Hook`：写入结束标记
- `手机会话行为指令`：注入工具使用时机

### `docs/zhixing/CODEX_PHONE_LINE_CONTRACT.md`

定义 Work Phone-line v1 协议，规范手机端与后台 Runner 之间的异步会话、工具调用、事件模型与 API 边界，确保安全协作。

- `report`：向手机端发送进度文本
- `ask`：向用户提问并等待回答
- `report_html`：提交清洗后的 HTML 报告
- `POST /v1/work/sessions`：创建手机工作会话
- `POST /v1/runner/heartbeat`：Runner 续租命令
- `POST /v1/mcp/sessions/{id}/report`：MCP 报告接口
- `GET /v1/work/sessions/{id}/stream`：事件流 SSE
- `POST /v1/work/sessions/{id}/archive`：归档会话

### `docs/zhixing/CODEX_PHONE_LINE_IMPLEMENTATION_PLAN.md`

Work Phone-line v1 的实施与验收计划，跟踪各阶段进度与验收标准。

### `docs/zhixing/DATA_SAFETY_AND_BACKUP.md`

定义知行应用的数据安全、升级迁移与备份恢复的设计契约、缺口和实施顺序。

- `数据分类表`：明确哪些数据必须备份和保留
- `升级前快照`：升级前自动本地快照与校验
- `备份包 manifest`：备份包结构、校验和路径安全契约
- `加密与凭据`：AES-256-GCM 加密与密钥保护边界
- `定时备份调度`：WorkManager 周期与事件触发备份策略
- `恢复模式`：迁移失败后的可恢复与恢复操作
- `发布验证门`：版本发布前必须通过的数据安全测试
- `非目标`：明确不承诺的恢复和同步能力

### `docs/zhixing/KNOWLEDGE_SPACE.md`

知行知识空间 v0.1 设计规范，定义产品边界、目录结构、数据导入/归一、检索引用及 AI 工具契约，用于指导实现与验收。

### `docs/zhixing/README.md`

知行文档导航，提供知行产品、架构与交付文档的统一入口和阅读指引。

### `docs/zhixing/RELEASE_FLOW.md`

定义知行项目的 Git 分支、提交与发布全流程规范。  
- `main`：稳定主干，永远可部署  
- `release/x.y.z`：发布冻结快照  
- `feat/需求号-简述`：功能分支  
- `fix/问题号-简述`：修复分支  
- `chore/简述`：配置维护分支  
- `exp/简述`：实验分支  
- commit 规范：Conventional Commits 中文摘要  
- 发布流程：先合入 main 再切 release  
- 回滚：通过更高版本修复  
- 门禁：线性历史，PR 必须通过 CI

### `docs/zhixing/RUNTIME_CONTRACT.md`

定义知行Android本地运行时架构、核心数据模型、生成流水线与持久化边界。
- `Conversation`：本地UUID会话实体
- `MessageNode`：分支候选消息节点
- `UIMessage`：结构化消息部件
- `Assistant`：助手运行配置快照
- `Memory`：手动记忆与画像管线
- `生成生命周期`：流式生成与工具循环
- `持久化边界`：Room/DataStore/附件安全
- `外部边界`：Provider/网关隔离

### `docs/zhixing/VOLCENGINE_TTS.md`

介绍火山引擎 Agent Plan TTS 的默认配置与 V3 二进制协议规范。

### `docs/zhixing/archive/2026-07-16-android-baseline-plan.md`

该文档记录了已归档的知行 Android 早期实施计划与验证状态，供追溯历史。

### `docs/zhixing/archive/2026-07-19-memory-mvp.md`

此文档是“知行记忆 MVP”的已归档产品规格与验收记录，用于留存 v27 迁移和首版设计决策证据。

（该文件为纯文档，无类/函数/导出，故省略列表）

### `docs/zhixing/archive/2026-07-20-android-product-design.md`

记录知行 Android 重构阶段的产品决策与设计约束，已归档不再作为总纲。

### `docs/zhixing/archive/README.md`

声明本目录仅用于历史决策追溯，内容已过期，不可替代现行文档。
