# 知行 Codex 原生会话打磨实施计划

状态：Implementation verified；首轮独立审查未通过后的缺口已修复，等待全新审查轮
日期：2026-07-19
分支：`feat/49-codex-chat-parity`
验收合同：[`CODEX_NATIVE_ARCHITECTURE.md`](./CODEX_NATIVE_ARCHITECTURE.md)

## 1. 当前基线

`v0.2.0` 已完成 Wire v1、Codex Catalog、项目/历史入口和基本 Runtime Bridge；`v0.2.1` 修复 Agent
常驻与项目目录整理。立项时的缺口集中在 Thread 会话本身：协议被压成纯文本、历史一次性同步、输入区重新实现，
模型/思考/附件/Skill/上下文/权限和既有 Markdown/思考/工具渲染均未形成完整闭环。

本计划不重新建设 Catalog、Relay 或项目管理，而是在现有架构上补齐“Codex 是知行聊天运行时”这一层。

## 2. 交付规则

- 不使用 worktree；从最新 `main` 创建短分支。
- 文档合同先于代码提交，后续代码与状态必须回填本计划。
- 每阶段 RED → GREEN → focused verify → commit；不以 UI TODO 代替协议闭环。
- 普通 Provider Chat 行为必须保持不变。
- 未被当前 App Server 运行时验证的接口必须 capability-gate 或延期。
- 准备收口时启动独立子 BOT，按架构文档第 15 节逐条审查；未通过则继续修复。

## 3. Phase A：文档与事实基线

状态：完成

范围：

- 重写 `CODEX_NATIVE_ARCHITECTURE.md`，完整记录两次调研、目标交互和验收矩阵。
- 更新 `ZHIXING_WIRE_V1.md` 的会话、catalog、附件和事件合同。
- 将 `NATIVE_WORKFLOW.md` 与 `AGENT_DESIGN.md` 明确为 Happy/Claude 历史与回滚材料。
- 更新 `README.md`、`AGENTS.md` 和本计划，消除“工作模块是监控器”的描述。

验证：

- 文档中每个 P0 风险均对应字段、测试或明确延期。
- 全仓搜索不存在互相冲突的 active 0.2.x 产品定义。

## 4. Phase B：Agent/App Server 保真适配

状态：完成

主要文件：

- `agent/src/codex/appServerClient.ts`
- `agent/src/wire/runtimeBridge.ts`
- `agent/src/catalog/threadDetail.ts`
- `agent/src/catalog/types.ts`
- `agent/src/wire/types.ts`

RED：

- Thread start/resume 的实际 model/effort/tier/profile/sandbox 被丢弃。
- userMessage 图片被压成 `[图片]`，reasoning/tool 只剩摘要。
- 只收到 agentMessage delta，其他 Runtime notification 无事件。
- Turn 只能接收 text。

GREEN：

- 提供 model/profile/skill/plugin/app catalog 请求和版本化 payload。
- Turn input 支持 text/localImage/skill/mention，通用附件使用受控上传引用。
- Thread snapshot 保存结构化 Item 和 sanitized raw。
- Runtime event 覆盖正文、思考、计划、命令、文件、MCP、用量、设置、reroute、错误和审批。
- start/resume/turn 响应回传 App Server 确认的 runtime settings。

验证：

```powershell
Set-Location agent
npm test
npm run typecheck
npm run build
```

## 5. Phase C：Wire 与 Room 合同

状态：完成

主要文件：

- `app/.../data/workflow/wire/`
- `app/.../data/workflow/codex/CodexCatalogModels.kt`
- `app/.../data/workflow/codex/CodexCatalogRepository.kt`
- Room entity/DAO/migration/schema

RED：

- 大历史超过 Relay 单包上限。
- 快照缺块时覆盖旧历史。
- Runtime delta 重复或乱序造成重复文本。
- 旧 `CodexItem.text` 缓存无法兼容升级。

GREEN：

