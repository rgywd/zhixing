# 套餐余量服务安全隧道

Work Core 不应通过公网明文 HTTP 携带 CPA Quota Monitor 的 Bearer Token。生产环境使用一条从 Work Core 主机发起的 SSH 本地端口转发，把容器可访问的内部地址映射到当前 CPA 主机的 `127.0.0.1:8322`。CPA 迁移主机时，必须同步更新受限公钥、`HostKeyAlias`/known-hosts 和 systemd 隧道目标，并完成下方四层验证；不能只迁移监控容器。

```text
Android -> HTTPS Work Core -> Docker host 内部端口
                              -> SSH tunnel -> CPA 127.0.0.1:8322
```

## CPA 主机

为隧道创建独立公钥，并在 `~/.ssh/authorized_keys` 中限制用途。不要复用日常登录密钥，也不要允许任意目标端口转发。

```text
restrict,port-forwarding,permitopen="127.0.0.1:8322",command="/bin/false" ssh-ed25519 <public-key> zhixing-quota-tunnel
```

`restrict` 会关闭 PTY、agent/X11 转发等能力；`port-forwarding` 只重新开启端口转发，`permitopen` 再将目标锁定到 Quota Monitor，`command="/bin/false"` 拒绝 shell 和远程命令。

Quota Monitor 容器只把端口发布到宿主机 loopback：

```yaml
ports:
  - "127.0.0.1:8322:8322"
```

不得发布到 `0.0.0.0`。修改后先从 CPA 宿主机请求 `/healthz`，再配置跨主机隧道。

## Work Core 主机

隧道由 systemd 托管。下面的监听地址必须是 Compose 网络的 host gateway，容器才能访问；变更 Compose 网络后需要重新确认该地址。

```ini
[Unit]
Description=Zhixing quota monitor SSH tunnel
After=network-online.target docker.service
Wants=network-online.target

[Service]
Type=simple
ExecStart=/usr/bin/ssh -NT \
  -o ExitOnForwardFailure=yes \
  -o ServerAliveInterval=30 \
  -o ServerAliveCountMax=3 \
  -o StrictHostKeyChecking=yes \
  -o HostKeyAlias=<stable-cpa-host-alias> \
  -i /root/.ssh/zhixing_quota_tunnel \
  -p <ssh-port> \
  -L <docker-gateway>:28213:127.0.0.1:8322 \
  <ssh-user>@<cpa-host>
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Work Core 的部署环境只保存内部地址与监控令牌：

```dotenv
CPA_QUOTA_BASE_URL=http://<docker-gateway>:28213
CPA_QUOTA_TOKEN=<monitor-token>
```

这里的 HTTP 只存在于 Docker host 到本机 SSH 监听端口之间；跨主机流量由 SSH 加密。不要将 `<monitor-token>`、隧道私钥或真实 `.env` 提交到 Git。

## 验证

依次确认隧道、host 入口、容器入口和 Work Core 代理：

```bash
systemctl is-active zhixing-quota-tunnel.service
systemctl show zhixing-quota-tunnel.service -p NRestarts --value
curl --fail http://<docker-gateway>:28213/healthz
docker exec <work-core-container> node -e \
  "fetch('http://<docker-gateway>:28213/healthz').then(r=>r.json()).then(x=>console.log(x.schema_version))"
```

最后使用 Work Core 用户令牌请求 `GET /v1/life/quotas`，确认返回 `quota-monitor/v1`。日志和验证输出只打印 provider、state、window 数量等摘要，不打印任何令牌。

## 回滚

1. 将 Work Core 回滚到上一镜像或上一提交。
2. 从部署环境移除 `CPA_QUOTA_BASE_URL` 与 `CPA_QUOTA_TOKEN`。
3. `systemctl disable --now zhixing-quota-tunnel.service`。
4. 从 CPA 主机的 `authorized_keys` 删除对应的专用公钥。

Android 会保留最后一次成功同步的套餐数据；后端或隧道暂时不可用时，界面标记缓存/过期状态，不会把未知余量显示成 0。
