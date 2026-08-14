# CI 与构建流水线

状态：生效

目标是在不降低门禁的前提下避免重复构建。CI 的事实来源是 `.github/workflows/ci.yml`、
`.github/workflows/release.yml` 与 `.github/scripts/ci-plan.mjs`。

## PR 门禁

`Plan CI` 先运行自身规则测试，并校验 `.agents/skills` 与 `.claude/skills` 的完整文件集合和文件内容
逐字节一致，再按完整 PR diff 判断改动域。各域对应的门禁如下：

| 改动域 | 执行的域门禁 |
| --- | --- |
| `work/`、`staging-driver/` | `Work and JS tests` |
| Android 模块、`web-ui/`、Gradle 配置与 wrapper | `Android unit tests`、`Android lint`、`Android build smoke` |
| 同时涉及 Work 与 Android | 上述两组全部执行 |
| `docs/`、Markdown、agent/Claude skill mirror | 仅执行规划器内建测试、skill mirror 校验与 `Branch policy` |
| CI、仓库基础设施或无法明确归类的路径 | 保守执行 Work 与 Android 全量门禁 |

被规划器排除的 Job 使用 job-level condition 标记为 skipped；workflow 本身始终触发，避免必需检查因
workflow path filter 缺失而长期 Pending。实际启用的构建与测试互不串行等待，其中 `Android lint` 执行
`:app:lintStaging`。任一应执行检查失败或缺失，合并后的 `main` 都不会复用该 PR 结果。

只有 diff 严格限定为以下内容时，PR 才进入 `Release metadata` 快线：

- `app/build.gradle.kts` 中纯数字 `versionCode` 和严格 `X.Y.Z` `versionName` 赋值；
- `release-notes/` 下的发布说明。

快线仍校验版本号递增、`versionCode` 递增、发布说明存在且非空。任何其他 Gradle 或源码改动自动回到
普通全量门禁。

## main push

GitHub 私有仓当前没有平台级分支保护，因此 `main` push 不能被简单忽略：

- 合并提交能关联到同一 SHA 的已完成 PR，且规划器、分支策略及该改动域要求的检查全部成功时，只运行
  来源校验，不重复执行已经通过的构建、测试与 lint；校验会重新读取 PR 文件列表并按同一保守规则计算
  应执行检查，不能仅凭 skipped 结果放行；
- 直接 push、API 查询失败、检查缺失或失败时，自动运行完整 CI。

这保留了直接 push 的兜底，同时避免绿色 PR 合并后再重复约十分钟的相同任务。

## Gradle 缓存

`org.gradle.caching=true` 启用 Gradle task build cache。受信任的本仓分支和正式 tag 可以写入缓存；
外部 fork PR 只读。缓存是性能优化，不是正确性来源，缓存未命中时所有任务仍可从零完成。

## Release

正式 tag 触发后：

1. `Resolve release` 校验 tag、版本号和 `origin/main` 祖先关系；
2. `Release tests` 与 `Signed release APK` 在独立 runner 上并行；签名构建会注入并校验正式版所需的
   生产服务配置，缺失时在构建前失败；
3. 两者都成功后，`Publish public release` 下载同一次运行的签名 APK，生成源码、更新清单和校验和；
4. 正式资产只发布到 `rgywd/zhixing-releases`。

发布失败重跑同一 tag，不移动或复用标签。并行只缩短等待时间，不跳过测试、R8、签名或公开资产校验。