- Thread snapshot chunk 具备 revision、hash、原子提交和缺块重试。
- Runtime event 带 sequence，Android reducer 幂等应用并检测 gap。
- 结构化 Item、runtime settings、catalog 和 token usage 增量持久化。
- v0.2.1 Room 30 通过 `30 -> 31` 增量迁移保留数据，详情 revision 使用新表，旧 text 只作为 fallback。

## 6. Phase D：Codex 消息投影器

状态：完成

主要文件：

- 新增 `data/workflow/codex/CodexMessageProjector.kt`
- 新增 `data/workflow/codex/CodexRuntimeItemReducer.kt`
- 新增 projector fixture/tests
- `ai/ui/Message.kt` 仅在确有共享缺口时做行为兼容扩展

RED：

- 历史 snapshot 和实时 event replay 得到不同 UI。
- reasoning/tool 顺序被重排。
- tool delta 没有按 Item ID 合并。
- turn 完成后思考或工具仍处于 loading。

GREEN：

- Codex Item 映射到 Text/Image/Reasoning/Tool/Note。
- snapshot 与等价事件 replay 输出深度相等。
- 复用 `MessagePartsBlock`、Markdown、Chain of Thought 和 Tool fallback。
- opaque Item 只隔离自己，不破坏 Thread。

验证：

```powershell
./gradlew.bat :app:testDebugUnitTest --tests "*CodexMessageProjectorTest*"
```

## 7. Phase E：共享 Timeline 与 Composer

状态：完成

主要文件：

- `ui/components/ai/ChatInput.kt`
- `ui/hooks/ChatInputState.kt`
- `ui/pages/chat/ChatPage.kt`
- `ui/pages/chat/ChatList.kt`
- 新增通用 Composer option/controller 类型

实施原则：

- Codex 通过 `CodexChatComposer` 适配现有 `ChatInput`；普通 Provider Chat 的默认发送/停止语义保持不变。
- Codex Thread 直接复用 `MessagePartsBlock`，不复制 `ChatMessage` 的 Markdown、思考和工具渲染逻辑。
- Model/Reasoning UI 数据驱动；Provider Model 和 Codex model catalog 分别适配。
- Codex reasoning 支持运行时广播的 `max/ultra`，不修改 Provider 语义。

RED：

- 普通聊天模型、思考、附件或发送行为回归。
- Codex 页面仍出现 `OutlinedTextField` 或 `CodexItemRow`。
- 最大字体、IME 或横屏遮挡输入。

GREEN：

- 普通 Chat 与 Codex Thread 使用同一输入容器和消息渲染。
- Codex 输入支持图片、文件、Skill、model、effort、Fast、profile 和 context usage。
- 运行中发送为 steer，空闲为 turn，停止为 interrupt。

## 8. Phase F：附件、恢复与错误状态

状态：完成

范围：

- Wire E2E attachment upload/download。
- Agent 临时文件目录、路径校验、大小/MIME/hash、TTL 清理。
- Thread detail 自动重试、明确错误、旧 revision 保留。
- 离线草稿、开发机离线和 Desktop takeover 确认。

必须测试：

- 路径逃逸、超限、hash 错误、重复 upload、过期清理。
- detail 首次失败后自动恢复。
- chunk 缺失/乱序/冲突不覆盖旧历史。
- takeover 未确认时不启动第二个写入 Runtime。

## 9. Phase G：跨层回归与真机验收

状态：完成

自动化：

```powershell
Set-Location agent
npm test
npm run typecheck
npm run build

Set-Location ..
./gradlew.bat :app:testDebugUnitTest
./gradlew.bat :app:assembleDebug

Set-Location relay
npm test
npm run typecheck
npm run build
```

真机检查：

1. 打开 Desktop 历史 Thread，看到完整用户/助手/思考/工具/图片。
2. 选择当前模型支持的 effort、Fast 和权限，发送并确认服务端实际值。
3. 发送截图和文件，Codex 能读取且历史重开后仍正确显示。
4. 实时正文/思考/命令/文件修改进入同一聊天流。
5. Relay/Agent 断开再恢复，不重复文本、不丢历史。
6. 最大字体、深浅主题、横屏与输入法不遮挡。

## 10. 独立审查门

每次主线准备结束时，启动独立子 BOT：

