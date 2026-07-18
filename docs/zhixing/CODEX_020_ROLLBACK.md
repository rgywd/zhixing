# 知行 0.2.0 切换与回滚

## 正常入口

0.2.0 默认将侧边栏“工作”路由到 Codex 原生项目与任务页面。Android 只连接 Wire v1 中继；
`zhixing-agent daemon` 默认只启动 Codex App Server、目录同步和运行时桥，不再注册 Happy 机器或 Socket。

构建期可用 `-PcodexWorkflowEnabled=false` 恢复旧工作入口，用于切换故障定位。该开关不迁移、不覆盖也不删除任何凭据或缓存。

## 保留边界

- Android 的旧 Happy 凭据、Room 会话缓存继续保留。
- VPS 的 `zhixing-happy-happy-relay-1`、`zhixing-happy-data` 和备份继续保留。
- Wire v1 使用独立中继、设备身份、序列、ACK 和密文格式，不读取或覆写 Happy 数据。
- 0.2.0 设置页只提供旧 Happy 历史的只读入口；不能发送、审批、恢复、停止或删除旧会话。

## 回滚到 0.1.13

1. 停止 0.2.0 Agent，保留 `~/.zhixing-agent/` 全目录。
2. 安装 GitHub Release `v0.1.13` 的同签名 APK；Android 会保留旧 Happy 凭据和缓存。
3. 使用 0.1.13 Agent，或在 0.2.0 Agent 上临时执行 `legacy-login` 后设置
   `ZHIXING_ENABLE_LEGACY_HAPPY=1` 启动兼容链路。
4. 验证 `https://happy.8-208-118-119.sslip.io/health`、旧机器在线和旧会话可读写。
5. 不删除 Wire Relay 数据；修复后可重新安装 0.2.0 并继续使用同一 Codex Thread。

只有 0.2.0 真机升级、备份恢复、断线重连和回滚演练全部通过后，才允许单独安排 Happy 容器下线。
