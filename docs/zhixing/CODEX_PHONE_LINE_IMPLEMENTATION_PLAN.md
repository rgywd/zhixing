# Work Phone-line v1：实施与验收计划

状态：执行中（2026-07-20）

## P1.2：Work 对话基础交互

- [x] 用户与 AI 文本支持选择、复制全文和引用；用户消息支持载入输入框修改后重发。
- [x] 继续复用普通聊天 Markdown；代码块沿用现有独立复制能力，不新增第二套渲染器。
- [x] 文本草稿按会话本地保存，发送失败不清空并提供原地重试。
- [x] 打开会话定位最新进展；阅读历史时不抢滚动位置，并显示新消息数量与“跳到最新”。
- [x] 通过纯逻辑单测、App JVM 单测、Kotlin 编译与 Debug APK 构建验证。

## P1.1：后台跟踪与会话归档

- [x] Core 为 session 增加 `archivedAt`，实现 active/archived 列表以及 archive/unarchive 幂等接口。
- [x] Android Room 同步归档字段；Work 首页仅展示 active，会话菜单可归档，工具栏可进入归档列表并恢复。
- [x] Work 前台服务轮询活跃会话与增量事件，常驻显示运行状态，并对 ASK、REPORT、HTML_REPORT、完成和失败可靠提醒。
- [x] 任务完成、结束或失败且仍有其他任务运行时，常驻通知镜像最近关键结果 60 秒并显示剩余任务数。
- [x] 验收：归档后默认列表消失、恢复后原消息和 Codex session 不变；Core 27 项测试、Kotlin 编译、Debug APK 构建与模拟器安装通过。

最终效果以 [`AI_ASSISTANT_VISION.md`](./AI_ASSISTANT_VISION.md)、
[`CODEX_PHONE_LINE_ARCHITECTURE.md`](./CODEX_PHONE_LINE_ARCHITECTURE.md) 和
[`CODEX_PHONE_LINE_CONTRACT.md`](./CODEX_PHONE_LINE_CONTRACT.md) 为准。

## Phase 0：旧链路下架

- [x] 删除 Android 旧 Work/Workflow 页面、状态管理和路由。
- [x] 删除 Happy 账号、恢复密钥、加密、socket 与同步协议。
- [x] 删除 Relay、App Server 客户端/catalog/supervisor 和旧 Agent 运行时。
- [x] 删除旧部署脚本、构建任务、通知与设置入口。
- [x] 通过 Room v33 显式迁移删除旧 Work/Happy/App Server/Codex catalog 表与运行时实体。
- [x] 通过 App/AI/Speech JVM 单测和 Debug APK 构建。

## Phase 1：契约与可独立验证的 Core

- [x] 建立 `work/core` 服务，并使用 SQLite 耐久保存会话、事件、命令、提问和报告。
- [x] 实现 user、per-runner、短期 session 三类 scope token；session token 可按会话撤销并在 resume 时刷新。
- [x] 实现 session/event/ask/report/runner command 的顺序、原子幂等和租约。
- [x] 实现 SSE + `afterSeq` 补拉；网络断开不影响写入。
- [x] 实现 HTML 清洗、固定模板与报告读取安全头。
- [x] 为跨会话越权、重复提交、顺序、超时答案补契约测试。

验收：无需 Android/Codex，用 API 测试完整走通“创建 → report → ask/answer → HTML → 完成”。

## Phase 2：Windows Runner 与 MCP

- [x] 建立 `work/runner`，本地配置只允许仓库白名单和 Core 凭据。
- [x] 登记 catalog、心跳、拉取命令、单会话互斥和进程状态恢复；catalog 同时兼容固定 `repos` 与显式授权
  `repoRoots`，并按周期重扫本机目录变化。
- [x] 启动 `codex exec`，解析并保存 session ID；补充消息使用 `codex exec resume`。
- [x] 使用隔离配置固定 model/effort、完全访问和三个 MCP 工具。
- [x] 实现 `report`、`ask`、`report_html` 的 stdio MCP server 与 Core client。
- [x] 进程退出时把 IDLE/FAILED 终态先写入本地 outbox，再原子提交 Core；断网或重启后持续重放。
- [x] stop 能终止子进程且不丢待处理消息。

