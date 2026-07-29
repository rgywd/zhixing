# 知行 OrbitOS CN Vault v0.3

状态：v0.3 已实现，本文是 vault 目录、浏览、Git 绑定、导入、检索、引用和 AI 维护边界的现行契约。

本结构基于 [MarsWang42/OrbitOS 的 CN 版](https://github.com/MarsWang42/OrbitOS/tree/main/CN)
调整，以本文件记录的知行版本为准。知行版本新增 `60_工具/`，并采用用户已经迁移完成的
vault-only 模型，不保留旧 `PROJECT.md` 或 `knowledge/` 兼容层。

## 1. 产品边界

一个绑定 Workspace 的个人知识库由三层组成：

```text
用户事实层  /workspace/vault/       Obsidian vault，可由 Git 跨设备维护
本机派生层  /workspace/.zhixing/    格式标记、归一文本和来源映射，可重建
执行层      RootFS                  Shell、Git、脚本与 AI 工具运行环境，可重建
```

`/workspace` 是 Workspace 持久文件区在 RootFS 内的绑定挂载。vault 不依赖 RootFS 才能被状态、
检索和精确读取工具使用；安装 RootFS 后，普通 Workspace 工具和 Git 可以直接在
`/workspace/vault` 工作。

## 2. Vault 目录契约

初始化必须幂等，已有目录、说明、模板、技能和用户内容不得被覆盖。`vault/AGENTS.md` 是可随 Git
同步的身份与维护契约；已有 `vault/AGENTS.md` 的手机 vault 会被直接原地识别，不要求预先存在本机标记。

| 类型 | 目录 | frontmatter `type` | 命名 |
| --- | --- | --- | --- |
| 收件箱条目 | `00_收件箱/` | 不要求 | 随意，AI 后续归类 |
| 日记 | `10_日记/` | 不要求 | `YYYY-MM-DD.md` |
| 项目 | `20_项目/` | 不要求 | C.A.P.：Context / Actions / Progress |
| 研究主笔记 | `30_研究/<领域>/<主题>/` | `reference` | `<主题>.md` |
| 原子概念 | `40_知识库/<分类>/` | 不要求，使用 Wiki 模板 | `<概念名>.md` |
| 工具条目 | `60_工具/<类别>/` | `tool` | `<工具名>.md` |
| 计划 | `90_计划/` | 不要求 | `Plan_YYYY-MM-DD_<主题>.md` |

其余稳定目录：

- `50_资源/`：精选外部资源；
- `99_系统/模板/`：Daily、Inbox、Project、Wiki 和 Content 模板；
- `99_系统/数据库/`：Obsidian Bases；
- `99_系统/提示词/`：领域提示词；
- `.agents/skills/`：vault 自带工作流技能的唯一内容来源；
- `.claude/`、`.codex/`、`.gemini/`：不同 CLI 的桥接入口；
- `AGENTS.md`、`CLAUDE.md`、`GEMINI.md`：通用规则和客户端入口。

`.zhixing/knowledge-space.json` 位于 Workspace 根而非 vault 内，只记录本机格式版本和
`contentRoot = vault`，不得成为识别已同步 vault 的唯一条件。

## 3. 捕获、导入与归一

- UI 的“导入到收件箱”和 `knowledge_ingest` 都把原文件复制到 `vault/00_收件箱/`。
- 同名冲突使用安全后缀，不覆盖已有条目。
- Markdown、文本和常见代码/结构化文本直接作为 vault 事实来源检索，不生成重复副本。
- PDF、DOCX、PPTX、EPUB 等需要解析的文件保留原文，并把归一 Markdown 写入
  `.zhixing/knowledge/normalized/`。
- 来源映射写入 `.zhixing/knowledge/metadata/`；派生文件可以重建，不能成为唯一事实来源。
- 导入只完成捕获，不猜测项目、研究、概念或工具分类。后续分类必须遵守 `vault/AGENTS.md`。
- 单次导入不修改原文件，不执行其中脚本，也不通过网络上传内容。

## 4. 检索与引用

本地全文检索覆盖：

- `AGENTS.md`、`CLAUDE.md`、`GEMINI.md`；
- `00_收件箱/`、`10_日记/`、`20_项目/`、`30_研究/`、`40_知识库/`；
- `50_资源/`、`60_工具/`、`90_计划/`、`99_系统/`；
- `.agents/skills/`；
- `.zhixing/knowledge/normalized/` 中的派生 Markdown。

只扫描受支持的文本扩展名和 `.base` 文件，不扫描图片、普通二进制、`.git/`、`.env`、`.env.*`、
`.obsidian/`、`.gemini/settings.json` 或其他客户端配置。符号链接桥不重复跟随，
`.agents/skills/` 是技能检索的规范路径。

每条命中至少返回：

- `path`：可精确读取的 vault 文件或派生 Markdown；
- `sourcePath`：用户原文件路径；
- `line` 与 `excerpt`：命中行和上下文；
- `citation`：稳定的 `workspace://<path>#L<line>` 引用。

AI 只有在用户要求查阅 vault，或当前任务明确依赖 vault 内容时才搜索。普通回答不以搜索为前置条件；
零命中不得阻断普通回答，也不得把模型常识伪装成 vault 事实。

## 5. AI 工具与维护契约

- `knowledge_status`：只读，返回 `contentRoot`、内容文件数和可检索文档数。
- `knowledge_search`：只读，跨编号目录和 vault-local skills 返回有限匹配。
- `knowledge_read`：只读，只接受搜索边界内的文本文件和派生 Markdown。
- `knowledge_ingest`：持久写入 `00_收件箱/`，默认需要用户批准。

普通 `workspace_*` 工具继续负责用户明确要求的维护和工具实现。绑定 vault 后，系统提示必须说明：

1. 维护前读取 `vault/AGENTS.md`；
2. 未分类内容默认进入 `00_收件箱/`；
3. 研究主笔记使用 `type: reference`，工具条目使用 `type: tool`；
4. 项目使用 C.A.P.，计划使用规定命名；
5. vault-local skills 位于 `vault/.agents/skills/`；
6. `.env` 只作为本地变量来源；模型按已注入的变量名调用，不读取、显示或记录文件内容；
7. 不读取凭据、不修改 `.git/`，未获用户明确要求不执行 Git 写操作。

`60_工具/` 同时允许保存工具说明、在用脚本和工具创意。工具条目必须记录用途、入口、输入输出、限制与
来源；credential 不得写入条目、脚本、日志或 Git。

## 6. 知识库页面

Workspace 详情提供独立的“知识库”页面：

- 从 `/workspace/vault` 根目录开始浏览文件夹和文件，不需要安装 RootFS；
- 点文件和点目录不出现在此页面，尤其不得展示 `.git/`、`.env` 和客户端私有配置；
- 文本复用现有编辑器打开，图片和其他文件复用现有本地预览能力；
- 目录路径始终限制在 vault 内，拒绝 `..` 等路径逃逸；
- 首版不在知识库页面提供删除、移动、批量重命名或冲突处理。

普通 Workspace 文件页继续保留，负责完整的文件和 RootFS 管理；知识库页面是面向 vault 语义的安全入口，
不是第二份文件状态。

## 7. Git 与备份边界

- 用户可以在知识库页面输入 HTTPS 或 SSH 仓库地址，显式为已有 vault 初始化 Git 并绑定 `origin`。
- “绑定”只执行必要的 `git init` 和 `git remote add/set-url`，不得自动 clone、fetch、pull、merge、
  commit、push 或解决冲突。
- 已存在不同 `origin` 时必须再次确认；替换操作只改远程地址，不改本地文件和提交。
- Git 绑定依赖 RootFS 内已安装 `git`；缺失时知识库页面提供显式安装动作，使用 RootFS 的
  `apt-get` 安装，安装失败或未完成不影响 vault 浏览。
- 仓库地址不得内嵌 HTTPS credential；知行不接管 Git token、密码、SSH 私钥或认证流程。
- 初始化和绑定确保 `.gitignore` 包含 `.env`、`.env.*`，同时允许提交不含值的 `.env.example`。
- 知行不得接管 Git 凭据，也不得把 `.git/` 暴露给知识读取工具。
- vault 中已提交的文本可以随 Git 同步，但未提交内容、未跟踪资源和大文件策略仍由用户管理。
- Git 不是知行完整备份的替代品；Workspace 文件备份与恢复仍遵循
  [`DATA_SAFETY_AND_BACKUP.md`](./DATA_SAFETY_AND_BACKUP.md)。

## 8. v0.3 验收

1. 初始化空 Workspace 后只创建 `vault/`、本机 `.zhixing/` 和对应模板，不创建旧目录。
2. 重复初始化不覆盖现有 `vault/AGENTS.md`、模板、技能或用户文件。
3. 只要已有 `vault/AGENTS.md`，即使缺少本机 marker，也能原地识别并启用知识工具。
4. 导入文件进入 `00_收件箱/`；文本不生成重复索引，二进制归一文本位于 vault 外。
5. 中文查询能命中研究主笔记和 `60_工具/` 条目，并返回行号、原文路径和 citation。
6. 读取拒绝 vault 外路径、路径逃逸、`.git/`、`.env` 和客户端凭据配置。
7. 未安装 RootFS 时 status/search/read 仍可使用；Git 和 Shell 能力按 RootFS 状态独立降级；
   RootFS 就绪但缺少 Git 时可从知识库页面安装。
8. UI 的知识库页可以逐级打开编号目录和文件，且不显示点文件或点目录。
9. 绑定空 vault 会初始化 `main` 分支并设置 `origin`，不执行任何同步命令。
10. 已有不同 `origin` 时先确认再替换；无论绑定还是替换，都不修改用户内容。
