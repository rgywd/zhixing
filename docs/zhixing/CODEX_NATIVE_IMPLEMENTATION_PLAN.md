# 知行 0.2.0 Codex 原生实施计划

状态：In progress
日期：2026-07-19
跟踪：GitHub Issue #49

## 1. 交付策略

0.2.0 是协议、服务端、Agent、数据层和 Android 信息架构的跨层更新。采用短分支顺序合并，`main` 始终可部署，0.1.x 可以并行发布。

规则：

- 不使用 worktree。
- 每个阶段从当时最新 `main` 创建 `feat/49-*`。
- 共享工作区有其他未提交改动时，不切分支、不 stash、不代替提交。
- 未闭环能力默认关闭或不挂正式 UI。
- 合并前重新同步 `origin/main` 并执行移动检测。
- 只在功能冻结和全链路验收后创建 `release/0.2.0`。

## 2. 阶段矩阵

### P0：文档与契约

分支：`feat/49-codex-native-contract`

产物：

- `docs/zhixing/CODEX_NATIVE_ARCHITECTURE.md`
- `docs/zhixing/ZHIXING_WIRE_V1.md`
- `docs/zhixing/CODEX_NATIVE_IMPLEMENTATION_PLAN.md`

验收：

- 当前链路、目标对象、Desktop 边界、UX、威胁模型和回滚均有明确结论。
- 高风险项对应契约、测试门槛或明确延期。
- 不修改运行时代码，不影响 #48 或 0.1.x。

### P1：Wire v1 core

分支：`feat/49-wire-v1-core`

文件：

- `agent/src/wire/`
- Android `data/workflow/wire/`
- `docs/zhixing/test-vectors/wire-v1/`

RED：

- AAD 任一字段改变仍能解密。
- duplicate/reordered/gap/expired 未被拒绝。
- 缺块覆盖旧 snapshot。
- tombstone 后旧设备可复活 Thread。
- 不同 major 仍执行写操作。

GREEN：

- Node/Kotlin 交叉向量全绿。
- Happy v0 与 Wire v1 bundle 可明确区分且只读兼容。
- Zod/Kotlin serializer 对未知可选字段前向兼容。

验证：

```powershell
cd agent
npm test
npm run typecheck
npm run build

cd ..
./gradlew.bat :app:testDebugUnitTest
```

### P2：Codex Adapter 与 Catalog

分支：`feat/49-codex-catalog`

文件：

- `agent/src/codex/appServerClient.ts`
- `agent/src/codex/schema/`
- `agent/src/catalog/`
- Agent CLI 诊断命令和测试。

RED：