验收：用假 Codex 进程覆盖启动/resume/stop；再用真实 Codex 完成三工具回环，普通桌面 Codex 配置无变化。

## Phase 3：Android 原生 Work

- [x] 在普通会话侧增加克制的 Work 入口；设置页增加 Work 连接卡，不新建设置首页。
- [x] Work 会话列表只展示手机创建的会话，支持离线缓存、状态和显式结束。
- [x] 新建选择缓存的 repo/model/effort，权限固定完全访问；目录选择支持按来源分组搜索，并清除已经失效的选择；
  不显示任务表单。
- [x] 详情复用普通聊天的页面骨架、Markdown 和输入框视觉组件，不复用普通 Provider 生成链路。v1 只发送文本，
  不显示尚无协议闭环的附件按钮。
- [x] 映射 report/ask/report_html/run-state；问题卡支持 1–4 题、多选和“其他”。
- [x] 报告沿用应用只读报告页；SSE 断线按 seq 补拉并以低频轮询兜底。
- [x] catalog 刷新失败只降级提示；已有缓存仍能选择 repo/model/effort。

验收：手机可以在弱网下发送第一条消息、看到汇报、回答问题、打开报告、补充消息并继续同一个 Codex session。

## Phase 4：部署、E2E 与发布门

- [x] 为 Core 提供单机 Docker Compose、备份和健康检查；不打包 OpenAI/Codex 凭据。
- [x] 为 Windows Runner 提供安装、登录、仓库白名单和自启动脚本。
- [x] 建立端到端测试：Core → fake/real Runner → 三工具 → Core readback；Android 侧由同一协议仓储消费。
- [x] 覆盖 Core 重启恢复、Runner 进程换代与命令回收、ask 超时、重复重试和 Codex 异常退出。
- [ ] 增补 SSE 强制断线和协议主版本不兼容的自动化用例。
- [ ] 从 v0.1.x APK 覆盖安装，确认普通聊天、Provider、知识空间和用户数据未回归。
- [ ] 子 BOT 按三份基线文档独立审查；存在 P0/P1 缺口则继续实施，不进入发布。

## 提交切片

1. `docs(work): define Codex phone-line contract`
2. `feat(work-core): add durable phone-line service`
3. `feat(work-runner): add Codex runner and MCP tools`
4. `feat(work): add native phone-line sessions`
5. `chore(work): add deployment and end-to-end validation`

每个切片单独可测试、可回滚；禁止在 Android 尚未使用前把半成品入口暴露给稳定版用户。

## 当前验证证据

- `npm --prefix work test`：34/34 通过，覆盖 Core 契约与重启恢复、ask 故障回滚、短期 token 撤销、命令/会话
  原子提交、Runner 终态 outbox 重放、断网 STOP 本地优先、隔离鉴权、进程换代与命令租约回收、提问超时、
  Runner 参数、resume、状态持久化、Windows 可执行文件解析，以及固定目录兼容、授权根目录发现、路径隔离、
  环境变量展开和 catalog 动态刷新。
- `npm --prefix work run e2e:real`：真实 Codex 完成 `report → ask/answer → report_html → IDLE`，Codex session ID
  `019f7fa6-3280-73e0-8c9b-050e3ab5ccff`；测试只使用临时 Git 仓库。
- `./gradlew :app:testDebugUnitTest` 与 `:app:compileDebugKotlin` 通过。
- `./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest :app:assembleDebug` 通过；产出 arm64、universal 与 x86_64 Debug APK。
- `Migration_32_33_Test` 已在 Android 15 模拟器通过，确认删除旧 Work 表且保留 Phone-line 表。
- `docker compose config` 通过；本机 Docker Desktop 引擎未启动，因此容器镜像运行验证仍待部署机执行。
