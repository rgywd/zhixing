---
source_commit: faa1320682d60db0cf30f821d05ed660197e00ed
generated: 2026-07-21
---

# 项目 Wiki 索引
> 本 wiki 由 commitwiki 按 commit 自动生成，供 AI 编程 agent 导航：先读本页，再按需打开 `modules/` 下的模块页；最近进展见 [STATUS.md](STATUS.md)。
## 项目概览
这是一个Android优先的个人AI工作台，采用Kotlin + Jetpack Compose + Material 3构建，支持多AI供应商、流式对话、搜索、语音、文档处理与本地工作区。项目模块化，`app`作为Android客户端入口，集成`ai`、`search`、`speech`等模块，`web-ui`提供Web前端，`work`与`workspace`实现分布式执行与本地环境。整体以本地优先、数据安全为设计理念，通过`_root`模块统一构建配置与开发导航。
## 模块路由
| 模块 | 职责 | 详情 |
|---|---|---|
| `_root` | 项目根模块，集中管理构建配置、多语言文档、许可与 AI 开发指南。 | [modules/_root.md](modules/_root.md) |
| `ai` | 该模块封装多供应商 AI 对话、图像与嵌入生成，并统一消息模型和 UI 交互。 | [modules/ai.md](modules/ai.md) |
| `app` | 知行 AI 助手 Android 应用的全功能主模块，集成聊天、AI 交互、工作区、设置、备份等全部界面与业务逻辑。 | [modules/app.md](modules/app.md) |
| `common` | 公共基础库，提供缓存、网络请求、日志、上下文工具等通用能力。 | [modules/common.md](modules/common.md) |
| `docs` | 该模块是知行项目的设计文档集，为 AI 编程代理提供产品架构、实施计划、协议契约与验收标准的上下文导航。 | [modules/docs.md](modules/docs.md) |
| `document` | MuPDF 文档渲染与解析模块，封装 PDF 操作及多格式转 Markdown 能力。 | [modules/document.md](modules/document.md) |
| `gradle` | **gradle** — 集中管理 Gradle 构建环境、依赖版本与 Wrapper，确保构建一致性。 | [modules/gradle.md](modules/gradle.md) |
| `highlight` | 提供基于 Compose 和 QuickJS 的代码语法高亮组件。 | [modules/highlight.md](modules/highlight.md) |
| `locale-tui` | 一个用于 Android 多语言资源的 TUI 翻译管理工具，支持死条目检测与 AI 批量翻译。 | [modules/locale-tui.md](modules/locale-tui.md) |
| `material3` | **material3** | [modules/material3.md](modules/material3.md) |
| `release-notes` | 记录知行各版本的新特性、修复与升级指引。 | [modules/release-notes.md](modules/release-notes.md) |
| `search` | 搜索模块封装 20+ 搜索引擎的 API 集成，提供统一的搜索与网页抓取接口。 | [modules/search.md](modules/search.md) |
| `speech` | 语音识别与文本转语音的多平台统一接口，支持多种云端提供商。 | [modules/speech.md](modules/speech.md) |
| `web` | 提供嵌入式Ktor Web服务器，托管前端静态资源并支持SPA。 | [modules/web.md](modules/web.md) |
| `web-ui` | **聊天 AI 对话前端界面** | [modules/web-ui.md](modules/web-ui.md) |
| `work` | **work** 模块是分布式工作调度与执行系统，负责创建会话、下发命令、驱动 Codex 执行并双向同步手机端。 | [modules/work.md](modules/work.md) |
| `workspace` | 模块：workspace | [modules/workspace.md](modules/workspace.md) |

## 使用建议
- 修改代码前先读对应模块页的「修改指引」；不确定归属时按上表「职责」列匹配。
- wiki 可能落后于最新代码，各页 frontmatter 的 `source_commit` 标明索引时的代码版本。
