# Zhixing Work Phone-line

这套运行时只服务于手机新建的 Codex 或 Claude Code 会话。Core 不持有 CLI 登录态和仓库；Windows Runner 只主动
访问 Core，并在白名单仓库中启动用户选定的本机 CLI。两个运行时都只能通过 `report`、`ask`、`report_html`
三个 MCP 工具联系手机。

## Core

1. 复制 `deploy/.env.example` 为 `deploy/.env`，生成三段互不相同的随机值。
2. 在 Core 前部署 HTTPS 反向代理；Android 只接受无子路径的 `https://` 地址。
3. 启动并检查：

```bash
cd work/deploy
docker compose up -d --build
curl http://127.0.0.1:8787/healthz
```

SQLite 数据与普通文件附件都位于 Docker volume `work-core-data`。普通文件默认写入
`/data/attachments`（可用 `WORK_CORE_ATTACHMENT_DIR` 调整），SQLite 只保存相对存储键和元数据；兼容既有数据的
图片仍保存在 SQLite BLOB 中。备份时先停止容器并复制整个 volume，不能只复制 `work-core.sqlite`；恢复时保持
SQLite 与附件目录来自同一份快照，使用相同协议版本启动后再开放 HTTPS 入口。

每条消息最多 4 个附件、单个最大 10 MiB。支持 PNG/JPEG/WebP/GIF、常见 UTF-8 文本与源码、PDF、Office/EPUB，
以及 ZIP、7z、GZip。Core 会按类型组合校验文件名、MIME、文件签名或 UTF-8 内容，拒绝伪装格式、可执行二进制和
安装包；文本脚本只作为非可信输入交付，不会自动执行。压缩包保持原样，由 Codex 或 Claude Code 在任务确有需要时
检查或解压，Core 不主动展开。未绑定到会话的上传会在后续上传时清理
超过 24 小时的记录和外置文件；绑定后与会话一同保留，归档不会删除附件。

### 套餐余量代理

右侧生活概览通过 Work Core 的 `GET /v1/life/quotas` 读取套餐余量。Core 使用
`CPA_QUOTA_BASE_URL` 和 `CPA_QUOTA_TOKEN` 访问 CPA 额度监控，Android 安装包不会包含监控 token，
也不会直接访问明文监控地址。该接口沿用 Work 用户 Bearer 鉴权和 `X-Zhixing-Work-Protocol: 1`。

Core 会在 SQLite 同目录持久化最后一次通过 `quota-monitor/v1` 校验的响应。上游超时、返回错误或
数据不符合契约时，已有快照会以 `proxy_stale=true` 返回；没有可用快照时返回 5xx。未知数字仍为
`null`，不会被改写为 0。普通页面只查询 `/v1/quotas`，不会触发 CPA 的立即刷新接口。

`CPA_QUOTA_BASE_URL` 必须指向 HTTPS 入口或仅在服务器内部可达的加密隧道端点，不要让 Core 使用
携带 Bearer Token 的公网明文 HTTP。当前单机部署可用受限 SSH 本地转发连接监控服务的 loopback
端口，再把该隧道地址填入 Core 环境变量。

Quota Monitor 的受控源码位于 [`quota-monitor/`](quota-monitor/)。采集历史与当前 CPA 凭据集合分开保存：
历史快照继续保留，但 `/v1/quotas` 只返回最近一次成功 inventory 中仍存在的账户，因此渠道新增、删除、禁用或
恢复会在下一轮轮询后自动反映，不需要清理 Android 缓存。

### 信息监控薄代理

普通聊天通过 Work Core 的以下只读端点查询邮件与飞书的监控结果：

- `GET /v1/life/inbox/status`
- `GET /v1/life/inbox/items`
- `GET /v1/life/inbox/digest`

三者沿用 Work 用户 Bearer 鉴权和 `X-Zhixing-Work-Protocol: 1`，分别转发到 Life Gateway 的
`/api/v1/monitor/status|items|digest`。Core 的总查询白名单是 `channel=email|feishu`、`hours=1..168`、
`limit=1..50` 和 `minImportance=low|normal|high|urgent`，并按端点继续收窄：`status` 只允许 `channel`，
`items` 允许全部四项，`digest` 允许除 `limit` 外的三项。未知、重复、越界或对当前端点无意义的参数直接拒绝。

