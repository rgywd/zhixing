# 知行自托管中继运行手册

状态：部署材料已实现；生产部署和手机/开发机全链路切换按本手册验收。

协议审计基线：[`slopus/happy@3f161de`](https://github.com/slopus/happy/commit/3f161de70541b1cedaf0b7547ed70889d8dae22d)，与
`HappyProtocol.UPSTREAM_COMMIT` 一致；部署包固定为该基线中的 `happy-server-self-host@1.1.11`。
上游 standalone server 使用 PGlite、本地文件和内存事件总线，
不要求外置 Postgres、Redis 或 S3；参考上游
[`packages/happy-server/README.md`](https://github.com/slopus/happy/blob/3f161de70541b1cedaf0b7547ed70889d8dae22d/packages/happy-server/README.md)。

## 1. 部署边界

中继只负责：

- 保存和转发端到端加密的机器、会话与消息记录；
- Socket.IO 在线状态、RPC 和增量通知；
- 保存无法解密的附件密文。

中继不得保存或代理：

- OpenAI、Anthropic 或其他模型 API key；
- Codex/Claude 的登录凭据；
- 开发机文件系统、SSH 端口或 Codex app-server 端口。

Codex/Claude 模型请求仍从开发机直接访问各自官方服务。中继所在地区不会改变模型请求的出口位置，
也不能用于规避服务地区或账号政策。OpenAI 明确说明从不支持地区访问可能导致账号被阻止或暂停；
Anthropic 也要求在其支持地区使用 Claude。部署前应分别检查
[OpenAI 支持地区](https://help.openai.com/en/articles/5347006)与
[Claude 支持地区](https://support.claude.com/en/articles/8461763-where-can-i-access-claude)。

推荐把中继放在独立、稳定、可备份的个人 VPS 上，与 CPA/生产业务隔离。服务器需要：

- Docker Engine + Docker Compose；
- 一个已解析到 VPS 的独立域名；
- TCP 443（使用 bundled Caddy 时还需 TCP 80，HTTP/3 可选 UDP 443）；
- 建议至少 2 GB 内存；约 1.5 GB 内存配合 2 GB swap 可运行；部署镜像直接安装固定的预构建 npm 包，
  不在 VPS 上编译整个上游 monorepo；
  生产数据位于 Docker volume `zhixing-happy-data`。

## 2. 首次部署

```bash
git clone https://github.com/rgywd/zhixing.git
cd zhixing/deploy/happy-relay
cp .env.example .env
openssl rand -hex 32
```

把生成值写入 `.env` 的 `HANDY_MASTER_SECRET`，并设置真实 `HAPPY_PUBLIC_URL`。该 secret 只属于
Happy server，不能与任何模型 key 共用；首次上线后必须稳定保存。

如果 VPS 已有 Nginx/Caddy/Traefik：

```bash
docker compose up -d --build happy-relay
curl --fail http://127.0.0.1:3005/health
```

现有反向代理应把独立域名的全部路径转到 `http://127.0.0.1:3005`，并允许 WebSocket upgrade；
不要配置子路径。Nginx 可从 `deploy/happy-relay/nginx.conf.example` 起步，替换 `server_name` 后：

```bash
sudo cp nginx.conf.example /etc/nginx/sites-available/zhixing-happy
sudo ln -s /etc/nginx/sites-available/zhixing-happy /etc/nginx/sites-enabled/zhixing-happy
sudo nginx -t && sudo systemctl reload nginx
sudo certbot --nginx -d happy.example.com
```

公网验收：

```bash
curl --fail https://happy.example.com/health
```

仓库提供的 Nginx 模板还会拒绝编码斜杠、反斜杠和编码 `..`，避免反向代理与静态文件路由产生
解析差异。`happy-server-self-host@1.1.11` 的依赖审计会报告 `@fastify/static` 公告；当前服务未启用
目录列表，公开静态 Web App 也不依赖路由守卫保护敏感文件，因此公告中的两个必要利用条件均不成立。
升级上游包时仍须重新运行 `npm audit --omit=dev` 并复核实际调用方式，不能只看汇总数量。

如果 VPS 没有反向代理，设置 `HAPPY_DOMAIN` 后启用随仓库提供的 Caddy：

```bash
docker compose --profile bundled-proxy up -d --build
curl --fail https://happy.example.com/health
```

## 3. 切换客户端

切换会产生一个新的空中继数据面，不迁移官方中继的历史；旧数据仍保留在旧中继。

1. 知行 App：`工作 -> 工作连接 -> 中继服务器`，填写新的 HTTPS origin，点击“保存并断开”，
   再用原恢复密钥登录。
2. 开发机：停止旧 `zhixing-agent daemon`，设置同一 origin 后重新登录并启动：

   ```bash
   export ZHIXING_RELAY_URL=https://happy.example.com
   zhixing-agent login '<恢复密钥>'
   zhixing-agent daemon
   ```

   Windows 可用用户级环境变量 `ZHIXING_RELAY_URL`；恢复密钥只在开发机交互输入，不写入脚本或日志。
3. 验收：App 能看到新机器；手机新建 Codex 任务；开发机收到 spawn；聊天流收到文本/工具/turn-end；
   普通模式审批可回传；完全访问和硬性限制分别按契约生效。

## 4. 备份、升级与回滚

PGlite 与附件都在同一 volume。做一致性备份时短暂停服务：

```bash
mkdir -p backups
docker compose stop happy-relay
docker run --rm \
  -v zhixing-happy-data:/data:ro \
  -v "$PWD/backups:/backup" \
  alpine:3.22 sh -c 'tar czf /backup/happy-data-$(date +%Y%m%d-%H%M%S).tgz -C /data .'
docker compose start happy-relay
```

升级前必须：备份 volume、审计新的上游 commit、运行 App/agent 契约测试、在非生产端点验证，再替换
`package.json` 中的版本并更新 `package-lock.json` 后重建。锁文件固定完整传递依赖和包完整性；不要使用
`latest` 或直接跟随上游 `main`。

回滚客户端时，把 App 和 agent 的中继地址改回 `https://api.cluster-fluster.com` 并重新登录；
自托管 volume 保留，不要删除。服务端回滚时恢复旧 npm 包版本；只有数据库已发生不兼容迁移时才停服恢复备份。

## 5. 故障检查

```bash
docker compose ps
docker compose logs --tail=200 happy-relay
curl --fail http://127.0.0.1:3005/health
docker volume inspect zhixing-happy-data
```

- 本机健康、公网失败：检查 DNS、证书、443 和反向代理。
- HTTP 正常、Socket 离线：检查反向代理是否保留 WebSocket upgrade，且没有改写 `/v1/updates`。
- 登录后看不到旧会话：这是中继隔离的预期行为，不是数据丢失；切回旧中继仍可读取旧历史。
- 更换 `HANDY_MASTER_SECRET` 后旧 token 失效：恢复原 secret；不要通过反复重新登录掩盖配置漂移。
