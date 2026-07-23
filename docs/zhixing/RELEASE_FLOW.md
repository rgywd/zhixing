# 知行 Git 分支、提交与发布流程

状态：生效（2026-07-23）

日常只需阅读根 [`AGENTS.md`](../../AGENTS.md) 的 Git 速查；执行分支、PR、冻结、发布或回滚时再读本文。

## 不可破坏的原则

1. `main` 永远可构建、可测试、可部署，所有改动通过短分支 PR 进入。
2. 发布内容先完整进入 `main`；`release/x.y.z` 只是已通过 CI 的主干冻结快照。
3. 冻结期修复仍先进入 `main`，再把 release 快进到新的 `origin/main`。
4. 禁止直接推送、强推或删除 `main`；禁止移动或复用已发布标签。
5. commit、push、PR 合并、tag、Release 是不同完成状态，汇报时必须分开。
6. 只有用户明确要求 release 才进入正式发布流程；未获明确授权时不得创建 `release/*`、tag、
   GitHub Release 或上传正式发行制品。

## 分支

| 分支 | 用途 | 退出 |
| --- | --- | --- |
| `main` | 唯一稳定主干 | 长期存在 |
| `release/x.y.z` | 发布冻结、验证与回滚准备 | 发布完成后删除 |
| `feat/需求号-简述` | 单一功能 | 合并后删除 |
| `fix/问题号-简述` | 单一缺陷 | 合并后删除 |
| `chore/简述` | 配置、依赖、构建与仓库维护 | 合并后删除 |
| `exp/简述` | 不承诺合并的实验 | 实验结束后删除或转正式分支 |

简述使用有意义的小写英文 `kebab-case`；`feat`、`fix` 必须带编号；分支名只允许一层 `/`。
release 分支用 `release/0.3.4`，正式标签才使用 `v0.3.4`。

## Commit

使用英文 Conventional Commit 类型/可选 scope，中文摘要和编号正文：

```text
<type>(<scope>): <中文结果>

1. <具体改动>
2. <验证结果或影响>
```

一个 commit 只表达一个可独立理解、可独立回退的意图。常用类型：
`feat`、`fix`、`docs`、`refactor`、`test`、`chore`、`build`、`ci`、`perf`、`revert`。

## 日常开发

```bash
git switch main
git pull --ff-only origin main
git switch -c feat/123-doubao-search

# 开发、测试、提交
git push -u origin feat/123-doubao-search
# PR: feat/123-doubao-search -> main
```

合并前必须：

- 相关测试通过，工作区没有意外文件；
- PR 指向 `main`，`Branch policy`、`Plan CI` 与该改动对应的并行检查通过；纯版本号和发布说明改动允许
  通过 `Release metadata` 快线；
- 分支基于最新 `main`；
- 需要保留独立回退边界时使用 rebase merge，否则可 squash。

`exp/*` 不能直接作为发布来源；验证成功后整理成正式短分支和可审查提交。

绿色 PR 合并后的 `main` push 只核对关联 PR 与检查结果；直接 push、来源不明或检查不完整时自动回退
全量 CI。具体门禁和 Gradle 缓存规则见 [`CI_PIPELINE.md`](./CI_PIPELINE.md)。

存在并行开发时，必须从干净的 `origin/main` 创建独立 worktree。不得把其他工作树中的未提交文件
静默复制进来，也不得为腾位置而 stash、reset 或覆盖它们；交付合入永久分支后再清理完成的 worktree。

## 正式发布

进入本节的前提是用户已经明确要求 release。仅要求实现、修复、构建、提交、push 或合并时，到相应
阶段即停止，不得自行推断发布授权。

1. 将功能、修复、版本号、递增的 `versionCode` 和 `release-notes/x.y.z.md` 全部通过 PR 合入 `main`。
2. 等合并后的 `main` 来源校验或兜底全量 CI 通过，并暂停本版本范围外的功能合并。
3. 从最新 `origin/main` 创建并推送 `release/x.y.z`；确认版本、说明和起点一致。
4. 在 release 上执行构建、升级、安装和关键路径验证。发现问题时，从 `main` 切 `fix/*` 修复并合入，
   再对 release 执行 `git merge --ff-only origin/main`。
5. 验证通过后在 release HEAD 创建一次 annotated `vX.Y.Z` 标签并推送。
6. 私有仓 Release workflow 并行执行关键测试与签名 APK 构建；两者都通过后，把发行资产发布到公开
   [`rgywd/zhixing-releases`](https://github.com/rgywd/zhixing-releases/releases)。
7. 核对公开 Release、Universal APK、源码归档、`SHA256SUMS.txt`、`latest.json` 和应用内更新，再删除
   release 与已合并短分支。

私有仓只保留 tag、workflow 和构建日志，不保留正式 Release 页面。完整分发边界见
[`PUBLIC_RELEASE_DISTRIBUTION.md`](./PUBLIC_RELEASE_DISTRIBUTION.md)。

## 冻结异常与回滚

- release 无法 `--ff-only` 跟进 `main` 时立即停止；不得用普通 merge 或强推掩盖分叉。
- 已发布严重问题通过更高补丁版本处理：从最新 `main` 回退问题提交，经 PR 合入，再切新的 release 发布。
- Android 不允许较低 `versionCode` 覆盖安装；不得删除或移动旧标签伪造回滚。

## 发布门禁

- [ ] 目标提交位于 `origin/main` 历史中，版本号、`versionCode`、说明和 tag 一致。
- [ ] 必需 CI、关键测试、覆盖升级与安装验证通过。
- [ ] 正式 Release 只存在于 `rgywd/zhixing-releases`。
- [ ] 未登录状态可下载更新清单、唯一 Universal APK、源码包与哈希文件。
- [ ] `latest.json.source.commit` 指向正式 tag commit。
- [ ] 发布成功后清理 release 和已合并短分支。