部署时通过 `LIFE_GATEWAY_BASE_URL` 和 `LIFE_GATEWAY_TOKEN` 配置上游。Base URL 必须是 HTTPS，或仅供
同机/加密隧道使用的 loopback HTTP；Core 禁止携带 token 跟随重定向。邮箱、飞书的登录凭据、游标、原始正文和
webhook payload 只属于 Life Gateway，不得放入 Android、Work Core 配置或日志。

Core 会验证并重建 `information-monitor/v1` 响应，任意深度出现 `body`、`raw`、`content`、
`credentials` 或 `providerItemId` 时拒绝该次上游结果，其他契约外字段也不会转发。最后一次有效结果只在进程内按
“端点 + 完整查询”缓存：最多 32 组，30 秒内复用，上游失败时最多降级使用 5 分钟。降级状态通过
`X-Zhixing-Life-Cache: stale`、`X-Zhixing-Life-Error` 和标准 `Warning: 110` 响应头表达；Core 不把邮件摘要缓存
写入磁盘，也不会跨查询借用结果。

这是普通聊天生活服务的窄代理例外，不负责采集、摘要生成或事件存储，也不会注册新的 Phone-line MCP 工具。
Codex / Claude Code 手机电话线仍然只有 `report`、`ask`、`report_html` 三个工具。

## Windows Runner

1. 安装 Node.js 22.5+、Git，以及需要开放给手机使用的 CLI。

   Codex 使用独立 `codexHome`，先为该目录完成一次登录：

```powershell
$env:CODEX_HOME = "$HOME\.zhixing-work\codex-home"
codex login
```

   Claude Code 复用当前 Windows 用户自己的 user settings。订阅登录态或 `settings.json.env` 中的第三方 API
   `ANTHROPIC_AUTH_TOKEN`、`ANTHROPIC_API_KEY`、`ANTHROPIC_BASE_URL` 和模型映射都只由本机 CLI 读取，
   Runner 不复制或上传凭据。先确认 CLI 可用并完成一次真实请求：

```powershell
claude --version
claude -p "只回复 CLAUDE_WORK_READY" --model sonnet --effort low --tools ""
```

   以真实请求为准。使用第三方 API 时先修复 `~/.claude/settings.json` 的 `env`；只有明确使用 Claude 订阅且
   OAuth 失效时才执行 `claude auth login`。

2. 复制 `runner/work-runner.example.json` 为 `runner/work-runner.json`，填写 Core HTTPS 地址、Runner token
   和仓库白名单。token 不要提交到 Git。`defaultRuntimes` 定义手机可选择的运行时、模型和思考深度；
   runtime 可用 `reasoningEffortsByModel` 为个别模型声明 `reasoningEfforts` 的非空子集；
   单个 `repos` 或 `repoRoots` 项也可用 `runtimes` 覆盖默认值。旧版 `defaultModels/models/reasoningEfforts`
   配置继续按 Codex catalog 读取。目录来源支持两种方式：
   - `repos`：固定目录，兼容已有配置；Runner 会实时校验目录是否仍然存在。
   - `repoRoots`：显式授权的本机根目录。`children` 发现一级子目录，`projects` 在 `maxDepth` 内寻找
     `.git`、`AGENTS.md`、`package.json`、`pyproject.toml`、Gradle、Cargo 或 Go 项目标记。

   `repoRoots.path` 支持 `%USERPROFILE%`、`${HOME}` 和 `~`。真实路径始终留在 Runner；Core 与 Android
   只接收稳定目录 ID、显示名、分组和可用状态。默认跳过隐藏目录、符号链接以及常见构建/依赖目录。
   `catalogRefreshIntervalMs` 控制自动重扫间隔，默认 30 秒。
3. 前台验收：

```powershell
pwsh -File .\work\runner\start-runner.ps1
```

4. 验收通过后，可为当前 Windows 用户安装登录自启动：

```powershell
pwsh -File .\work\runner\install-autostart.ps1
```

## 验证

`npm --prefix work test` 使用假的 Codex/Claude Code 进程覆盖完整协议。本机对应 CLI 已真实登录时，可额外运行：

```powershell
npm --prefix work run e2e:codex
npm --prefix work run e2e:claude
```

真实测试在临时 Git 仓库中启动 Core 与 Runner，自动回答 `ask`，并确认三个 MCP 工具、通用
`runtimeSessionId` 和最终 IDLE 状态均完成，不会修改产品仓库。
