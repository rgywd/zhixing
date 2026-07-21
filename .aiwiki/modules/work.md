---
source_commit: 4e68436185cb505db643c54224ab82d2f2745844
generated: 2026-07-21
---


# 模块：work

**work** 模块是分布式工作调度与执行系统，负责创建会话、下发命令、驱动 Codex 执行并双向同步手机端。

模块分为两层：`core` 提供 HTTP API 与 SQLite 存储，管理会话、命令、事件和提问；`runner` 轮询命令，启动 Codex 子进程，通过 MCP 工具向手机端发送报告/提问/HTML 报告。Runner 与 Core 之间通过 `CoreClient` 通信，认证依赖 token 哈希。核心数据流：Runner 注册 → 获取命令 → 执行 Codex → 解析 JSONL 事件 → 上报 Core，Core 持久化并转发给手机端。手机端可通过 `ask` 回答回传。

修改指引：调整 API 或会话逻辑从 `core/server.js` 和 `core/store.js` 入手；修改 Runner 执行流程或状态管理从 `runner/runner.js` 和 `runner/state.js` 入手。

## 文件摘要

### `work/README.md`

项目部署指南，说明 Core 和 Windows Runner 的配置与验证。
- `deploy/.env`：Core 环境变量，需生成随机值
- `docker compose up -d --build`：启动 Core 服务
- `runner/work-runner.json`：Runner 配置（token 与白名单）
- `start-runner.ps1`：前台运行 Runner
- `npm --prefix work test`：模拟协议测试

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

### `work/core/server.test.js`

Work server 集成测试，验证会话、命令、附件、询答等。
- `fixture`：创建测试环境
- `request`：HTTP 请求助手
- `uploadImage`：上传附件
- `registerAndCreate`：注册并创建会话
- `runnerCommandsPath`：命令路径构建

### `work/core/store.js`

工作会话存储与状态管理，基于 SQLite 实现会话、命令、事件、提问等核心数据操作。

- `WorkStore`：核心类，封装会话/命令/事件/附件/提问等 CRUD 与业务逻辑
- `tokenHash`/`safeHashEquals`：安全哈希与常量时间比较
- `SESSION_TERMINAL`/`SESSION_ACTIVE`：会话状态常量集
- `createSession`/`appendEvent`/`createCommand`：创建会话、追加事件、创建命令
- `createAsk`/`answerAsk`：提问与回答流程
- `report`/`reportHtml`：报告处理
- `validateQuestions`/`validateAnswers`：提问与答案校验

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

封装 Codex CLI 进程的启动、参数构建与 JSONL 事件解析。
- `mcpConfigArgs`：生成 MCP 服务器配置 CLI 参数
- `buildCodexArgs`：构建 exec/resume 命令参数
- `parseCodexSessionId`：从事件提取会话 ID
- `parseCodexAssistantMessage`：解析 agent 消息
- `runCodex`：启动子进程并逐行派发 JSON 事件
- `resolveCodexCommand`：Windows 下解析 codex.exe 路径

### `work/runner/core-client.js`

Core 服务的 HTTP 客户端封装，负责 runner 注册、心跳、命令/附件获取与确认。
- `CoreClient`：HTTP 客户端类，封装请求与 runner 生命周期 API
- `PROTOCOL_HEADERS`：协议标识头常量

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

### `work/runner/runner.js`

工作会话运行器，轮询命令并管理 Codex 子进程生命周期。
- `WorkRunner`：启停子进程，处理 START/STOP/RESUME/COMPLETE，维护状态与发件箱。
- `isolatedCodexEnv`：构造隔离环境变量。
- `PHONE_DEVELOPER_INSTRUCTIONS`：手机端提示指令。

### `work/runner/runner.test.js`

验证 WorkRunner 与 Codex 集成、状态持久化与恢复。
- `buildCodexArgs` 参数构造
- `parseCodexAssistantMessage` 消息筛选
- `WorkRunner` 启动/停止/恢复
- `RunnerState` 状态持久化
- `ensurePhoneHookProfile` 配置生成
- `resolveCodexCommand` 可执行文件解析

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

工作运行器示例配置，定义核心连接、轮询策略与仓库列表。
- `id`：运行器标识
- `name`：运行器名称
- `version`：版本号
- `coreUrl`：核心服务地址
- `token`：认证令牌
- `stateFile`：状态文件路径
- `pollIntervalMs`：轮询间隔
- `codexCommand`：Codex命令
- `repos`：仓库列表，含路径、模型与推理强度
