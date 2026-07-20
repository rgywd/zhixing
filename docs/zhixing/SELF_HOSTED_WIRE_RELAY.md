# 知行自有 Relay v1 部署与恢复（0.2.0–0.2.2 回滚）

状态：Historical / rollback only（2026-07-19）

这是 0.2.0–0.2.2 的已发布中继。Issue #66 的目标数据面不再经过 VPS Relay；本文仅用于旧版本运行、
故障回滚和数据恢复。0.1.13 使用的 Happy 自托管服务继续保留为更早回滚面，不与本服务共用端口、数据库或密钥。

## 1. 部署边界

服务要求 Node 22 容器、SQLite WAL 持久卷和 HTTPS 反向代理。个人 2C2G VPS 足够；compose 将 relay 限制在
768 MiB / 1.5 CPU，并默认只绑定 `127.0.0.1:3100`。

Relay 不持有 OpenAI/ChatGPT 登录态，不代理 Codex 模型请求，也不接触开发机端口。迁移中继不会改变 Codex 请求出口，
因此不能用于规避账号或地区规则。

## 2. 首次启动

```bash
git clone https://github.com/rgywd/zhixing.git
cd zhixing/deploy/zhixing-relay
cp .env.example .env
docker compose up -d --build relay
curl --fail http://127.0.0.1:3100/healthz
```

使用服务器既有 Nginx/Caddy 时，把一个独立 HTTPS origin 的全部路径代理到 `127.0.0.1:3100`，保留 WebSocket
upgrade、Authorization 和 `X-Zhixing-Device-Id` header。无既有代理时：

```bash
docker compose --profile bundled-proxy up -d --build
curl --fail "https://${ZHIXING_RELAY_DOMAIN}/healthz"
```

不支持子路径。`sslip.io` 可用于个人验证，但正式长期使用更建议稳定域名。

## 3. 验收

```bash
docker compose ps
docker compose logs --tail=100 relay
curl --fail http://127.0.0.1:3100/healthz
curl --fail http://127.0.0.1:3100/metrics
```

发布门禁还包括仓库内 relay 集成测试：认证重放、设备撤销、envelope 幂等、sequence collision、ACK、outbox、
WebSocket 通知、重启持久化、tombstone receipt、schema 拒绝降级和备份恢复。

## 4. 在线备份

脚本使用 SQLite online backup API，随后对副本执行 schema、integrity 和 SHA-256 manifest 验证：

```bash
chmod +x backup.sh restore.sh
./backup.sh
ls -lh backups/
```

把 `.sqlite3` 和同名 `.manifest.json` 一起复制到 VPS 之外。仅把 Docker volume 当备份不够。

## 5. 恢复演练

```bash
./restore.sh backups/relay-YYYYMMDDTHHMMSSZ.sqlite3
```

恢复脚本会停止 relay、验证 manifest/schema/integrity、原子替换数据库、保留带时间戳的 rollback 副本，再启动并检查
healthz。宿主备份保持 root `0600`；脚本只为一次性恢复容器增加 `CHOWN`/`DAC_OVERRIDE`，恢复后立即把数据库和
rollback 副本交回无特权 `node` 用户。恢复失败时不会自动启动一个状态不明的服务。

## 6. 升级与回滚

升级前：

1. 执行并异机保存备份；
2. 在 PR CI 跑完 relay/agent/Android 契约测试；
3. 检查数据库 migration 和 `RELAY_SCHEMA_VERSION`；
4. 重建 relay 镜像并验证重启后的 outbox/ACK/presence。

0.2.0 客户端切换失败时，停止新 relay 写入，保留 `zhixing-relay-data`，客户端与 Agent 回退到 v0.1.13 和旧 Happy
origin。新 relay 通过一次真实备份恢复与客户端闭环前，不删除 `deploy/happy-relay`、旧 volume 或 Happy 数据。
