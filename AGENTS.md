# Repository Guidelines

本文档面向贡献者，概述本仓库的模块结构、开发流程，便于快速上手并保持一致的协作质量。

## Documentation and Code Navigation

产品、架构与交付契约统一从 [`docs/zhixing/README.md`](docs/zhixing/README.md) 进入。实现定位使用
`rg --files`、`rg` 和源码符号；文档用于解释边界与决策，源码和测试是当前实现的事实来源。

## Build, Test, and Development Commands

使用 Android Studio 或命令行 Gradle：

```bash
./gradlew assembleDebug          # 构建 Debug APK
./gradlew test                   # 运行所有模块的 JVM 单元测试
./gradlew connectedDebugAndroidTest  # 运行设备/模拟器上的仪器测试
./gradlew lint                   # 运行 Android Lint
```

提交最终改动后、push 或创建 PR 前，统一运行本地门禁：

```bash
git fetch origin main
node .github/scripts/local-verify.mjs
```

脚本要求工作树干净，按 `origin/main...HEAD` 自动选择 Work、Android、纯文档或发布元数据检查，并把
通过记录写入当前 worktree 的 Git 元数据目录。GitHub PR 只做轻量策略校验，不替代这条本地门禁。

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
- 所有可发布改动先通过短分支 PR 合入 `main`。本地门禁和 PR policy 通过后，才从最新 `main` 切出
  `release/x.y.z`。
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

日常重测试默认在开发机运行；GitHub-hosted runner 仅保留单 Job PR policy 和正式 tag 的 Release 门禁。
局部开发可先运行目标测试，但 push 前仍需在最终 clean commit 上运行 `local-verify.mjs`。

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

- **Memory Documents**: Long-term memory is a Room-backed Markdown document layer with a compact listing,
  always-visible `/profile.md` and `/preferences.md`, and on-demand reads for `/areas`, `/topics`, and `/people`.
  Reads and mutations are optional during the active foreground chat run: unrelated questions do not read memory, and
  no mutation means no `memory_write` call or Run finalization. Every persisted fact is `[stated]`, carries a user
  source, and uses file-level optimistic locking. Raw conversation FTS is a separate capability. The V3 contract and
  V2 migration boundary are documented in
  `docs/zhixing/MEMORY_SYSTEM.md`.

- **Work / Agent Phone-line**: A separate, mobile-created session domain for communicating with Codex or Claude Code
  processes on a registered development machine. It uses a durable Work Core, an outbound-only local Runner, and exactly
  three MCP tools (`report`, `ask`, `report_html`). Codex turns use a Runner-internal, version-pinned App Server adapter;
  that private JSONL protocol must never be exposed to the phone. Work must not read desktop history, depend on Happy,
  expose arbitrary repository paths, or reuse the normal Provider generation pipeline. Product and protocol
  boundaries are defined in `docs/zhixing/CODEX_PHONE_LINE_ARCHITECTURE.md` and
  `docs/zhixing/CODEX_PHONE_LINE_CONTRACT.md`.

- **Agenda / Weak Calendar**: A local-first domain for simple tasks and long-horizon plans, surfaced compactly in the
  normal chat right drawer with a full plan/stage page for detail. Room is the source of truth; Android system calendar
  events are an optional read-only projection. Normal chat exposes read and write tools for both task types. Tool calls
  execute without a separate approval card while repository validation, Android permissions, privacy consent,
  credentials, and hard safety boundaries remain enforced. The current contract is documented in
  `docs/zhixing/AGENDA_AND_CALENDAR.md`.

## Internationalization

- String resources are usually located in `app/src/main/res/values*/strings.xml`; feature modules such as `search`
  may also maintain their own `values*/strings.xml`
- Use `stringResource(R.string.key_name)` in Compose
- Page-specific strings should use page prefix (e.g., `setting_page_`)
- If the user does not explicitly request localization, prioritize implementing functionality without considering
  localization. (e.g `Text("Hello world")`)
- For `locale-tui` operations, use the `locale-tui-localization` skill.
