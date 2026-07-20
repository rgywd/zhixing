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

## Windows Runner

1. 安装 Node.js 22.5+、Git 和 Codex CLI，并先在本机完成 Codex 登录。
2. 复制 `runner/work-runner.example.json` 为 `runner/work-runner.json`，填写 Core HTTPS 地址、Runner token
   和仓库白名单。token 不要提交到 Git。
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
