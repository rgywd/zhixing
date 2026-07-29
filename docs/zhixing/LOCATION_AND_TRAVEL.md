# 位置与出行契约

状态：v0.1 已实现（2026-07-29）

## 产品边界

位置与出行是普通聊天中的可选本地工具组，不是后台监控能力。用户为某个助手启用后，模型可以在当前对话中
请求以下三项能力：

| 工具 | 能力 | 用户确认 |
| --- | --- | --- |
| `get_current_location` | 获取一次前台位置和结构化地址 | 每次确认 |
| `search_nearby_places` | 以一次前台位置为中心检索附近地点 | 每次确认 |
| `open_navigation` | 打开高德地图 App；未安装时打开高德 Web 导航 | 每次确认 |

v0.1 不包含后台或持续定位、轨迹和位置历史、地理围栏、到离提醒、App 内地图、路线规划对比、通用跨城市
地点搜索，也不接入高德导航 SDK。

## 启用与权限

- 工具组默认关闭，由用户在助手本地工具设置中单独启用。
- 首次启用前必须展示高德定位与搜索 SDK 的数据边界并取得用户同意；未同意时不得初始化相关 SDK。
- 隐私说明提供[高德地图开放平台隐私权政策](https://lbs.amap.com/pages/privacy/)的直接入口。
- 只申请 Android 前台粗略/精确位置权限，不申请后台位置权限。用户只授予大致位置时仍允许尝试定位，并在
  结果中如实返回精度。
- 每次工具执行还必须经过聊天内的工具审批。关闭工具组后，助手不再获得上述工具。
- 权限被拒绝、系统定位关闭、配置缺失、超时或第三方失败只影响本工具组，不得阻断普通聊天。

## 数据与模型边界

- 定位和附近检索由 Android 客户端直接调用高德服务，不经过知行自建服务。
- `get_current_location` 默认只返回结构化地址、精度、采集时间和坐标系；仅当工具调用明确设置
  `include_coordinates=true` 时，才把当前坐标返回给模型。
- `search_nearby_places` 在客户端内部取得搜索中心。返回地点名称、地址、类别、距离、地点 ID 和目的地坐标，
  但不向模型返回用户的搜索中心坐标。
- 目的地坐标用于后续打开导航，坐标系固定为高德 GCJ-02。不得把高德坐标标记成 WGS-84。
- v0.1 不把定位结果、搜索词、搜索结果或导航目的地写入独立数据库，也不维护位置历史。它们只随当前工具调用
  进入既有会话消息。
- 日志和错误消息不得包含 API Key、用户坐标、完整第三方响应或第三方内部错误正文。

## 工具契约

### `get_current_location`

输入：

```json
{
  "include_coordinates": false
}
```

成功结果包含 `address`、`country`、`province`、`city`、`district`、`street`、`accuracy_meters`、
`observed_at` 和 `coordinate_system="GCJ02"`。坐标只有在明确请求后才以 `latitude`、`longitude` 返回。

### `search_nearby_places`

输入：

```json
{
  "query": "咖啡",
  "radius_meters": 2000,
  "limit": 5
}
```

- `query` 必填且去除首尾空白后不能为空。
- `radius_meters` 默认 2000，有效范围 100–50000。
- `limit` 默认 5，有效范围 1–10。
- 结果包含 `places`；每个地点包含可用的 `place_id`、`name`、`address`、`category`、
  `distance_meters`、`latitude` 和 `longitude`。结果不包含搜索中心坐标。

### `open_navigation`

输入：

```json
{
  "destination_name": "目的地",
  "latitude": 39.9,
  "longitude": 116.4,
  "place_id": "可选高德 POI ID",
  "travel_mode": "driving"
}
```

`travel_mode` 只允许 `driving`、`transit`、`walking`、`cycling`。目的地名称不能为空；纬度必须位于
`[-90, 90]`，经度必须位于 `[-180, 180]`。客户端优先打开高德地图 App，并以 Web 导航作为未安装时的
降级入口。

## 稳定失败语义

工具失败返回稳定的 `error_code` 和面向用户的简短 `message`：

| 错误码 | 含义 |
| --- | --- |
| `CONFIGURATION_REQUIRED` | 当前构建未配置对应高德 Android Key |
| `PRIVACY_CONSENT_REQUIRED` | 用户尚未同意高德 SDK 数据边界 |
| `NO_PERMISSION` | 前台位置权限不可用 |
| `LOCATION_DISABLED` | 系统定位服务关闭 |
| `TIMEOUT` | 本次定位或检索超时 |
| `INVALID_ARGUMENT` | 工具输入不满足契约 |
| `EXTERNAL_SERVICE_ERROR` | 高德服务返回失败，或当前 CPU 架构不受 SDK 支持 |
| `NO_HANDLER` | 设备无法处理 App 或 Web 导航入口 |
| `EXECUTION_FAILED` | 其他经脱敏的本地执行失败 |

第三方错误码可作为非敏感诊断字段返回，但不得把第三方错误正文直接交给模型。

## 构建配置

高德 Android Key 不提交到仓库。每个应用 ID / 签名组合需要独立 Key，并通过同名 Gradle 属性或环境变量
注入：

| 变体 | 配置名 | 应用 ID |
| --- | --- | --- |
| debug | `AMAP_API_KEY_DEBUG` | `dev.sundby.zhixing.debug` |
| staging | `AMAP_API_KEY_STAGING` | `dev.sundby.zhixing.staging` |
| release | `AMAP_API_KEY_PRODUCTION` | `dev.sundby.zhixing` |

缺少 Key 时仍必须可以编译和运行；位置与附近检索返回 `CONFIGURATION_REQUIRED`，普通聊天和其他本地工具
保持可用。Key 不得进入 Git、文档、测试快照、命令输出或日志。

当前高德 10.x 以上组合 SDK 的原生定位库只支持 `armeabi-v7a` 和 `arm64-v8a`。知行保留既有
`x86_64` 构建兼容性，但在这类设备或模拟器上调用当前位置、附近搜索时返回
`EXTERNAL_SERVICE_ERROR`（诊断码 `UNSUPPORTED_ABI`），不加载 SDK、不导致应用崩溃；外部导航仍可使用。

## 验收

- 关闭工具组时，模型看不到三个工具；启用后才能看到。
- 首次启用需要隐私说明和 Android 前台位置权限，不出现后台位置权限。
- 三项工具均进入既有工具审批 UI，拒绝后不执行 SDK 或外部 Intent。
- 默认当前位置结果和全部附近搜索结果不包含用户坐标。
- 高德地图存在与不存在两条导航路径都可验证。
- 无 Key、无权限、定位关闭和第三方失败均以稳定错误降级，不影响会话继续生成。
- 单元测试、文档检查、lint 与 staging 构建通过后才可合并。
