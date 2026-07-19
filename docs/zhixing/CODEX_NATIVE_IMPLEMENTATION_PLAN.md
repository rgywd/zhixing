# 知行 Chat/Work 双模式与 App Server 直连实施计划

状态：Executing（Android/loopback 闭环完成；Tailscale WSS 真机链路与发布验收待完成）
日期：2026-07-19
分支：`feat/66-work-mode-direct`
跟踪：[GitHub Issue #66](https://github.com/rgywd/zhixing/issues/66)
产品合同：[`CODEX_NATIVE_ARCHITECTURE.md`](./CODEX_NATIVE_ARCHITECTURE.md)
协议合同：[`CODEX_APP_SERVER_CONTRACT.md`](./CODEX_APP_SERVER_CONTRACT.md)

## 1. 交付目标

在不重写知行聊天 UI 的前提下，把 0.2.2 的独立 Work 产品和 Wire 消息网关收敛为：

```text
同一个 Chat 页面壳
  ├─ Chat runtime：现有 Provider / Conversation / ChatService
  └─ Work runtime：当前仓库 / Codex Thread / App Server JSON-RPC

Android -> Tailscale WSS -> Codex App Server
                            └─ 薄 supervisor 只管生命周期、仓库元信息和附件
```

完成后，用户在现有侧边栏同一行切换 Chat/Work，Work 分组就是仓库，并直接使用现有聊天页开展 Codex 对话。

## 2. 当前基线

`v0.2.2` 已发布：

- Room 31 Codex Project/Thread/Item/runtime cache；
- `CodexRuntimeItemReducer` 与 `CodexMessageProjector`；
- `CodexChatComposer` 对现有 `ChatInput` 的部分复用；
- 模型、effort、Fast、权限、Skill、附件和审批的 Wire 适配；
- Agent 70 项、Relay 7 项、Android 223 项测试基线。

需要保护：

- `UIMessage/UIMessagePart` 和历史/实时等价投影；
- 现有 `ChatInputState`、`ChatInput`、Markdown、Reasoning、Tool 和附件；
- 普通 Chat 的 Conversation、Folder、Assistant、Provider、Workspace 和设置行为；
- Room 30→31 已发布迁移和旧数据；
- 0.2.2 Wire/Happy 回滚数据。

需要替换：

- Drawer 独立“工作”菜单；
- `CodexWorkflowPage` 项目/历史首页；
- `CodexConnectionSettingsPage` 独立设置页；
- Android `WireRelayClient` 在默认 Work 数据面中的角色；
- `WireCodexRuntimeBridge` 的消息翻译和状态复制；
- Relay 作为消息必经层。

## 3. 交付规则

- 不创建新的 worktree；从最新 `main` 使用短分支。
- 文档合同先提交，后续实现状态和验证结果持续回填。
- 每阶段按 RED → GREEN → focused verify → commit 推进。
- 一个阶段不能同时删除回滚面和切换新数据面。
- Catalog、Tailscale 或实验 API 失败必须有缓存/只读降级，不得用 loading 锁死 UI。
- 普通 Chat 回归是每个 UI 阶段的必过门。
- 发布前启动全新独立审查 BOT；P0 只有代码、自动化与真实运行证据同时存在才可 PASS。

## 4. Phase A：文档与事实基线

状态：完成

范围：

- [x] 建立 #66，记录最终产品与架构决定。
- [x] 重写 `CODEX_NATIVE_ARCHITECTURE.md` 为 Chat/Work 双模式目标合同。
- [x] 新增 `CODEX_APP_SERVER_CONTRACT.md`，定义 WSS、认证、JSON-RPC、缓存和降级。
- [x] 重写本实施计划。
- [x] 更新 `PRODUCT_DESIGN.md`、`AGENTS.md` 和 README 的 active 产品描述。
- [x] 将 Wire/Relay 文档明确标记为 0.2.0–0.2.2 historical/rollback。
- [x] 对文档执行一致性和对抗审查，修复冲突后提交 docs baseline。

事实证据：

- 本机 `codex-cli 0.144.0`；
- `codex app-server --listen ws://IP:PORT`、capability-token 和 signed bearer auth 已存在；
- App Server WebSocket 官方状态为 experimental/unsupported；
- Tailscale 当前未安装，Serve 对 App Server Upgrade/header 必须真实探针；
- 当前 `main`/`v0.2.2` 为 `907b0554516681c18df4fbe33ee09d6f025f7c85`。

验证：

```powershell
rg -n "active|实施基线|唯一.*基线|工作.*入口|Wire.*稳定协议" docs/zhixing AGENTS.md README.md README_ZH_CN.md README_ZH_TW.md
git diff --check
```

## 5. Phase B：直连 transport contract

状态：完成

目标：先用纯 Kotlin/OkHttp 建立可测试的 App Server JSON-RPC client，不接 UI、不切默认路由。

新增建议：

```text
app/src/main/java/me/rerere/rikkahub/data/work/
  AppServerTransport.kt
  AppServerJsonRpcClient.kt
  AppServerProtocol.kt
  AppServerCompatibility.kt
  WorkConnectionStore.kt
  WorkConnectionState.kt
```

RED：

- bearer 未在 Upgrade 发送；
- initialize 前调用业务 RPC；
- response、notification 和 server request 混淆；
- 断线后挂起请求泄漏或重复 socket；
- `-32001` 紧循环；
- Catalog 失败导致模型列表为空。

GREEN：

- OkHttp WebSocket 单连接 client；
- request ID correlation、server request response 和 notification flow；
- initialize/initialized 状态机；
- timeout、指数退避+jitter、前后台恢复和显式断开；
- capability/schema compatibility gate；
- bundled model presets + cached catalog + background refresh merge；
- Keystore 加密 connection token。

Focused verify：

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*AppServer*" --tests "*WorkConnection*"
```

阶段提交：`feat(work): add direct Codex app-server transport`

## 6. Phase C：薄 supervisor 与开发机探针

状态：进行中（loopback supervisor 已完成；Tailscale 登录与 Serve 真机探针待完成）

目标：在现有 Wire 翻译网关旁新增独立 supervisor 能力和开发机探针。此阶段不改动旧 Work
默认数据面，也不删除、停用或降级现有 Wire 网关；真正的切换只允许在 Phase G 的 direct
闭环和回滚验证通过后发生。

主要文件：

```text
agent/src/supervisor/
  appServerProcess.ts
  statusServer.ts
  repositoryCatalog.ts
  attachmentStore.ts
  tailscaleProbe.ts
agent/src/cli.ts
agent/package.json
```

RED：

- supervisor 能看到或代理聊天消息；
- App Server 绑定非 localhost；
- transport token 出现在命令行/日志；
- 任意路径上传、路径逃逸或无 TTL；
- Tailscale Serve 不转发 Authorization/Upgrade 却被误判为可用。

GREEN：

- 固定版本 App Server 生命周期、health、schema hash；
- token file 创建/轮换和最小权限；
- 受控仓库候选；
- 附件 MIME/大小/hash/路径/TTL；
- Tailscale 安装状态、Serve 配置和 WSS 探针；
- 诊断输出无消息正文和密钥；
- 旧 Wire 翻译进程、命令和健康检查保持可用，现有 0.2.2 Work 行为不回归。

真实开发机验证：

1. 安装并登录 Tailscale；
2. App Server 监听 localhost；
3. 配置 `tailscale serve --bg`；
4. 从 tailnet 客户端用 bearer 完成 initialize；
5. Wi-Fi/5G 分别完成 `thread/start + turn/start`；
6. 重启 Windows 后 App Server 与 Serve 恢复；
7. 记录 direct/DERP 路由和延迟，不把 DERP 不稳定误报为应用协议错误。

验证：

```powershell
Set-Location agent
npm test
npm run typecheck
npm run build
```

阶段提交：`feat(agent): add local Codex supervisor beside Wire`

## 7. Phase D：共享 Chat 页面壳

状态：完成

目标：不再维护独立 Codex Thread Scaffold，把普通 Chat 与 Work Chat 的差异收敛为 runtime/controller。

主要文件：

```text
ui/pages/chat/ChatPage.kt
ui/pages/chat/ChatDrawer.kt
ui/pages/chat/ChatList.kt
ui/components/ai/ChatInput.kt
ui/pages/workflow/codex/CodexThreadPage.kt
ui/pages/workflow/codex/CodexWorkflowVM.kt
```

设计：

```kotlin
interface ChatRuntimeController {
    val screenState: StateFlow<ChatScreenState>
    val inputState: ChatInputState
    fun send(parts: List<UIMessagePart>)
    fun stop()
    fun newConversation()
    fun resolveToolRequest(...)
}
```

- Provider controller 继续使用现有 `ChatVM/ChatService`；
- Work controller 使用 `AppServerJsonRpcClient`、Codex reducer/projector 和 Room cache；
- `ChatPageShell` 只接收统一时间线、Top Bar、Composer options 和动作；
- `CodexThreadPage` 最终只做 route 参数到 Work controller 的薄适配，不能再声明自己的 Scaffold/输入框。

RED：

- Chat 和 Work 存在两个 Scaffold/附件 Sheet/输入框；
- Work 标题、IME、安全区、语音或附件行为与普通 Chat 不一致；
- Work Part 绕过 Markdown/Reasoning/Tool renderer；
- 普通 Chat 模型和发送语义回归。

GREEN：

- 同一个 Top Bar、timeline、composer 和 + Sheet；
- Work runtime 支持 text/image/file/model/effort/Fast/profile/context；
- 空闲发送 turn/start，运行中有草稿 steer，无草稿停止；
- `/` CompletionProvider 提供 command/Skill/plugin/mention；
- approval/request_user_input 内联现有 Tool UI。

验证：

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*ChatInput*" --tests "*Codex*" --tests "*ChatRuntime*"
```

阶段提交：`feat(work): reuse the native chat page for Codex`

## 8. Phase E：侧边栏双模式与仓库分组

状态：完成

目标：删除独立 Work 首页，把模式与仓库切换放入现有 Drawer。

主要文件：

```text
ui/pages/chat/ChatDrawer.kt
ui/pages/chat/ChatDrawerVM.kt
data/model/Folder.kt                # Chat 语义保持不变
data/work/WorkRepositoryConfig.kt   # Work 仓库配置，不复用 FolderEntity
RouteActivity.kt
```

RED：

- Chat/Work 切换单独占一行；
- Work 仍存在 Drawer 菜单项或项目首页；
- Work 仓库和 Chat Folder 共用实体导致语义/删除串线；
- 添加仓库自动导入全部历史 CWD；
- 切换仓库丢草稿或创建大量空 Thread。

GREEN：

- 分组栏同一 Row：左侧可滚动 folders/repositories，右侧固定紧凑模式开关；
- Chat 下现有 FolderBar 与 ConversationList 无行为变化；
- Work 下左侧为用户明确添加的仓库，主体为当前 Thread；
- 没有仓库时只显示“添加仓库”最小空状态，不创建假 Thread；
- 每仓库保存当前 Thread、草稿和模型/effort/权限/Fast；
- 无 Thread 时同一聊天壳空白展示，第一条消息惰性创建；
- 顶部新建显式创建新 Thread；
- `DrawerActions` 删除独立工作入口；
- Work 历史目录不展示。

阶段提交：`feat(work): add inline Chat and Work mode switch`

## 9. Phase F：现有设置中的 Work 卡片

状态：完成

目标：删除独立 Work 设置首页，在现有 `SettingPage` 内完成连接、仓库和诊断管理。

主要文件：

```text
ui/pages/setting/SettingPage.kt
ui/pages/setting/components/WorkSettingsCard.kt
ui/pages/workflow/codex/CodexConnectionSettingsPage.kt  # 删除
RouteActivity.kt                                        # 删除平行设置 route
```

GREEN：

- 使用现有 `CardGroup`、Dialog、BottomSheet；
- 卡片显示连接、当前机器、仓库数量、CLI/App Server/Tailscale 状态；
- 添加/编辑仓库不离开设置体系；
- 连接 token 不回显；
- 诊断可复制但已脱敏；
- 旧 Wire/Happy 只读入口保留在卡片的回滚区域。

阶段提交：`feat(settings): integrate Work into the existing settings`

## 10. Phase G：直连切换与缓存迁移

状态：完成（direct 默认路径与旧数据隔离完成；Tailscale 外网验证仍由 Phase C 阻断）

目标：在 direct 真实闭环通过后，把 Work 默认数据面从 Wire 切到 App Server。

规则：

- 新版 Work 会话选择、草稿和仓库配置写入独立原子文件 `work-ui.json`，不改写已发布 Room schema 31；
- 保留旧 Codex Item cache 并按 `(connectionId, threadId)` 映射；
- direct snapshot 成功前不覆盖旧缓存；
- Wire/Relay/旧 Agent 保持可切回只读，不在本阶段删除；
- 切换开关只用于发布前/故障回滚，不暴露成普通用户长期设置。
- Phase C 新增的 supervisor 在本阶段成为 direct 路径的本机门卫；只有 direct 闭环、旧版覆盖升级和
  回滚门全部通过后，旧 Agent 才退出默认消息路径。

验证：

- 0.2.2 APK 数据覆盖安装；
- Room migration instrumentation test；
- 旧缓存离线可读，direct 成功后原子刷新；
- direct 断线不会回退到 Wire 双写；
- 普通 Chat 数据完全不变。

阶段提交：`feat(work): cut over Work to direct app-server`

## 11. Phase H：删除旁路与完整验收

状态：进行中（产品旁路已隐藏，历史/回滚代码保留一个发布周期）

目标：删除产品运行时中已无引用的独立 Work UI 和翻译逻辑，但保留明确的历史/回滚材料。

候选删除（必须先由引用扫描和回滚决定）：

- `CodexWorkflowPage` 的项目/历史/搜索主页；
- 独立 Work 设置 route/page；
- 默认运行路径中的 `WireRelayClient`；
- Agent `WireCodexRuntimeBridge` 消息翻译；
- Relay 发布包和 CI 默认回归项（稳定一个发布周期后另开清理提交）；
- 旧 Happy 写入 UI。

不得在未验证 direct 回滚前删除：

- 0.2.2 tag/release；
- Room 旧 schema；
- 用户 Wire/Happy 数据；
- 运维回滚文档。

完整自动化：

```powershell
Set-Location agent
npm test
npm run typecheck
npm run build

Set-Location ..
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:lintDebug
./gradlew.bat :app:assembleDebug
```

真机矩阵：

1. Chat/Work 切换不新增一行，Chat 分组和会话不丢；
2. Work 仓库切换、添加和当前 Thread 恢复；
3. 空白新建，无任务表单；
4. 文本、拍照、照片、文件、语音；
5. 模型、effort、Fast、权限、`/` Skill；
6. Markdown、Reasoning、Tool、审批、停止和 steer；
7. Catalog 失败、Wi-Fi/5G 切换、后台恢复、开发机休眠；
8. 浅色、深色、大字体、横屏、IME；
9. Android→App Server 消息链路无 Relay/翻译 Agent；
10. 0.2.2 覆盖升级与回滚。

## 12. 独立审查门

发布准备前启动新的独立审查 BOT，逐条读取：

- `CODEX_NATIVE_ARCHITECTURE.md` 第 12 节；
- `CODEX_APP_SERVER_CONTRACT.md` 第 14 节；
- 本计划状态、diff、测试输出、真机截图和网络探针。

每项只能是：

- `PASS`：实现、自动化和运行证据齐全；
- `FAIL`：与合同冲突；
- `NOT PROVEN`：实现可能存在但证据不足。

任一 P0 为 FAIL/NOT PROVEN，主线继续修复并启动全新审查轮，直到全 P0 PASS。

## 13. 发布流程

1. `feat/66-work-mode-direct` 只经 PR 合入 `main`；
2. CI 与真机验收完成后，从最新 `main` 创建下一个 `release/x.y.z`；
3. 冻结分支只接收发布修复；
4. release PR 合回 `main`；
5. 在合并后的 `main` 创建 `vX.Y.Z`；
6. 验证 APK、更新清单、hash 和应用内更新；
7. 至少保留 0.2.2 Wire 回滚能力一个发布周期，再单独规划服务器与死代码下线。

## 14. 延期项

- Work 历史会话目录、归档、重点和全量搜索；
- 多开发机自动切换；
- Tailscale 自动注册/管理账号；
- 通用终端、文件浏览器、移动 Diff；
- Claude Code；
- 公开 Internet/Funnel 访问；
- App Server WebSocket 稳定前的无版本锁动态升级。

## 15. 执行记录

- 2026-07-19：产品讨论收口，确认 Chat/Work 为同一侧边栏中的两种模式；Work 分组直接对应仓库。
- 2026-07-19：确认 Work 直接复用普通聊天页、现有 + Sheet、模型/思考和 `/` completion；不再建立独立页面体系。
- 2026-07-19：确认设置只在现有 `SettingPage` 增加 Work 卡片，不保留独立 Work 设置首页。
- 2026-07-19：创建 Issue #66 和分支 `feat/66-work-mode-direct`。
- 2026-07-19：本机核验 `codex-cli 0.144.0` App Server WebSocket/auth/daemon；确认 Tailscale 尚未安装。
- 2026-07-19：完成 Phase A 文档基线；独立审查首轮发现发布顺序、active runtime contract、
  supervisor 传输安全和 Room 版本四项阻断，全部修复后复审 PASS。
- 2026-07-19：完成 Phase B 旁路 Android transport：WSS bearer、initialize、RPC correlation、
  notification/server request、断线清理、退避、Keystore 连接存储、静态模型兜底和 schema 兼容门；
  focused Android 测试 8 项通过。
- 2026-07-19：Phase C 已完成 loopback App Server 生命周期、独立 supervisor bearer、状态/重启、
  受控附件、Tailscale 探针和 Wire 并行启动；Agent 20 个测试文件 75 项、typecheck/build 全绿，
  真实本机探针得到 ready=200、附件上传=201、哈希一致、删除=200。Tailscale 安装仍需 Windows UAC 完成。
- 2026-07-19：完成 Phase D–F：Chat/Work 共用 `NativeChatScaffold`、原生消息 renderer 与输入组件；
  Drawer 同行模式开关、显式仓库分组和现有 SettingPage Work CardGroup 已在模拟器验证。旧版数据只从
  Work 设置卡片进入只读核对，不进入新版首页。
- 2026-07-19：完成 loopback direct 对话闭环：模型、effort、上下文、Fast/权限面板、`/` 完成项、
  text、系统相册图片、文件上传、Markdown、Reasoning、Tool 与 inline approval 均走官方 App Server。
  图片识别实测返回截图时间 `5:37`；文件实测读取并返回文稿一级标题。
- 2026-07-19：修复三项真实运行缺陷：稀疏 `turn/completed` 不再抹掉流式 Items；新 Thread 清空旧
  token usage；App Server 事件改为广播并按 threadId 过滤，避免多个仓库控制器互相吞回复。
- 2026-07-19：Catalog 仍采用客户端 bundled presets 乐观启用、后台合并服务端列表；标题、附件清单
  和结构化 Skill/App/Plugin 输入统一在投影边界清洗，不向用户泄露协议内部文本。
- 2026-07-20：安装最新 x86_64 Debug APK 后完成进程级复验：Work 面板在 1080×2400 下可滚动访问
  仓库、Fast 与三档权限；唯一消息 `EVENT_BUS_OK` 经 App Server 实时返回，强制停止并重启应用后仍可从
  当前 Work Thread 恢复。Android 单测/构建、Agent 测试/typecheck/build 均通过；Tailscale WSS 真机链路
  仍等待 Windows UAC 安装与登录，因此不进入发布阶段。
- 2026-07-20：按独立审查阻塞项完成 release-blocker 收口：Chat/Work 共用 `NativeChatTimeline` 与
  `ChatAttachmentActions`；仓库文件级增删改选/恢复、跨 Thread turn 隔离、运行中 Turn 恢复、内联
  request_user_input、`-32001` 退避、断线自动重连、Supervisor schema 兼容门禁和运行参数拒绝回滚均进入
  生产路径并补测试。Android 260 项单测、Debug APK、Compose 仪器测试编译与模式切换真机测试全绿；
  最新 APK 经 loopback compatibility gate 实测收到 `DIRECT_GATE_OK`，普通 Chat 未建立 Work WebSocket。
- 2026-07-20：按 Codex 0.144.0 `generate-ts --experimental` 的 `v2/UserInput` 修正通用文件：新消息发送
  结构化 `mention { name, path }`，旧文本清单仅做历史兼容读取。
- 2026-07-20：独立复审补齐官方 plan/command/file/MCP/error/model-reroute 与未知 opaque 事件累计；
  `request_user_input` 在 Item 迟到时创建可回答的原生投影；参数拒绝只回退命中字段；未配置可选
  Supervisor 时保持文本可写，仅禁用需要受控上传的附件。
