# Work Phone-line v1：实施与验收计划

状态：执行中（2026-07-20）

最终效果以 [`PRODUCT_DESIGN.md`](./PRODUCT_DESIGN.md)、
[`CODEX_PHONE_LINE_ARCHITECTURE.md`](./CODEX_PHONE_LINE_ARCHITECTURE.md) 和
[`CODEX_PHONE_LINE_CONTRACT.md`](./CODEX_PHONE_LINE_CONTRACT.md) 为准。

## Phase 0：旧链路下架

- [x] 删除 Android 旧 Work/Workflow 页面、状态管理和路由。
- [x] 删除 Happy 账号、恢复密钥、加密、socket 与同步协议。
- [x] 删除 Relay、App Server 客户端/catalog/supervisor 和旧 Agent 运行时。
- [x] 删除旧部署脚本、构建任务、通知与设置入口。
- [x] 保留 Room v31 历史表的升级兼容壳，不保留 DAO 或运行时引用。
- [x] 通过 App/AI/Speech JVM 单测和 Debug APK 构建。

## Phase 1：契约与可独立验证的 Core

- [ ] 建立 `work/core` 服务，先实现内存仓储，再接耐久数据库。
- [ ] 实现 user、runner、session 三类 scope token 与撤销。
- [ ] 实现 session/event/ask/report/runner command 的顺序、幂等和租约。
- [ ] 实现 SSE + `afterSeq` 补拉；网络断开不影响写入。
- [ ] 实现 HTML 清洗、固定模板与报告读取安全头。
- [ ] 为跨会话越权、重复提交、乱序、超时答案和 runner 重领命令补契约测试。

验收：无需 Android/Codex，用 API 测试完整走通“创建 → report → ask/answer → HTML → 完成”。

## Phase 2：Windows Runner 与 MCP

- [ ] 建立 `work/runner`，本地配置只允许仓库白名单和 Core 凭据。
- [ ] 登记 catalog、心跳、拉取命令、单会话互斥和崩溃恢复。
- [ ] 启动 `codex exec`，解析并保存 session ID；补充消息使用 `codex exec resume`。
- [ ] 使用隔离 profile 固定 model/effort、完全访问和三个 MCP 工具。
- [ ] 实现 `report`、`ask`、`report_html` 的 stdio MCP server 与 Core client。
- [ ] 进程退出时上报 IDLE/FAILED；stop 能终止子进程且不丢待处理消息。

验收：用假 Codex 进程覆盖启动/resume/stop；再用真实 Codex 完成三工具回环，普通桌面 Codex 配置无变化。

## Phase 3：Android 原生 Work

- [ ] 在普通会话侧增加克制的 Work 入口；设置页增加 Work 连接卡，不新建设置首页。
- [ ] Work 会话列表只展示手机创建的会话，支持离线缓存、状态和显式结束。
- [ ] 新建选择缓存的 repo/model/effort，权限固定完全访问；不显示任务表单。
- [ ] 详情复用普通聊天的页面骨架、Markdown、附件选择和输入框视觉组件，不复用普通 Provider 生成链路。
- [ ] 映射 report/ask/report_html/run-state；问题卡支持 1–4 题、多选和“其他”。
- [ ] 报告使用隔离只读 WebView；SSE 断线按 seq 补拉。
- [ ] catalog 刷新失败只降级提示；已有缓存仍能创建会话。

验收：手机可以在弱网下发送第一条消息、看到汇报、回答问题、打开报告、补充消息并继续同一个 Codex session。

## Phase 4：部署、E2E 与发布门

- [ ] 为 Core 提供单机 Docker Compose、备份和健康检查；不打包 OpenAI/Codex 凭据。
- [ ] 为 Windows Runner 提供安装、登录、仓库白名单和自启动脚本。
- [ ] 建立端到端测试：Android fixture → Core → fake/real Runner → 三工具 → Android readback。
- [ ] 覆盖 Core 重启、Runner 离线、ask 超时、SSE 断线、重复重试、Codex 异常退出和版本不兼容。
- [ ] 从 v0.1.x APK 覆盖安装，确认普通聊天、Provider、知识空间和用户数据未回归。
- [ ] 子 BOT 按三份基线文档独立审查；存在 P0/P1 缺口则继续实施，不进入发布。

## 提交切片

1. `docs(work): define Codex phone-line contract`
2. `feat(work-core): add durable phone-line service`
3. `feat(work-runner): add Codex runner and MCP tools`
4. `feat(work): add native phone-line sessions`
5. `chore(work): add deployment and end-to-end validation`

每个切片单独可测试、可回滚；禁止在 Android 尚未使用前把半成品入口暴露给稳定版用户。
