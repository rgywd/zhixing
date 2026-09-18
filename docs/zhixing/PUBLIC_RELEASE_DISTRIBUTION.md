# Gitee 主仓公开发布

## 边界

- 公开主仓 [`rongguiyewd/zhixing`](https://gitee.com/rongguiyewd/zhixing) 承载源码、Issue、正式标签与 Release。
- 私有 GitHub 镜像 `rgywd/zhixing` 执行 PR policy、签名构建与发行测试，并保留现有 GitHub Issue 工具。
- 每个正式 Release 同时提供签名 APK 和对应源码归档；README 署名不能替代 AGPL 对应源码义务。
- Staging 客户端不检查正式更新，也不得作为正式 Release 发布。

## 发布规则

1. 正式资产只发布到 [Gitee Releases](https://gitee.com/rongguiyewd/zhixing/releases)。GitHub CI 镜像的
   发布 Job 通过 `GITEE_PAT` secret 调用 Gitee API；该令牌不得进入仓库、URL 或日志。
2. 可发布变更先通过 Gitee PR 进入 `main`，再通过 GitHub CI 镜像 PR 同步。Gitee `main` 的发布提交须位于
   GitHub `main` 历史中；两端 `vX.Y.Z` tag 必须指向同一提交。标签不得移动或复用。
3. 补发既有标签时在 GitHub CI 镜像使用 `workflow_dispatch`；构建与源码锁定标签提交，打包及上传工具取自
   触发 workflow 的当前提交。
4. 发布前核对唯一 Universal APK、源码包、`latest.json`、`SHA256SUMS.txt`、许可文件和匿名下载。

## 发布资产

每个 `vX.Y.Z` Release 固定包含：

1. `zhixing-X.Y.Z-universal.apk`
2. `zhixing-X.Y.Z-source.tar.gz`
3. `latest.json`
4. `SHA256SUMS.txt`
5. `LICENSE`
6. `THIRD_PARTY_NOTICES.md`

源码包来自同一个 tag 的干净递归检出，包含子模块和构建脚本，排除 Git 历史、签名文件、
`local.properties`、依赖缓存与构建产物。`latest.json.source.commit` 必须等于 tag commit。
应用通过 Gitee Release API 查询最新版本，再读取该 Release 的 `latest.json` 附件。

## 旧版客户端迁移

v0.3.9 至 v0.4.18 的客户端内置旧仓库 `rgywd/zhixing-releases` 的更新地址。旧仓库删除后，
这些版本无法自动检查更新，需要从 Gitee Releases 手动安装包含新地址的版本。历史 Release 资产迁移到
Gitee 时保留原始 APK 和源码的 SHA-256，并将迁移后的 `latest.json` 下载地址改为 Gitee。

## 回滚

- 资产生成或上传失败时修复 workflow 后重跑同一 tag；上传步骤可替换同名资产，但不能改变源码 commit。
- 已发布 APK 有缺陷时按正常流程发布更高 `versionCode` 的补丁版本。
