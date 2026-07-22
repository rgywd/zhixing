# 公开安装包分发

## 边界

- 私有仓库 `rgywd/zhixing` 承载开发历史、Issue、PR、签名构建和 CI。
- 公开仓库 `rgywd/zhixing-releases` 只承载正式 Release，关闭 Issues 与 Wiki。
- 每个公开 Release 必须同时提供签名 APK 和精确对应的完整源码归档；README 署名不能替代 AGPL
  对应源码义务。
- staging 客户端不检查正式更新，也不得发布到公开仓库。

## B 方案硬规则

从 v0.3.8 起，正式发布遵守以下不可变边界：

1. **私有开发仓只产出 tag，不承载正式 Release。** `rgywd/zhixing` 可以运行构建和保留 Actions 日志，
   但禁止在 `https://github.com/rgywd/zhixing/releases` 创建、编辑或上传正式资产。
2. **公开分发仓是唯一正式下载入口。** APK、对应源码、更新清单、校验和与许可文件只能发布到
   `https://github.com/rgywd/zhixing-releases/releases`。
3. **发布目标必须显式指定。** workflow 中所有 `gh release create/view/upload/edit` 必须带
   `--repo rgywd/zhixing-releases`；工作流会在目标等于开发仓或偏离固定公开仓时立即失败。
4. **补发旧标签仍走同一条 workflow。** 使用 `workflow_dispatch` 传入既有 `vX.Y.Z`，构建与源码锁定
   该标签提交，打包工具取自触发 workflow 的当前不可变提交；禁止移动标签或在私有仓手工补 Release。
5. **误发必须先迁移再清理。** 先验证公开仓的 manifest、APK、源码和校验和均可匿名访问，再删除私有仓
   同版本 Release 记录；删除时必须保留 tag，禁止使用 `--cleanup-tag`。

私有仓中 v0.3.7 及更早的 Release 仅作为迁移前历史记录，不再是正式下载源，也不得被应用更新器引用。

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
一个使用公开更新源的新版本；之后恢复应用内自动更新。v0.3.8 已迁移到公开仓，但该标签早于客户端更新源
切换代码，因此它本身仍不能完成自动更新源迁移；首个包含公开更新源代码的更高版本仍需手动安装一次。

## 回滚

- 不移动或复用已发布 tag。
- 资产生成或上传失败时修复 workflow 后重跑同一 tag；上传步骤会覆盖同名资产，但不会更改源码 commit。
- 已发布 APK 有缺陷时按正常流程发布更高 `versionCode` 的补丁版本。