- cursor 分页漏 Thread。
- archived、subagent、fork、Automation 分类错误。
- Windows/WSL/`\\?\`/Git root 生成重复 Project。
- schema/capability 不兼容仍允许控制。
- Desktop 状态未知被显示为 running/idle。

GREEN：

- `codex app-server generate-ts` 产物生成 schema hash 和能力映射。
- `thread/list/read` 完整目录与按需正文规范化。
- Project ID 持久化且不泄漏路径。
- 真机 catalog dump 与 Desktop 项目/历史抽样一致。
- 旧 Codex 明确只读降级。

### P3：Zhixing Relay v1

分支：`feat/49-relay-v1-service`

产物：

- 独立 `relay/` 服务。
- `deploy/zhixing-relay/` Docker Compose、反向代理、持久卷和 runbook。
- 数据库 migration、健康检查、指标、备份和恢复脚本。

RED：

- 重复 envelope 重复应用。
- 重启丢 ACK/presence/outbox/tombstone。
- Relay 日志或数据库出现 plaintext payload。
- 备份无法恢复或版本不匹配静默启动。
- 2C2G 环境 WebSocket/持久化失效。

GREEN：

- 认证、配对、撤销、路由、ACK、outbox、RPC 和 tombstone 集成测试通过。
- Docker 重启持久化、备份恢复和回滚演练通过。
- 明文扫描无路径、标题、正文、工具参数和凭据。
- 不依赖 `happy-server-self-host`。

### P4：Android Catalog 与信息架构

分支：`feat/49-android-codex-catalog`

产物：

- Wire v1 Client、Repository 和 Room schema。
- Project/Thread/Turn/Item/RuntimeBinding/Approval 本地模型。
- 工作首页、Project 页、全局搜索、设置与离线状态。

RED：

- 大量 Thread 卡顿或丢失。
- 配置仍出现在工作首页。
- 离线导致历史消失或草稿丢失。
- subagent/Automation 平铺污染主列表。
- 返回栈、最大字体、横屏或深浅主题失败。

GREEN：

- 项目优先首页和 Project → Thread 导航完成。
- Room 是缓存单一来源，revision/tombstone 正确应用。
- 搜索覆盖标题、正文和项目，未知/不可解对象隔离。
- 48dp 触控、contentDescription、字体放大和主题验收。

### P5：Codex Runtime Bridge

分支：`feat/49-codex-runtime-bridge`

产物：

- 唯一 RuntimeBinding manager。
- start/resume/fork/steer/interrupt/archive/delete/approval。
- Android 原生 Thread 页和规范化 Item 渲染。

RED：

- 重复 resume 创建多个产品会话或并发 turn。
- requestId 重试重复执行危险操作。
- Desktop 状态未知被静默接管。
- 工具/审批/错误被当成普通正文或只进日志不进聊天。

GREEN：

- Agent 启动的 Thread 真机完成消息、工具、审批、steer、interrupt 和完成闭环。
- 同一 Thread 重连不重复。
- Desktop 历史可继续；状态未知要求确认。
- Socket/Relay 断线后从 ACK/revision 补齐。

### P6：Happy 只读与切换

分支：`feat/49-happy-readonly-cutover`

状态（2026-07-19）：已实现，等待 PR gate。默认 `Screen.Workflow` 与 Agent daemon 均切到
Codex Wire；`-PcodexWorkflowEnabled=false` 和 `ZHIXING_ENABLE_LEGACY_HAPPY=1` 分别保留
Android/Agent 回滚开关。旧 Happy 页面只读，旧凭据、缓存、VPS 容器与 v0.1.13 Release 均已核对保留。

产物：

- 0.2.0 feature flag 和新旧入口切换。
- Happy 历史只读边界。
- 0.1.13 客户端、Agent、Relay 回滚说明。

验收：

- 新旧凭据、密钥和缓存不串站。
- 新链路失败不清理旧 Happy 数据或本机 Codex Thread。
- 回滚后 0.1.13 可读取旧数据并启动旧工作流。
- 新链路全绿后才下线 Happy 容器。

### P7：发布 0.2.0

分支：`release/0.2.0`

门槛：

- 所有阶段 PR 已进入最新 main。
- Agent/Relay/Android 全量回归。
- 目标 VPS 部署、备份恢复、断线、升级和回滚真机演练。
- 小屏/大屏、深浅主题、最大字号、横屏截图集。
- 0.1.13 → 0.2.0 应用内更新和数据迁移。
- APK 签名、版本号、更新日志、GitHub Release 资产和 hash。

## 3. 并行冲突管理

- 并行 #48 涉及 AppDatabase、Memory 和 `IMPLEMENTATION_PLAN.md`；P0 只新增独立文档。
- Room migration 阶段必须从 #48 合并后的数据库版本继续编号，禁止预占 v27。
- `docs/zhixing/IMPLEMENTATION_PLAN.md` 只在阶段收口时小改；0.2.0 细节留在独立文档。
- 每次开分支前记录 `origin/main` commit；PR 描述列出同步 commit 和测试结果。
- 若 0.1.x 发布移动 main，先同步再继续，不把未完成 0.2.0 可见行为带入小版本。

## 4. 验收证据矩阵

| 要求 | 权威证据 |
|---|---|
| Desktop/CLI 项目和历史可见 | 真机 `thread/list/read` dump 与 Desktop 抽样截图 |
| 同一 Thread 接续 | Desktop threadId、Agent resume 结果、手机/桌面历史对比 |
| 实时 Agent 闭环 | Relay/Agent 脱敏日志、Android 录屏、turn/approval 事件 |
| E2E 保密 | Node/Kotlin向量、Relay DB/日志明文扫描、篡改失败测试 |
| 幂等与离线 | duplicate/gap/chunk/tombstone 集成测试和断网真机 |
| Codex 升级降级 | schema hash/capability matrix 与旧版本 smoke |
| Android 交互 | UI 测试、截图集、无障碍/字体/主题检查 |
| 部署与回滚 | VPS health、备份恢复记录、0.1.13 回滚实测 |
| 发布 | tag、Release、APK hash、应用内更新安装结果 |

## 5. 当前已完成的发现

- 创建 #49 并锁定 Codex-only、App Server、独立 Relay 和项目优先 UI。
- 本机 `codex-cli 0.144.0` 生成 schema，确认 thread/list/read/resume/fork/archive/unarchive/delete、turn/start/steer/interrupt、审批和增量事件。
- Desktop 运行时，独立 App Server 可读取完整目录与历史。
- Desktop App Server 是 stdio 私有子进程，独立 App Server 无法订阅其实时状态；产品边界已如实收口。
- 现有 Happy AES-GCM 没有 AAD；Wire v1 必须使用新 bundle 和域分离。

## 6. 延期但不遗忘

- OpenAI 若正式开放 Desktop remote-control endpoint，再通过 capability 增加实时 Desktop attach。
- Push provider、多个手机设备的细粒度历史 rekey 和全量旧 Happy 数据迁移可在 v1 核心稳定后评估。
- 多 Agent 编排、移动 Diff 编辑器和完整终端不属于 0.2.0。