- 只读架构合同、diff、测试输出和运行证据；
- 逐条标记 PASS / FAIL / NOT PROVEN；
- 任一 P0 为 FAIL 或 NOT PROVEN 时不得交付；
- 主线修复后重新启动新的审查轮，直到全 P0 PASS。

## 11. 当前记录

- 2026-07-19：完成 Desktop/CLI/App Server 协议同族和能力审计。
- 2026-07-19：完成知行 ChatInput/UIMessage/ChatMessage/Markdown/Reasoning/Tool 流程审计。
- 2026-07-19：确认当前缺口是适配器和页面旁路，而不是 Codex 不保存历史。
- 2026-07-19：创建 `feat/49-codex-chat-parity`；远端 fetch 因本机 Schannel TLS 握手失败，
  创建分支前本地 `main...origin/main` 记录为 `0 0`，基线 `c7b041587`。
- 2026-07-19：Agent 改为保真传递 App Server Item、运行时设置、catalog、审批与交互请求；
  附件上传/下载增加哈希、大小、路径授权和 TTL，历史改为带 revision/hash 的分块原子提交。
- 2026-07-19：Room schema `29 -> 30`；Android 新增 runtime catalog/settings、附件映射和草稿缓存，
  snapshot 与 runtime event 统一通过 `CodexRuntimeItemReducer` 和 `CodexMessageProjector`。
- 2026-07-19：Codex Thread 切换到共享 `ChatInput` 容器和 `MessagePartsBlock`，接入图片/文件、
  model、effort、Fast、权限、Skill、插件/App 可用性、上下文用量、steer/interrupt 与内联审批。
- 2026-07-19：首轮独立审查结论为 FAIL：运行中补充要求不可达，plan/file/MCP/reroute 事件、取消审批、
  动态目录与上传回执校验、详情 revision 防回退及跨层证据不足；未将该轮误记为通过。
- 2026-07-19：按首轮审查逐项修复：共享 `ChatInput` 在 Codex 运行中有草稿时发送 steer、无草稿时停止；
  Agent 补齐 plan/file/MCP/reroute，catalog 值与 Skill/附件回执双端校验，插件/App 失败显式进入 capability；
  审批和 request_user_input 均支持 cancel；详情带 revision 回执，Android 仅在相同 revision 已原子落库后确认成功。
- 2026-07-19：补齐 schema golden fixture、共享 Android→Wire→Agent 命令 fixture、全类型多 Turn 历史/实时等价、
  设置/用量/reroute 合并、普通 ChatInput 无回归、缺块和旧 revision 等测试。
- 2026-07-19：第二轮审查前自动化基线：Agent 17 个测试文件/69 项，Relay 3 个测试文件/7 项，
  Android 48 个测试类/223 项（0 failure、0 skipped）；三端 typecheck/build 与 debug APK 构建通过。
- 2026-07-19：Android 35 x86_64 模拟器完成浅色、深色、大字体、横屏与 IME 场景检查；
  证据保存在本地 `build/ui-audit/codex-final-*.png`。检查期间发现并修复运行中 reasoning
  在开发机/手机时钟偏差下出现负计时的问题。
- 2026-07-19：覆盖安装验证发现已发布的 Room 30 不能被改写；新增正式 `30 -> 31` AutoMigration，
  恢复 30 号 schema 并生成 31 号 schema。`Migration_30_31_Test` 通过 ADB/AndroidJUnitRunner
  在 Android 35 模拟器执行（1/1）；保留旧 0.2.1 数据覆盖安装后正常恢复 `RouteActivity`，
  logcat 无 Room identity/SafeMode/FATAL 错误，证据为本地 `build/ui-audit/codex-room31-upgrade.png`。

## 12. 延期项

- 官方 Desktop attach endpoint：仅在公开、可自托管且运行探针通过后评估。
- 通用二进制文件的模型原生理解：P0 只保证受控落盘 + mention/path。
- 新的 Codex 专用 Tool renderer：先使用通用 Tool fallback，真实高频后再做专用卡片。
- 多 Agent 编排、完整终端和移动 Diff 编辑器不属于本计划。
