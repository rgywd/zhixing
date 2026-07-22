---
source_commit: c2ef85031701bb57c43e639bcd2eb032215efe90
generated: 2026-07-22
---


# 项目 Wiki 索引

> 本 wiki 由 commitwiki 按 commit 自动生成，供 AI 编程 agent 导航：先读本页，再按需打开 `modules/` 下的模块页；最近进展见 [STATUS.md](STATUS.md)。

## 项目概览

这是一个Android优先的个人AI工作台，采用Kotlin + Jetpack Compose + Material 3构建，支持多AI供应商、流式对话、搜索、语音、文档处理与本地工作区。项目模块化，`app`作为Android客户端入口，集成`ai`、`search`、`speech`等模块，`web-ui`提供Web前端，`work`与`workspace`实现分布式执行与本地环境。整体以本地优先、数据安全为设计理念，通过`_root`模块统一构建配置与开发导航。


## 模块路由

| 模块 | 职责 | 详情 |
|---|---|---|
| `_root` | `_root` 模块是项目入口与元信息中心，负责提供整体规范、许可、构建配置和开发指引。 | [modules/_root.md](modules/_root.md) |
| `ai` | AI 模块 | [modules/ai.md](modules/ai.md) · [src](modules/ai/src.md) |
| `app` | Android 应用入口模块，管理构建配置、签名、混淆与启动性能优化。 | [modules/app.md](modules/app.md) · [baselineprofile](modules/app/baselineprofile.md) · [src](modules/app/src.md) |
| `common` | 模块职责：为 Android 应用提供通用基础设施，包括缓存、HTTP 网络、日志记录与 JS 引擎注入。 | [modules/common.md](modules/common.md) |
| `docs` | 项目文档与规范知识库，统一提供架构、产品设计、发布流程等导航信息。 | [modules/docs.md](modules/docs.md) |
| `document` | 文档处理模块，负责文档生成、解析与存储管理，当前尚未实现功能。 | [modules/document.md](modules/document.md) · [src](modules/document/src.md) |
| `gradle` | Gradle 构建环境与依赖版本配置中心 | [modules/gradle.md](modules/gradle.md) |
| `highlight` | 该模块提供代码语法高亮的 Compose 组件，基于 QuickJS 执行 Prism 脚本解析代码。 | [modules/highlight.md](modules/highlight.md) |
| `locale-tui` | locale-tui 是一个基于 Textual 的 Android 多语言资源文件翻译管理 TUI 工具。 | [modules/locale-tui.md](modules/locale-tui.md) |
| `material3` | material3 模块封装 Material3 动态配色方案到 Compose ColorScheme 的映射。 | [modules/material3.md](modules/material3.md) |
| `release-notes` | 记录知行各版本的功能变更、修复与升级指引，供用户与开发者查阅。 | [modules/release-notes.md](modules/release-notes.md) |
| `search` | 搜索模块提供统一接口接入多种搜索引擎及自定义JS脚本，实现搜索与网页抓取。 | [modules/search.md](modules/search.md) |
| `speech` | 语音识别（ASR）与文本转语音（TTS）能力收敛模块 | [modules/speech.md](modules/speech.md) · [src](modules/speech/src.md) |
| `web` | 提供 Web 管理界面与 HTTP 服务，集成前端静态资源并启动 Ktor 嵌入式服务器。 | [modules/web.md](modules/web.md) |
| `web-ui` | Web UI 是 AI 对话应用的单页前端，基于 React Router 7 + Vite 构建，负责界面渲染、路由与状态管理。 | [modules/web-ui.md](modules/web-ui.md) · [.vscode](modules/web-ui/.vscode.md) · [app](modules/web-ui/app.md) |
| `work` | 实现 AI 与手机端异步协作的电话线服务，管理会话、报告与指令分发。 | [modules/work.md](modules/work.md) |
| `workspace` | 工作空间管理模块：负责 Android 端工作空间的全生命周期管理，包括根文件系统安装、沙箱文件操作、PRoot 命令执行及知识空间存储与搜索。 | [modules/workspace.md](modules/workspace.md) |

## 使用建议

- 修改代码前先读对应模块页的「修改指引」；不确定归属时按上表「职责」列匹配。
- 单文件内部结构（类/函数/行号）见 `files/` 下的符号导航页（≥300 行的文件自动生成）。
- wiki 可能落后于最新代码，各页 frontmatter 的 `source_commit` 标明索引时的代码版本。
