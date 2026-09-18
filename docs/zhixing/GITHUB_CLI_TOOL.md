# 受控 GitHub CLI 工具

## 边界

- 模型侧工具名保持为 `gh`，参数沿用 GitHub CLI；不引入额外命令名。
- 第一版只允许 `gh issue` 的显式子命令，并固定目标为私有 GitHub CI 与 Issue 镜像 `rgywd/zhixing`。
- 普通聊天直接执行受控调用；首次调用若 Rootfs 尚未安装 `/usr/bin/gh`，会通过 Ubuntu `apt` 自动安装
  `ca-certificates` 与 `gh`。命令、仓库、stdin 和凭据边界不因取消逐次审批而放宽。
- GitHub Token 继续复用“设置 → 关于 → GitHub Issue 提交”入口，保存在 Android Keystore 加密的
  `noBackupFilesDir` 中。

## 凭据流

```text
模型 gh(args, stdin)
  -> 参数、子命令和仓库校验
  -> App 从 Keystore 读取 Token
  -> 仅向本次 /usr/bin/gh 子进程注入 GH_TOKEN
  -> 清洗 stdout/stderr 后返回
```

`workspace_shell` 不继承 `GH_TOKEN`。每次调用会生成随机中继变量名，Token 不进入 PRoot 命令行；固定启动脚本
只按位置参数转交模型提供的参数，不执行字符串拼接或二次求值。`GH_CONFIG_DIR` 指向 Rootfs 临时目录，Token
不写入 Rootfs 的持久配置。

## 第一版命令策略

允许读取类 Issue 子命令：`list`、`status`、`view`。

允许写入类 Issue 子命令：`create`、`close`、`comment`、`delete`、`edit`、`lock`、`pin`、`reopen`、
`unlock`、`unpin`。

禁止其他顶层命令，因此模型不能调用 `gh api`、`gh auth token`、alias、extension、secret 或 variable。
显式 `--repo` 只能等于 `rgywd/zhixing`，`--body-file`/`-F` 只能使用 `-` 从受控 stdin 读取。
公开源码与正式发布入口位于 Gitee；此工具的 Issue 数据仍位于 GitHub 镜像，需持有相应仓库权限。

扩展到 PR、Actions 或 Release 前，必须先同步调整设置入口中的 Token 权限说明和命令策略；
应用内的 GitHub Token 与 Actions 的 `GITHUB_TOKEN` 分别管理。
