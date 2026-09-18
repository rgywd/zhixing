# CI 与本地构建流水线

状态：生效

目标是在 GitHub Free 的 Actions 月度额度内保留正式发布可信度。日常重测试由注册开发机执行，GitHub
CI 镜像 PR 只运行轻量策略门禁，正式 tag 仍由 `.github/workflows/release.yml` 完成测试、签名和 Gitee 发布。
事实来源是 `.github/scripts/local-verify.mjs`、`.github/workflows/ci.yml` 与
`.github/workflows/release.yml`。

## 本地门禁

最终 commit 完成后、push 或创建 PR 前运行：

```powershell
git fetch origin main
node .github/scripts/local-verify.mjs
```

脚本默认要求 clean worktree，以 `origin/main...HEAD` 的完整 diff 选择门禁：

| 改动域 | 本地执行内容 |
| --- | --- |
| `work/`、`staging-driver/` | Work 与 staging driver 测试 |
| Android 模块、`web-ui/`、Gradle 配置与 wrapper | 递归初始化 submodule、web 依赖、完整 Android JVM 测试、lint、debug/staging/androidTest 构建 |
| 同时涉及 Work 与 Android | 两组全部执行 |
| `docs/`、Markdown、agent/Claude skill mirror | 仓库策略测试与 skill mirror 校验 |
| 纯版本号与发布说明 | 仓库策略和 release metadata 校验 |
| CI、仓库基础设施、空 diff 或无法明确归类的路径 | 保守执行 Work 与 Android 全量门禁 |

通过后，脚本把 commit、diff SHA-256、执行计划、命令集合和耗时写入当前 worktree 的 Git 元数据目录；
记录不进入提交，也不包含 credential。局部开发可使用 `--mode work`、`--mode android` 或 `--dry-run`，
但交付前必须在最终 clean commit 上使用默认 `auto` 或更严格的 `full` 模式重新运行。

## GitHub PR policy

`.github/workflows/ci.yml` 只响应面向 `main` 或 `release/**` 的 pull request，并只创建一个 `PR policy`
Job。该 Job 执行：

- CI 规划、release metadata 与本地验证脚本的规则测试；
- `.agents/skills` 与 `.claude/skills` 镜像一致性；
- 短分支命名；
- 完整 diff 分类；
- 纯版本号和发布说明 PR 的 metadata 校验。

它不运行 Work 测试、Gradle、Android lint 或 APK 构建。单 Job 避免多个不足一分钟的任务分别向上取整。
`main` push 不触发 workflow；Gitee 主仓的合并纪律由本地门禁、GitHub CI 镜像的 PR policy、
Release workflow 和本文件共同约束。启用平台级分支保护后再以其限制直接推送。

## Release

正式 tag 仍触发完整云端门禁：

1. `Resolve release` 校验 tag、版本号和 `origin/main` 祖先关系；
2. `Release tests` 与 `Signed release APK` 在独立 runner 上并行；签名构建注入并校验生产配置；
3. 两者成功后，`Publish Gitee release` 下载同一次运行的签名 APK，生成源码、更新清单与校验和；
4. 正式资产只发布到 `rongguiyewd/zhixing`，发布 Job 使用 CI 镜像的 `GITEE_PAT` secret。

GitHub 上的 release tests 是发布门禁，不因本地已通过而跳过。发布失败重跑同一 tag，不移动或复用标签。

## 故障处理

- 本地门禁失败：修复后在最终 commit 上完整重跑，不通过删测试或伪造记录绕过。
- 开发机缺少 JDK、Node、pnpm、Python 或 Android SDK：先修复环境，不回退到日常云端重任务。
- PR policy 失败：修复策略、分支或发布元数据后再合并。
- 开发机不可用且存在紧急修复：先恢复或切换受控开发机；若确需临时云端全量验证，必须通过单独的手动
  workflow 和明确决策恢复，不能把日常全量触发静默加回。
