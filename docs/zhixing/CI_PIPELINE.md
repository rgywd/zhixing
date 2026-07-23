# CI 与构建流水线

状态：生效

目标是在不降低门禁的前提下避免重复构建。CI 的事实来源是 `.github/workflows/ci.yml`、
`.github/workflows/release.yml` 与 `.github/scripts/ci-plan.mjs`。

## PR 门禁

`Plan CI` 先判断改动类型，普通 PR 并行运行：

- `Work and JS tests`
- `Android unit tests`
- `Android build smoke`
- `Branch policy`

三组构建与测试互不串行等待。任一必需检查失败，合并后的 `main` 都不会复用该 PR 结果。

只有 diff 严格限定为以下内容时，PR 才进入 `Release metadata` 快线：

- `app/build.gradle.kts` 中纯数字 `versionCode` 和严格 `X.Y.Z` `versionName` 赋值；
- `release-notes/` 下的发布说明。

快线仍校验版本号递增、`versionCode` 递增、发布说明存在且非空。任何其他 Gradle 或源码改动自动回到
普通全量门禁。

## main push

GitHub 私有仓当前没有平台级分支保护，因此 `main` push 不能被简单忽略：

- 合并提交能关联到同一 SHA 的已完成 PR，且该 PR 的必需检查全部成功时，只运行来源校验；
- 直接 push、API 查询失败、检查缺失或失败时，自动运行完整 CI。

这保留了直接 push 的兜底，同时避免绿色 PR 合并后再重复约十分钟的相同任务。

## Gradle 缓存

`org.gradle.caching=true` 启用 Gradle task build cache。受信任的本仓分支和正式 tag 可以写入缓存；
外部 fork PR 只读。缓存是性能优化，不是正确性来源，缓存未命中时所有任务仍可从零完成。

## Release

正式 tag 触发后：

1. `Resolve release` 校验 tag、版本号和 `origin/main` 祖先关系；
2. `Release tests` 与 `Signed release APK` 在独立 runner 上并行；
3. 两者都成功后，`Publish public release` 下载同一次运行的签名 APK，生成源码、更新清单和校验和；
4. 正式资产只发布到 `rgywd/zhixing-releases`。

发布失败重跑同一 tag，不移动或复用标签。并行只缩短等待时间，不跳过测试、R8、签名或公开资产校验。
