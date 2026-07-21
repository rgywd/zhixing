---
source_commit: faa1320682d60db0cf30f821d05ed660197e00ed
generated: 2026-07-21
---

# 模块：docs

该模块是知行项目的设计文档集，为 AI 编程代理提供产品架构、实施计划、协议契约与验收标准的上下文导航。

文档按主题组织：`references/` 描述核心会话生成链路；`zhixing/` 覆盖从产品基线、阶段计划、记忆 MVP、知识空间、数据安全、电话线架构到发布流程等完整设计。关键契约文档（如 RUNTIME_CONTRACT、CODEX_PHONE_LINE_CONTRACT）定义了领域对象、状态机与 MCP 工具，实现时需依此对齐。文档本身无程序接口，其依赖关系体现为开发阶段对设计契约的遵循。

修改指引：需求评估后先定位对应阶段文档（如 IMPLEMENTATION_PLAN），再按其中契约与验收标准实施，涉及协议变更时必须同步更新相关 CONTRACT 文档。

## 文件摘要

### `docs/references/chat-generation-pipeline.md`

描述用户消息到 AI 回复的完整生成链路，含预处理、变换管道、工具系统、会话管理。
- `ChatService`：入口编排
- `ConversationSession`：会话状态容器
- `GenerationHandler`：核心生成与工具调用
- `InputMessageTransformer`：发送前消息变换
- `OutputMessageTransformer`：流式接收后变换

### `docs/zhixing/AUTO_PROFILE_MAINTENANCE.md`

{
  "path": "docs/zhixing/AUTO_PROFILE_MAINTENANCE.md",
  "summary": "描述自动画像维护功能的设计契约：使用四维画像增量整理对话，通过快速模型生成候选，并保护手动/锁定画像不被覆盖。涵盖调度、数据模型、交互、失败降级与验收标准。"
}

### `docs/zhixing/CODEX_PHONE_LINE_ARCHITECTURE.md`

描述 Codex 手机电话线架构，定义组件、状态机与安全边界。
- `Android`：手机端，管理会话/通知/附件
- `Work Core`：公网耐久队列与状态存储
- `Work Runner`：开发机执行器，启动 Codex CLI
- `Phone-line MCP`：report/ask/report_html 工具
- 状态机：`CREATED→QUEUED→RUNNING→WAITING_FOR_USER→IDLE→COMPLETED/FAILED`

### `docs/zhixing/CODEX_PHONE_LINE_CONTRACT.md`

定义 Work Phone-line v1 协议契约，涵盖会话、MCP 工具、HTTP API、事件与展示映射。
- 工具：`report`, `ask`, `report_html`
- 事件：`ASSISTANT_MESSAGE`, `USER_MESSAGE`, `REPORT`, `ASK`, `HTML_REPORT`, `RUN_STATE`, `SYSTEM_ERROR`
- 协议头：`X-Zhixing-Work-Protocol: 1`

### `docs/zhixing/CODEX_PHONE_LINE_IMPLEMENTATION_PLAN.md`

Codex 手机链路实施与验收计划，分阶段执行并跟踪状态。
- Phase 0：旧链路下架
- Phase 1：契约与 Core
- Phase 2：Windows Runner
- Phase 3：Android Work
- Phase 4：部署与发布门
- 提交切片：5 个独立可测提交
- 验证证据：Core 测试、E2E、Android 编译、迁移通过

### `docs/zhixing/DATA_SAFETY_AND_BACKUP.md`

知行数据安全与备份设计基线，定义升级、备份与恢复契约。

- 安全目标：覆盖升级不丢数据，迁移失败可恢复，远端备份兜底
- 数据分类：Room/DataStore/Workspace 原文必须备份，RootFS/索引可重建
- 备份包契约：manifest.json + SHA-256 + AES-256-GCM 加密
- 升级门槛：升级前快照、迁移验证、恢复模式
- 实施顺序：P0 修复事实错误，P1 可恢复升级，P2 自动加密备份

### `docs/zhixing/IMPLEMENTATION_PLAN.md`

知行 Android 基座分阶段实施计划，定义各阶段目标与验证门。
- `Phase 0`：可构建的知行基线
- `Phase 0.5`：数据安全与可恢复升级
- `Phase 1`：核心会话闭环
- `Phase 2`：助手、工具与工作区
- `Phase 2.1`：项目知识空间
- `Phase 2.3`：记忆 MVP
- `Phase 3`：可选同步服务
- `Phase 4`：Work Phone-line v1
- 每阶段验证门
- 当前基线与保护规则

### `docs/zhixing/KNOWLEDGE_SPACE.md`

定义知行知识空间 v0.1 的设计基线，涵盖产品边界、目录结构、导入/检索机制和 AI 工具契约。
- `/PROJECT.md`：项目目标与约束
- `/knowledge/sources/`：原始资料
- `/.zhixing/knowledge/normalized/`：归一化可检索文本
- `knowledge_search`：本地全文检索，返回 citation
- `knowledge_ingest`：导入文件需批准
- 验收：幂等初始化、中文检索、权限隔离

### `docs/zhixing/MEMORY_MVP.md`

知行记忆MVP设计文档，定义画像与情境两区记忆、数据模型、工具契约及验收标准。  
- `我的画像`：全局原子用户事实  
- `记住的事`：通用情境记录  
- `MemoryEntity`：新增`kind(PROFILE/CONTEXT)`、`state(ACTIVE/ARCHIVED)`  
- `memory_tool`：单工具支持create/edit/archive/restore/delete  
- 迁移：v26→v27，旧记录补为CONTEXT+ACTIVE  
- 验收：分区注入、归档不注入、单工具、edit优先、单测覆盖

### `docs/zhixing/PRODUCT_DESIGN.md`

产品设计基线，定义首期闭环、Work、信息架构与验收标准。

- `闭环`：流式、会话、Work
- `Work`：Codex 手机电话线，MCP
- `架构`：会话/助手/工作区/设置
- `验收`：独立安装、自适应布局
- `不做`：旧迁移、Ktor、多平台UI

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

定义知行运行时架构、核心领域对象与契约。
- `Conversation`：对话实体
- `MessageNode`：消息分支节点
- `UIMessage`：结构化消息部件
- `Assistant`：助手配置快照
- `ChatService`：会话管理
- `GenerationHandler`：生成生命周期
- `ProviderManager`：模型供应商
- 持久化：Room/DataStore 边界

### `docs/zhixing/VOLCENGINE_TTS.md`

{
  "path": "docs/zhixing/VOLCENGINE_TTS.md",
  "summary": "说明知行语音设置中独立集成的火山引擎 Agent Plan TTS 类型，仅负责语音合成，不恢复已下架模型，给出默认配置、WebSocket 鉴权方式与 V3 二进制协议边界，确保合规调用与错误处理。"
}
