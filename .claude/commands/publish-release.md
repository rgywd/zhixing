请基于git log生成更新日志，然后创建一个新的release

## 更新日志

- 查看从上一次release tag到当前commit的修改
- 总结更新内容并生成更新日志, BUG修复和UI调整尽可能合并，确保总更新日志条目数量不超过 10条
- 日志中避免出现技术名词

使用以下格式:

```markdown
更新内容:

- xxx
- xxx

Updates:

- xxx
- xxx
```

(双语更新日志)

更新日志生成完后，请求用户确认更新日志是否合理，等到用户确认可以发布后，创建release

## 发布

发布由 `.github/workflows/release.yml` 自动完成，**不要手动构建或上传 APK**：

1. 确认要发布的 commit 已合入 main（main 有分支保护，需走 PR + CI）
2. 在该 commit 上打 **带 v 前缀** 的 tag（例如 `v0.1.8`）并推送——workflow 只匹配 `v*`，
   不带 v 的 tag 不会触发发布
3. Release workflow 自动完成：关键单测、签名构建 arm64/x86_64/universal 三个 APK、
   生成 latest.json 与 SHA256SUMS、创建 GitHub Release（整条约 15 分钟）
4. workflow 完成后，把双语更新日志更新到 release 描述中（自动生成的 notes 只有
   commit 列表，需替换为上面的更新日志格式）
