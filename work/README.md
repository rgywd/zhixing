# Zhixing Work Phone-line

这套运行时只服务于手机新建的 Codex 会话。Core 不持有 Codex 登录态和仓库；Windows Runner 只主动访问 Core，
并在白名单仓库中启动本机 `codex exec`。Codex 只能通过 `report`、`ask`、`report_html` 三个 MCP 工具联系手机。

## Core

1. 复制 `deploy/.env.example` 为 `deploy/.env`，生成三段互不相同的随机值。
2. 在 Core 前部署 HTTPS 反向代理；Android 只接受无子路径的 `https://` 地址。
3. 启动并检查：

```bash
cd work/deploy
docker compose up -d --build
curl http://127.0.0.1:8787/healthz
```

SQLite 数据位于 Docker volume `work-core-data`。备份时先停止容器，再复制 volume 中的
`work-core.sqlite`；恢复时使用相同协议版本启动后再开放 HTTPS 入口。

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

## Windows Runner

1. 安装 Node.js 22.5+、Git 和 Codex CLI。Runner 使用独立 `codexHome`，先为该目录完成一次登录：

```powershell
$env:CODEX_HOME = "$HOME\.zhixing-work\codex-home"
codex login
```
2. 复制 `runner/work-runner.example.json` 为 `runner/work-runner.json`，填写 Core HTTPS 地址、Runner token
   和仓库白名单。token 不要提交到 Git。目录来源支持两种方式：
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

`npm --prefix work test` 使用假 Codex 覆盖完整协议；本机已登录 Codex 时可额外运行
`npm --prefix work run e2e:real`。真实测试在临时 Git 仓库中启动 Core 与 Runner，自动回答 `ask`，并确认三个
MCP 工具和最终 IDLE 状态均完成，不会修改产品仓库。
