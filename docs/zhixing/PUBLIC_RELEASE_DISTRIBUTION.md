# 公开安装包分发

## 边界

- 私有仓库 `rgywd/zhixing` 承载开发历史、Issue、PR、签名构建和 CI。
- 公开仓库 `rgywd/zhixing-releases` 只承载正式 Release，关闭 Issues 与 Wiki。
- 每个公开 Release 必须同时提供签名 APK 和精确对应的完整源码归档；README 署名不能替代 AGPL
  对应源码义务。
- staging 客户端不检查正式更新，也不得发布到公开仓库。

## 发布资产

每个 `vX.Y.Z` Release 固定包含：

1. `zhixing-X.Y.Z-universal.apk`
2. `zhixing-X.Y.Z-source.tar.gz`
3. `latest.json`
4. `SHA256SUMS.txt`
5. `LICENSE`
6. `THIRD_PARTY_NOTICES.md`

源码包来自同一个私有 tag 的干净递归检出，包含子模块和构建脚本，排除 Git 历史、签名文件、
`local.properties`、依赖缓存与构建产物。`latest.json.source.commit` 必须等于触发 workflow 的 tag commit。

## 凭证

私有仓库 Actions Secret `RELEASES_REPO_TOKEN` 只允许在最终跨仓发布步骤中使用。推荐使用仅授权
`rgywd/zhixing-releases`、仅具备 Contents 写权限的 fine-grained personal access token。

## 私有仓库门禁

当前 GitHub 套餐不为私有仓库提供 `main` 分支保护，GitHub API 会直接返回需要升级 Pro 或改回公开。
在升级套餐前，`main` 禁止直接 push 只能依靠本仓库流程约定执行，PR 与 CI 仍然必须完成，但平台无法强制拦截绕过。

## 迁移说明

0.3.7 已作为公开基线迁移。仍指向私有更新源的旧客户端无法匿名发现新版本，因此首次迁移需要手动安装
一个使用公开更新源的新版本；之后恢复应用内自动更新。

## 回滚

- 不移动或复用已发布 tag。
- 资产生成或上传失败时修复 workflow 后重跑同一 tag；上传步骤会覆盖同名资产，但不会更改源码 commit。
- 已发布 APK 有缺陷时按正常流程发布更高 `versionCode` 的补丁版本。
