# 知行 Git 分支、提交与发布流程

## 阅读顺序

日常工作先看根目录 [`AGENTS.md`](../../AGENTS.md) 的 Git 速查。只有在创建分支、编写 commit message、
发起 Pull Request、冻结版本或处理回滚时，才需要展开本文对应章节。

## 不可破坏的原则

1. `main` 永远可构建、可测试、可部署，不直接承载开发过程。
2. 所有功能、修复、版本号和发布说明先通过短分支 PR 进入 `main`。
3. `main` 合并并通过 CI 后，才从最新 `origin/main` 切出 `release/x.y.z`。
4. `release/x.y.z` 是已进入主干代码的冻结快照，不是先收集功能、再整体合回 `main` 的集成分支。
5. 禁止直接推送、强推或删除 `main`，禁止移动或复用已经发布的版本标签。

## 分支规则

| 分支 | 用途 | 生命周期 |
| --- | --- | --- |
| `main` | 唯一稳定主干，永远可部署 | 长期存在 |
| `release/x.y.z` | 发布冻结、修复验证和回滚准备 | 发布完成后删除 |
| `feat/需求号-简述` | 单一功能需求 | 合并后删除 |
| `fix/问题号-简述` | 单一缺陷修复 | 合并后删除 |
| `chore/简述` | 配置、依赖、构建和仓库维护 | 合并后删除 |
| `exp/简述` | 验证性实验，不承诺合并 | 实验结束后删除或转正式分支 |

### 命名要求

- 分支名只允许一层 `/`。
- 类型前缀使用表中固定的规范英语单词。
- 简述必须使用有明确含义的英文、小写字母和 `kebab-case`；不要使用中文、拼音、空格或含糊缩写。
- `feat` 和 `fix` 必须在简述前带需求号或问题号。
- `release` 必须使用严格的三段式语义版本号 `x.y.z`。

正确示例：

```text
feat/123-doubao-search
fix/456-search-timeout
chore/upgrade-gradle
exp/multi-search-ranking
release/0.3.2
```

错误示例：

```text
feat/doubao-search          # 缺少需求号
fix/456-xiufu-sousuo        # 使用拼音
chore/Update_Gradle         # 不是小写 kebab-case
release/v0.3.2              # release 分支不带 v 前缀
feat/123/search/provider    # 超过一层斜杠
```

## Commit message 规范

Commit 使用 Conventional Commits 结构，但面向人的描述必须使用中文：

```text
<type>(<scope>): <中文摘要>

1. <中文说明具体改动>
2. <中文说明验证结果或影响范围>
```

具体要求：

- `type` 和可选的 `scope` 使用规范英语、小写字母，例如 `feat(search)`、`fix(work)`、`docs(git)`。
- 常用类型包括 `feat`、`fix`、`docs`、`refactor`、`test`、`chore`、`build`、`ci`、`perf` 和 `revert`。
- header 的摘要使用简洁中文，说明结果，不写英文流水账，不在末尾加句号。
- 正文必须至少包含 `1.`、`2.` 两个中文编号项；第一项说明改动，第二项说明验证或影响。
- 一个 commit 只表达一个可独立理解、可独立回退的意图。需要单独回退的能力必须拆成独立 commit。

功能提交示例：

```text
feat(search): 支持豆包搜索

1. 接入豆包 Web 搜索接口并增加 API Key 配置。
2. 补充请求解析测试并通过 Search 模块单元测试。
```

修复提交示例：

```text
fix(search): 修复单个搜索源失败时中断查询

1. 隔离各搜索源异常并保留其他来源的成功结果。
2. 增加部分失败和全部失败场景的回归测试。
```

## 日常开发流程

1. 同步最新 `main`，确认工作区干净。
2. 从 `main` 创建与任务类型匹配的短分支，一个分支只处理一个目标。
3. 按 commit message 规范提交，运行与改动风险相匹配的测试。
4. 推送短分支，创建以 `main` 为目标的 Pull Request。
5. `Branch policy` 与 `Build and test` 全部通过后才能合并。
6. 需要保留独立回退边界时使用 rebase merge；只有确认无需保留分步历史时才使用 squash merge。
7. 合并完成后删除本地和远端短分支，不复用已合并分支。

```bash
git switch main
git pull --ff-only origin main
git switch -c feat/123-doubao-search

# 开发、测试、按规范提交
git push -u origin feat/123-doubao-search
# 创建 PR：feat/123-doubao-search -> main
```

`exp/*` 的成果不能直接作为发布来源。实验验证成功后，应整理成新的正式短分支和可审查提交。

## 正式发布：main 优先

### 1. 先把发布内容合入 main

