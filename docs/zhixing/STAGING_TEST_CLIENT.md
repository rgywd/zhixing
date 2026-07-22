# Staging 测试客户端

`dev.sundby.zhixing.staging` 是知行的机器可验证测试客户端。它与正式版并存，用来让开发代理在不操作用户主力手机、
不污染正式数据的前提下，安装 APK、注入测试凭证、执行固定场景并读取结构化结果。

## 分发与数据边界

| 项目 | 正式版 | Staging |
| --- | --- | --- |
| Application ID | `dev.sundby.zhixing` | `dev.sundby.zhixing.staging` |
| 应用名称 | 知行 | 知行 Staging |
| Deep Link scheme | `zhixing://` | `zhixing-staging://` |
| 数据目录 | 正式版独立目录 | Staging 独立目录 |
| 更新源 | GitHub Release `latest.json` | 关闭 |
| 可调试 | 否 | 是 |
| 测试驱动 | 关闭 | 开启 |

Staging APK 不进入正式 Release 资产，也不能读取或覆盖正式版数据库、Keystore 凭证和缓存。

## 本机驱动

`staging-driver/` 提供一个仅监听 `127.0.0.1` 的 HTTP API。所有请求都必须携带
`Authorization: Bearer <STAGING_DRIVER_TOKEN>`；驱动不提供透传 shell、任意 instrumentation class、任意文件路径或
任意 ADB 参数。

PowerShell 启动：

```powershell
.\staging-driver\start-driver.ps1
```

脚本会在未设置时生成一次高熵 Bearer Token。Token 只用于本机开发会话，不提交到仓库。

完整本地验证（安装、状态与截图）：

```powershell
.\staging-driver\verify-driver.ps1 -Install
```

如需运行真实 Work 标题场景，使用进程级环境变量传入 `ZHIXING_STAGING_WORK_BASE_URL` 与
`ZHIXING_STAGING_WORK_TOKEN` 后再执行同一脚本；脚本输出不会回显 Work Token。

### 固定接口

| 方法 | 路径 | 用途 |
| --- | --- | --- |
| `GET` | `/v1/status` | 返回 Staging 的安装、版本和进程状态 |
| `POST` | `/v1/installations` | 只执行 `installStaging` 与 `installStagingAndroidTest` |
| `POST` | `/v1/config/work` | 将 Work HTTPS origin 与 Token 一次性写入 Staging Keystore |
| `POST` | `/v1/runs/work-title` | 走真实 Work 创建链路并读回会话标题 |
| `GET` | `/v1/screenshots/current` | 获取当前设备 PNG 截图 |

`/v1/config/work` 的 Token 经 stdin 写入应用私有 `no_backup` 临时文件，不出现在进程参数中；仪器测试导入 Keystore 后
立即删除该文件。`baseUrl` 只接受无路径、无 query、无 fragment 的 HTTPS origin。

## 标题验收场景

`POST /v1/runs/work-title` 接收：

```json
{
  "message": "修复 Work 会话标题并验证读回"
}
```

场景必须完成以下真实链路：

1. 从 Staging Keystore 读取 Work 凭证并刷新可用仓库；
2. 通过与手机 UI 相同的 `PhoneWorkSessionCreator` 生成标题并创建会话；
3. 从 Core 刷新会话，再从本地缓存读回；
4. 断言标题非空且不等于仓库名；
5. 返回 `applicationId`、`versionName`、`channel`、`sessionId`、`title` 与 `repoName`。

本场景会真实创建一条 Work 会话。验收环境应使用可识别的测试消息，并在验证后按 Work 的正常归档/结束流程清理。

## CI 门禁

PR CI 必须运行驱动契约单测、Staging JVM 单测，并构建 Staging 应用 APK 与仪器测试 APK。CI 不连接真实 Core；
真实 Core 场景只在受控开发机或测试设备上执行。
