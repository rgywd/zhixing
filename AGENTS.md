# Repository Guidelines

本文档面向贡献者，概述本仓库的模块结构、开发流程，便于快速上手并保持一致的协作质量。

## Wiki First（必须）

开始定位或修改代码前，必须优先查阅项目 Wiki：

1. 先读 `.aiwiki/INDEX.md`，通过模块路由确定目标模块。
2. 再读 `.aiwiki/STATUS.md`，了解最近提交与当前开发进展。
3. 按需打开 `.aiwiki/modules/<模块>.md`，确认职责、数据流和修改入口。
4. 只有 Wiki 信息不足、需要核对精确实现或验证时，才继续搜索和阅读源码。

各 Wiki 页面的 `source_commit` 表示其对应的代码版本；若落后于当前 `HEAD`，应先运行
CommitWiki 更新，或在结论中明确说明版本差异，不能把过期内容当作当前事实。

## Build, Test, and Development Commands

使用 Android Studio 或命令行 Gradle：

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew connectedDebugAndroidTest  # 运行设备/模拟器上的仪器测试
./gradlew lint                   # 运行 Android Lint
```

构建应用不依赖 `google-services.json`。`web` 模块会在 `preBuild` 阶段构建
`web-ui/` 并复制静态资源，需要本地可用 `pnpm`。

## Git Workflow (Required)

先用本节做日常决策；创建分支、提交、合并或发布前，再阅读
[`docs/zhixing/RELEASE_FLOW.md`](docs/zhixing/RELEASE_FLOW.md) 中的完整规则和命令示例。

| 分支 | 用途 |
| --- | --- |
| `main` | 永远可部署 |
| `release/x.y.z` | 发布冻结、修复验证与回滚准备 |
| `feat/需求号-简述` | 功能短分支 |
| `fix/问题号-简述` | 缺陷修复 |
| `chore/简述` | 配置、依赖与构建调整 |
| `exp/简述` | 实验分支，不承诺合并 |

- 分支前缀和简述使用规范英语；简述采用小写 `kebab-case`，`feat`、`fix` 必须带需求号或问题号。
- Commit header 使用英文 Conventional Commit 类型与 scope，摘要和正文使用中文；正文用 `1.`、`2.` 编号说明改动与验证。
- 所有可发布改动先通过短分支 PR 合入 `main`。`main` CI 通过后，才从最新 `main` 切出 `release/x.y.z`。
- `release/x.y.z` 是主干的冻结快照，不是把尚未进入主干的功能整体合回 `main` 的入口。
- 禁止直接推送、强推或删除 `main`；正式标签使用严格的 `vX.Y.Z`，且必须指向 `origin/main` 历史中的提交。
- 单线开发可直接从 `main` 切短分支；检测到其他分支、脏改动或并行任务正在操作时，从干净的
  `origin/main` 创建独立 worktree，不复制、stash、重置或覆盖现有未提交改动。
- 用户未明确要求 release 时，不得创建 `release/*`、tag、GitHub Release 或上传正式发行制品。
  commit、push、PR 合并和测试 APK 均不等于获得发布授权。

## Coding Style & Naming Conventions

本仓库使用 `.editorconfig` 统一格式：

- Kotlin/Gradle 脚本：4 空格缩进，最大行长 120。
- XML/JSON：2 空格缩进。
- Markdown/YAML：2 空格缩进，允许尾随空格（用于对齐）。

命名习惯：模块名为小写目录（如 `ai/`、`speech/`），Kotlin 类遵循 PascalCase，测试类以 `*Test` 结尾。

## Testing Guidelines

测试框架以 JUnit/AndroidX Test 为主。未设定强制覆盖率门槛，但新逻辑应配套新增/更新测试。测试文件命名建议：

- 单元测试：`FooTest.kt`
- 仪器测试：`FooInstrumentedTest.kt` 或 `*Test.kt`

## Module Structure

- **app**: Main application module with UI, ViewModels, and core logic
- **ai**: AI SDK abstraction layer for different providers (OpenAI, Google, Anthropic)
- **common**: Common utilities and extensions
- **document**: Document parsing module for handling PDF, DOCX, PPTX, and EPUB files
- **highlight**: Code syntax highlighting implementation
- **material3**: Material color utility extensions used by the app UI
- **search**: Search functionality SDK for multiple providers (Exa, Tavily, Zhipu, Bing, Brave, SearXNG, and others)
- **speech**: Speech module for TTS and ASR implementations
- **web**: Embedded web server module that provides Ktor server startup function and hosts static frontend build files (
  built from web-ui/ React project)
- **workspace**: Sandboxed per-workspace file system and shell execution environment exposed to the AI as tools.

## Concepts

- **Assistant**: An assistant configuration with system prompts, model parameters, and conversation isolation. Each
  assistant maintains its own settings including temperature, context size, custom headers, tools, memory options, regex
  transformations, and prompt injections (mode/lorebook). Assistants provide isolated chat environments with specific
  behaviors and capabilities. (app/src/main/java/me/rerere/rikkahub/data/model/Assistant.kt)

- **Conversation**: A persistent conversation thread between the user and an assistant. Each conversation maintains a
  list of MessageNodes in a tree structure to support message branching, along with metadata like title, creation time,
  update time, pin status, chat suggestions, optional conversation-level system prompt, and prompt injection bindings. (
  app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **UIMessage**: A platform-agnostic message abstraction that encapsulates chat messages with different types of content
  parts (text, images, documents, reasoning, tool calls/results, etc.). Each message has a role (USER, ASSISTANT,
  SYSTEM, TOOL), creation timestamp, model ID, token usage information, and optional annotations. UIMessages support
  streaming updates through chunk merging. (ai/src/main/java/me/rerere/ai/ui/Message.kt)

- **MessageNode**: A container holding one or more UIMessages to implement message branching functionality. Each node
  maintains a list of alternative messages and tracks which message is currently selected (selectIndex). This enables
  users to regenerate responses and switch between different conversation branches, creating a tree-like conversation
  structure. (app/src/main/java/me/rerere/rikkahub/data/model/Conversation.kt)

- **Message Transformer**: A pipeline mechanism for transforming messages before sending to AI providers (
  InputMessageTransformer) or after receiving responses (OutputMessageTransformer). Transformers can modify message
  content, add metadata, apply templates, handle special tags, convert formats, and perform OCR. Common transformers
  include:
  - TemplateTransformer: Apply Pebble templates to user messages with variables like time/date
  - ThinkTagTransformer: Extract `<think>` tags and convert to reasoning parts
  - RegexOutputTransformer: Apply regex replacements to assistant responses
  - DocumentAsPromptTransformer: Convert document attachments to text prompts
  - Base64ImageToLocalFileTransformer: Convert base64 images to local file references
  - OcrTransformer: Perform OCR on images to extract text

  Output transformers support `visualTransform()` for UI display during streaming and `onGenerationFinish()` for final
  processing after generation completes.
  (app/src/main/java/me/rerere/rikkahub/data/ai/transformers/Transformer.kt)

- **Long-term Profile Memory**: Automatic profiles use a bounded four-stage pipeline: exact quotes from user messages
  become internal `OBSERVATION` records, deterministic longitudinal gates promote qualified observations, and at most
  one canonical automatic `PROFILE` summary is maintained per built-in dimension. Observations never enter prompts;
  only active profiles do. The V2 contract, thresholds, lifecycle, evidence fields, and destructive v35→v36 legacy
  profile cleanup are documented in `docs/zhixing/AUTO_PROFILE_MAINTENANCE.md`.

- **Work / Codex Phone-line**: A separate, mobile-created session domain for communicating with Codex processes on a
  registered development machine. It uses a durable Work Core, an outbound-only local Runner, and exactly three MCP
  tools (`report`, `ask`, `report_html`). It must not read Codex Desktop history, depend on Happy/App Server protocols,
  expose arbitrary repository paths, or reuse the normal Provider generation pipeline. Product and protocol boundaries
  are defined in `docs/zhixing/CODEX_PHONE_LINE_ARCHITECTURE.md` and
  `docs/zhixing/CODEX_PHONE_LINE_CONTRACT.md`.

- **Agenda / Weak Calendar**: A local-first task domain surfaced in the normal chat right drawer. Room tasks are the
  source of truth; Android system calendar events are an optional read-only projection. Normal chat exposes five task
  tools, with approval required for every write. The current contract is documented in
  `docs/zhixing/AGENDA_AND_CALENDAR.md`.

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.

<!-- commitwiki:start -->
## 项目 Wiki（AI 导航 · commitwiki 自动生成，勿手改此区块）

了解本项目前，先读 `.aiwiki/INDEX.md`（模块路由表），按需打开 `.aiwiki/modules/*.md`；
大文件内部的类/函数与行号见 `.aiwiki/files/*.md` 符号导航页；最近开发进展见 `.aiwiki/STATUS.md`。
页面 frontmatter 的 `source_commit` 表示 wiki 对应的代码版本。
不要为"了解项目结构"而通读源码——先查 wiki。
<!-- commitwiki:end -->
