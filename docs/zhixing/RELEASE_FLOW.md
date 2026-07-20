# 知行分支与发布流程

## 目标

`main` 永远保持可构建、可测试、可发布。功能开发、缺陷修复和发布冻结均在短分支完成，
通过 Pull Request 和 CI 后才允许进入 `main`。

## 分支约定

| 分支 | 用途 | 生命周期 |
| --- | --- | --- |
| `main` | 唯一稳定主干，永远可部署 | 长期存在 |
| `release/x.y.z` | 版本冻结、发布修复和回滚准备 | 发布完成后删除 |
| `feat/需求号-简述` | 单一产品需求 | 合并后删除 |
| `fix/问题号-简述` | 单一缺陷修复 | 合并后删除 |
| `chore/简述` | 配置、依赖、构建和仓库维护 | 合并后删除 |
| `exp/简述` | 验证性实验，不承诺合并 | 实验结束后删除或转正式分支 |

分支名只允许一层 `/`。`feat` 和 `fix` 必须同时包含编号与简述，例如：

```text
feat/123-agent-plan-speech
fix/456-update-check-crash
chore/upgrade-gradle
exp/new-memory-ranking
release/0.2.0
```

## 日常开发

1. 从最新 `main` 创建对应类型的短分支。
2. 一个分支只处理一个需求、问题或维护目标。
3. 推送分支并创建指向 `main` 的 Pull Request。
4. 分支名校验和 `Build and test` 必须通过。
5. 合并后删除远端和本地短分支。

推荐命令：

```bash
git switch main
git pull --ff-only origin main
git switch -c feat/123-short-description
git push -u origin feat/123-short-description
```

禁止直接在 `main` 开发、强推 `main`、复用已经合并的功能分支，或把实验分支直接作为发布来源。

## 正式发布

1. 确认 `main` CI 通过，并暂停向 `main` 合并新的 `feat/*` 和 `exp/*`。
2. 从最新 `main` 创建 `release/x.y.z`。
3. 在发布分支更新 `versionName`、单调递增的 `versionCode` 和必要的发布说明。
4. 冻结期间只接收面向该版本的 `fix/*`；修复 PR 先合入 `release/x.y.z`。
5. 完成发布验证后，从 `release/x.y.z` 创建 PR 合并回 `main`。
6. 在合并后的 `main` 提交上创建且只创建一次 `vX.Y.Z` 标签。
7. 推送标签，由 `Release` workflow 回归 Android，构建签名 APK，校验哈希并创建 GitHub Release。
8. 验证 Release 中的 APK、`latest.json` 和应用内更新检查后，删除发布分支。

```bash
git switch main
git pull --ff-only origin main
git switch -c release/0.2.0
# 更新 versionName/versionCode，提交并完成 PR
git switch main
git pull --ff-only origin main
git tag -a v0.2.0 -m "Zhixing v0.2.0"
git push origin v0.2.0
```

标签必须符合严格的 `vX.Y.Z` 格式，版本号必须与 `app/build.gradle.kts` 中的 `versionName` 一致，
且标签提交必须位于 `origin/main` 历史中。

## 缺陷修复与回滚

- 未进入发布冻结时：从 `main` 创建 `fix/问题号-简述`，通过 PR 合回 `main`。
- 发布冻结期间：从 `release/x.y.z` 创建 `fix/问题号-简述`，先合回发布分支，发布分支最终整体合回 `main`。
- 已发布版本出现严重问题时：禁止删除或移动旧标签；从最后一个可用版本或当前 `main` 创建新的
  `release/x.y.(z+1)`，回退问题提交、递增 `versionCode`，以新的补丁版本重新发布。

Android 不支持把较低 `versionCode` 当成升级包，因此“回滚”始终通过更高版本号发布修复包完成。

## GitHub 门禁

`main` 开启以下保护：

- 必须通过 Pull Request 合并；单人仓库不强制额外审批人。
- 必须通过 `Branch policy` 与 `Build and test`。
- 合并前必须基于最新 `main`。
- 禁止 force push 和删除分支。
- 要求线性历史，合并时使用 squash 或 rebase。

`release/*` 通过面向 `main` 的 Pull Request 执行构建与测试；合并后的 `main` 会再次验证，正式发布仍以
`main` 上的 `vX.Y.Z` 标签为准。