1. 功能和缺陷修复分别通过 `feat/*`、`fix/*` PR 合入 `main`。
2. 版本号、单调递增的 `versionCode` 和发布说明通过 `chore/prepare-x-y-z` 短分支 PR 合入 `main`。
3. 等待合并后的 `main` CI 通过，并暂停新的 `feat/*`、`exp/*` 合并，进入发布冻结。

到这一步，准备发布的全部内容已经在 `main`。禁止把尚未进入 `main` 的功能先并入 `release/x.y.z`，
再用一个 release PR 整体合回 `main`。

### 2. 再从 main 切 release

从最新、已通过 CI 的 `origin/main` 创建发布分支：

```bash
git switch main
git pull --ff-only origin main
git switch -c release/0.3.2
git push -u origin release/0.3.2
```

创建后应确认：

- `release/x.y.z` 的起点与准备发布的 `origin/main` 提交一致。
- `app/build.gradle.kts` 的 `versionName` 与分支版本一致。
- `versionCode` 高于所有已发布版本。
- `release-notes/x.y.z.md` 已存在并描述实际交付内容。

### 3. 冻结期修复仍然先进入 main

`release/x.y.z` 用于固定验证基线和确认发布修复，不直接产生只存在于 release 的提交。若冻结期发现问题：

1. 从最新 `main` 创建 `fix/问题号-英文简述`。
2. 修复 PR 先合入 `main`，等待 `main` CI 通过。
3. 冻结期间保持其他功能暂停，将 `release/x.y.z` 快进到新的 `origin/main`。
4. 重新执行发布验证。

```bash
git switch release/0.3.2
git fetch origin
git merge --ff-only origin/main
git push origin release/0.3.2
```

如果不能 `--ff-only`，说明 release 已经产生独立历史或主干混入了不属于本次发布的内容；立即停止，
查明分叉原因，不得用强推或普通 merge 掩盖问题。

### 4. 标签与发布

1. 完成发布验证，确认 `release/x.y.z` HEAD 位于 `origin/main` 历史中。
2. 在该提交上创建且只创建一次 `vX.Y.Z` annotated tag；标签版本必须与 `versionName` 一致。
3. 推送标签，由 `Release` workflow 回归关键路径、构建签名 APK、生成哈希和 GitHub Release。
4. 验证 Release 中的 Universal、ARM64、x86_64 APK、`SHA256SUMS.txt`、`latest.json` 和应用内更新检查。
5. 发布成功后删除本地和远端 `release/x.y.z`，再恢复常规功能合并。

```bash
git switch release/0.3.2
git tag -a v0.3.2 -m "Zhixing v0.3.2"
git push origin v0.3.2
```

## 缺陷修复与回滚

- 非冻结期缺陷：从最新 `main` 创建 `fix/问题号-英文简述`，通过 PR 合入 `main`。
- 冻结期缺陷：修复仍先进入 `main`，随后只允许将 release 快进到对应主干提交。
- 已发布版本出现严重问题：禁止删除或移动旧标签；从最新 `main` 创建修复分支，回退问题提交，更新为更高的
  补丁版本和 `versionCode`，先合入 `main`，再从 `main` 切新的 `release/x.y.(z+1)` 发布。

Android 不支持把较低 `versionCode` 当作升级包，因此回滚必须通过更高版本号的新发布完成。

## GitHub 门禁

`main` 必须满足：

- 只允许通过 Pull Request 合并。
- 必须通过 `Branch policy` 与 `Build and test`。
- 合并前必须基于最新 `main`。
- 禁止 force push 和删除分支。
- 保持线性历史；根据是否需要保留独立回退边界选择 rebase 或 squash。

正式发布还必须满足：

- `release/x.y.z` 来自已经通过 CI 的 `main`，不能领先于主干承载未合入功能。
- `vX.Y.Z` 符合严格格式，且标签提交位于 `origin/main` 历史中。
- `Release` workflow 成功，发布资产和更新清单完成实际下载与哈希核验。

## 操作检查单

### 合并短分支前

- [ ] 分支名的类型、编号和英文 `kebab-case` 简述符合规范。
- [ ] 每个 commit 使用英文 type/scope、中文摘要和 `1.`、`2.` 编号正文。
- [ ] 相关测试已通过，工作区没有意外文件。
- [ ] PR 指向 `main`，必需 CI 全部通过。

### 创建 release 前

- [ ] 本版本所有功能、修复、版本号和发布说明已经合入 `main`。
- [ ] 合并后的 `main` CI 成功，新的功能合并已经暂停。
- [ ] `release/x.y.z` 将从最新 `origin/main` 创建，而不是反向合回主干。

### 推送标签后

- [ ] 标签版本与 `versionName` 一致，标签提交属于 `origin/main`。
- [ ] Release workflow 成功。
- [ ] APK、`latest.json` 和 SHA256 已核验。
- [ ] release 与已合并短分支已清理。
