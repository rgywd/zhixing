---
source_commit: 253b9107c5c4fbf3dd5e5e0ea8ecfd9fe6443132
generated: 2026-07-22
---


# 模块：work

**实现 AI 与手机端异步协作的电话线服务，管理会话、报告与指令分发。**

核心模块`core`为 Node.js HTTPS 服务，使用 SQLite 持久化 Runner、会话、命令与事件，通过 Token 认证 Runner/用户；`runner`模块在 Windows 中运行，拉取命令、启动 Codex CLI 代理，并通过 MCP 工具（`report`/`ask`/`report_html`）向手机端推送消息。数据流：Runner 注册与心跳 → Core 创建会话并派发命令 → Runner 启 Codex 子进程 → 输出经 MCP 回调 Core → 用户通过手机应答提问。对外接口为 Core REST API 及 MCP 协议；依赖 `@modelcontextprotocol/sdk`、SQLite。

**修改指引**：会话生命周期与命令派发在 `core/store.js` 和 `core/server.js`，Codex 集成与 MCP 消息在 `runner/runner.js` 和 `runner/mcp-server.js`。


## 文件摘要

### `work/README.md`

描述 Zhixing Work Phone-line 项目整体架构、部署与验证流程，让 AI agent 快速了解运行时环境。

- `Core` 部署：Docker 启动 HTTPS 服务，使用 SQLite 存储
- `Windows Runner` 配置：安装 Node.js、Codex，设置白名单仓库
- `repoRoots` 发现：自动扫描项目目录，支持多种项目标记
- `验证`：`npm test` 使用假 Codex 覆盖 MCP 协议与 e2e 测试

### `work/core/index.js`

应用入口，初始化存储与服务器并监听端口。

- `WorkStore`：数据持久化
- `createWorkServer`：创建HTTP服务
- `shutdown`：优雅关闭
- `runCatchingJson`：安全解析JSON
- 环境变量：`WORK_USER_TOKEN`、`WORK_RUNNER_TOKENS`、`WORK_SESSION_SECRET`、`PORT`、`HOST`

### `work/core/server.js`

HTTP 服务器实现 API 路由，处理 Runner/Work/MCP 认证与请求解析。
- `createWorkServer`：创建 HTTP 服务器
- `readJson` / `sendJson`：请求/响应 JSON 处理
- `requireUser` / `requireRunner` / `requireSession`：身份验证
- `sanitizeReport`：HTML 报告清理
- `waitForAnswer` / `streamEvents`：实时事件与轮询
- `PROTOCOL_VERSION` / `MAX_IMAGE_BYTES` / `IMAGE_TYPES`：协议与限制常量

> 符号导航：[files/work/core/server.js.md](../files/work/core/server.js.md)

### `work/core/server.test.js`

集成测试套件，验证 Work 服务器的电话线闭环、用户交互、Runner 协议及状态持久化。

- `fixture`：启动测试服务器与 Store
- `request`：封装认证 HTTP 请求
- `registerAndCreate`：注册 Runner 并创建会话
- `uploadImage`：上传图片附件
- `runnerCommandsPath`：构建 Runner 命令 URL
- `"full phone-line API flow..."`：测试核心 API 幂等与顺序
- `"ask creation rolls back..."`：测试事务回滚
- `"Core restart times out..."`：测试重启恢复

> 符号导航：[files/work/core/server.test.js.md](../files/work/core/server.test.js.md)

### `work/core/store.js`

核心存储层，管理工作流运行器、会话、事件、命令及附件。

- `WorkStore`：封装 SQLite 存储与认证逻辑。
- `createSession`：新建会话并派发启动命令。
- `postUserMessage`：追加用户消息并触发命令。
- `listCommands`：获取待处理的运行器命令。
- `ackCommand`：确认命令状态（索取/完成/失败）。
- `appendRunnerEvent`：记录助手消息事件。
- `createAsk`：生成问题集等待用户回答。
- `answerAsk`：处理用户回答并恢复会话。

> 符号导航：[files/work/core/store.js.md](../files/work/core/store.js.md)

### `work/deploy/Dockerfile`

构建 Node.js 生产镜像，运行 core 应用，监听 8787 端口。
- 基础镜像: `node:24-alpine`
- 工作目录: `/app`，复制并安装生产依赖
- 环境变量: `HOST=0.0.0.0`, `PORT=8787`, `WORK_CORE_DB=/data/work-core.sqlite`
- 数据卷: `/data`
- 健康检查: `/healthz`
- 启动命令: `node core/index.js`

### `work/deploy/compose.yml`

定义核心服务部署，使用环境变量注入令牌与密钥，持久化数据卷。

- `core`：主服务，映射端口8787，依赖强制环境变量
- `work-core-data`：持久化数据卷

### `work/e2e/real-codex.js`

Work Phone-line 真实 Codex 端到端验收脚本，验证会话全流程。
- `USER_TOKEN` / `RUNNER_TOKEN`：认证令牌
- `PROTOCOL_HEADERS`：协议头
- `api()`：封装 API 请求
- `waitFor()`：轮询等待条件
- 流程：创建临时仓库→启动服务→注册 Runner→创建会话→等待并验证 REPORT/ASK/HTML_REPORT 事件→断言 IDLE 状态

### `work/package.json`

工作区根配置，定义脚本与依赖。

- `@zhixing/work`：私有包，ESM 模式，Node ≥22.5
- 脚本：`core`→核心入口，`runner`→运行器，`test`→单元测试，`e2e:real`→端到端测试
- 依赖：`@modelcontextprotocol/sdk`、`sanitize-html`、`zod`

### `work/runner/codex-process.js`

