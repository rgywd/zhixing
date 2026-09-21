# GitHub 主仓公开发布

## 边界

- 公开主仓 [`rgywd/zhixing`](https://github.com/rgywd/zhixing) 承载源码、Issue、正式标签与 Release。
- GitHub Actions 在同一仓库执行 PR policy、签名构建、发行测试和 Release 发布。
- 每个正式 Release 同时提供签名 APK 和对应源码归档；README 署名不能替代 AGPL 对应源码义务。
- Staging 客户端不检查正式更新，也不得作为正式 Release 发布。

## 发布规则

1. 可发布变更通过 GitHub PR 进入 `main`；正式 tag 必须指向 `main` 历史中的提交，且不得移动或复用。
2. 正式资产只发布到 [GitHub Releases](https://github.com/rgywd/zhixing/releases)。发布 Job 使用同仓
   `GITHUB_TOKEN`，无需跨仓发布令牌。
3. 补发既有标签时使用 `workflow_dispatch`；构建与源码锁定标签提交，打包及上传工具取自触发 workflow
   的当前 `main` 提交。
4. 发布前核对 唯一的知行 Universal APK、源码包、`latest.json`、`SHA256SUMS.txt`、许可文件和匿名下载。

## 发布资产

单应用构建的每个 `vX.Y.Z` Release 固定包含：

1. `zhixing-X.Y.Z-universal.apk`
2. `zhixing-X.Y.Z-source.tar.gz`
3. `latest.json`
4. `SHA256SUMS.txt`
5. `LICENSE`
6. `THIRD_PARTY_NOTICES.md`

`latest.json.downloads` 仅包含知行主应用，Work 随主应用更新，不再额外输出 Work APK 或 `work` 字段。
历史双应用版本保留原有资产，不重写已发布标签或安装包。

源码包来自同一个 tag 的干净递归检出，包含子模块和构建脚本，排除 Git 历史、签名文件、
`local.properties`、依赖缓存与构建产物。`latest.json.source.commit` 必须等于 tag commit。
应用直接读取 `https://github.com/rgywd/zhixing/releases/latest/download/latest.json`。

## 历史迁移与旧版升级

原发布专用仓库 `rgywd/zhixing-releases` 的 22 个历史 Release 在删除旧仓前完整迁入主仓。迁移时保留
APK、源码包、校验和与许可文件，并把历史 `latest.json` 的下载地址改为主仓 Release。

v0.3.9 至 v0.4.18 客户端仍内置旧发布专仓地址。旧仓删除后，这些版本无法自动检查更新，需要从主仓
Releases 手动安装 v0.4.19 或更高版本；升级后恢复应用内检查更新。

## 回滚

- 资产生成或上传失败时修复 workflow 后重跑同一 tag；上传步骤可替换同名资产，但不能改变源码 commit。
- 已发布 APK 有缺陷时按正常流程发布更高 `versionCode` 的补丁版本。