封装 Codex CLI 进程的启动、参数构建、事件解析与终止逻辑。
- `mcpConfigArgs`：生成 MCP 服务器配置参数
- `buildCodexArgs`：构建 Codex exec 命令参数
- `parseCodexSessionId`：从事件中提取会话 ID
- `parseCodexAssistantMessage`：提取助手消息文本
- `parseCodexTurnOutcome`：解析回合完成/失败状态
- `terminateProcessTree`：跨平台终止进程树
- `runCodex`：启动 Codex 子进程并返回控制
- `resolveCodexCommand`：解析 Codex 可执行文件路径

### `work/runner/core-client.js`

为 Runner 提供与后端核心服务的 HTTP 通信客户端，封装注册、心跳、指令拉取等操作。

- `CoreClient`：封装基础 URL、令牌与实例 ID
- `request`：通用请求方法，自动处理超时与错误
- `register`：注册 Runner 及其仓库信息
- `heartbeat`：发送心跳保活会话
- `commands`：拉取待执行的命令列表
- `downloadAttachment`：下载附件（支持重试）
- `ack`：确认命令执行结果（支持重试）
- `updateState`：更新会话状态

### `work/runner/core-client.test.js`

测试 CoreClient 在遇到 503 错误时自动重试下载附件。

### `work/runner/index.js`

工作调度器入口，加载配置并启动 WorkRunner 主循环。
- `WorkRunner.fromConfig`：根据配置创建运行实例
- `config`：解析自环境变量或默认 JSON 配置文件
- 信号处理：`SIGINT`/`SIGTERM` 触发 `runner.stop()`

### `work/runner/install-autostart.ps1`

安装登录自启动计划任务，调用 `start-runner.ps1`。
- `$Config`：配置文件路径（默认 `work-runner.json`）
- `$launcher`：启动脚本路径
- `$action`：执行 `pwsh.exe` 并传递参数
- `$trigger`：用户登录时触发
- `$settings`：失败重启3次，间隔1分钟
- `Register-ScheduledTask`：注册任务“Zhixing Work Runner”

### `work/runner/mcp-server.js`

MCP 服务器，注册 report/ask/report_html 工具，向手机端发送消息并管理收件箱游标。
- `report`：即时汇报 Markdown 消息
- `ask`：发送结构化选择题
- `report_html`：发送 HTML 报告
- `post`：带重试的 HTTP 请求
- `stageCursor`/`acknowledgePendingCursor`：游标持久化

### `work/runner/phone-hook-profile.js`

生成用于 hook 的手机配置 TOML 文件。  
- `ensurePhoneHookProfile`：创建 zhixing-phone 的 hooks 配置，支持 node 与 hook 脚本路径。

### `work/runner/phone-stop-hook.js`

在 Stop 事件时写入带会话信息的标记文件。

- `marker`：包含 version、event、workSessionId、codexSessionId、turnId 的 JSON 对象
- 环境变量：`ZHIXING_WORK_HOOK_OUTBOX`（输出目录），`ZHIXING_WORK_SESSION_ID`（会话 ID）
- 输入字段：`hook_event_name`(== "Stop")、`session_id`、`turn_id`

### `work/runner/repo-catalog.js`

仓库目录的构建与校验，整合显式配置及自动发现，生成仓库表与指纹。

- `buildRepositoryCatalog`：从配置组装仓库列表
- `validateRepositoryConfig`：校验配置完整性
- `repositoryCatalogFingerprint`：生成仓库列表指纹
- `expandPath`：展开路径中的环境变量和~别名

### `work/runner/repo-catalog.test.js`

测试 `repo-catalog.js` 的仓库发现、验证、指纹和刷新功能。

### `work/runner/runner.js`

作为核心服务与 Codex 进程之间的桥梁，管理会话、附件和状态同步。

- `WorkRunner`：核心类，管理 Codex 会话生命周期
- `isolatedCodexEnv`：为 Codex 子进程构建隔离的环境变量
- `PHONE_DEVELOPER_INSTRUCTIONS`：手机端开发者指令常量
- `start()`：启动轮询和心跳，确保目录就绪
- `startCommand`：启动 Codex 进程并处理附件
- `monitorCommand`：监控进程退出并提交最终状态
- `downloadAttachments`：下载并校验附件文件
- `stopCommand`：终止运行中的 Codex 进程

> 符号导航：[files/work/runner/runner.js.md](../files/work/runner/runner.js.md)

### `work/runner/runner.test.js`

测试 WorkRunner 与 Codex CLI 集成、会话恢复、重试及进程清理的完整生命周期。

> 符号导航：[files/work/runner/runner.test.js.md](../files/work/runner/runner.test.js.md)

### `work/runner/start-runner.ps1`

启动工作运行器，安装依赖并执行Node.js入口。  
- `$Config`：指定配置文件路径，默认`work-runner.json`。  
- `$workRoot`：项目根目录。  
- `npm ci`：无`node_modules`时安装依赖。  
- `$env:WORK_RUNNER_CONFIG`：设置环境变量指向配置。  
- `node runner/index.js`：启动运行器。

### `work/runner/state.js`

管理会话/转换/事件队列的持久化状态存储。
- `RunnerState`：核心类，维护sessions/outbox/eventOutbox
- `get`/`set`/`delete`：会话CRUD
- `enqueueTransition`/`transitions`/`removeTransition`：转换队列
- `enqueueEvent`/`events`/`removeEvent`：事件队列
- `persist`：原子写入文件

### `work/runner/uninstall-autostart.ps1`

删除 Windows 计划任务“Zhixing Work Runner”。
- `Unregister-ScheduledTask`：移除计划任务

### `work/runner/work-runner.example.json`

Work Runner 的示例配置文件，定义连接核心服务、挂载仓库及模型参数。
