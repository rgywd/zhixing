---
source_commit: 762c37f2ba2c6f3f982c8b1ae5761fadb904b8af
generated: 2026-07-21
---


# 模块：locale-tui

**locale-tui** 是一个基于 Textual 的 Android 多语言资源文件翻译管理 TUI 工具。

模块按功能分层：`screens/` 管理界面（模块选择与翻译表格），`services/` 封装 XML 解析、死条目检测及 AI 翻译，`models/entry.py` 定义翻译条目，`widgets/edit_modal.py` 提供编辑弹窗。数据流为：加载 `config.yml`→选择模块→解析 XML→检测死条目→AI 翻译缺失条目→保存回写。对外提供 CLI 与 TUI 双入口，依赖 OpenAI API 与 Textual 框架。

**修改指引**：UI 调整在 `screens/` 或 `widgets/`，业务逻辑修改在 `services/`，配置项变更在 `config.py` 与 `config.yml`。


## 文件摘要

### `locale-tui/CLAUDE.md`

AI 助手的项目指导文档，定义开发命令、架构、配置与数据流。  
- `config.yml`：模块、语言、翻译设置  
- `.env`：`OPENAI_API_KEY`、`OPENAI_BASE_URL`  
- 开发命令：`uv run python src/main.py`、`uv run textual run --dev src/main.py`  
- 核心组件：`app.py`、`config.py`、`TranslationEntry`  
- 数据流：加载配置 → 选择模块 → 解析 XML → 检测死条目 → AI 翻译

### `locale-tui/README.md`

Android 语言文件翻译管理 TUI 工具说明文档
- `功能`：模块选择、翻译表、AI翻译、Dead entry检测、搜索过滤、编辑删除
- `运行`：`uv run python src/main.py`
- `配置`：`config.yml` 和 `.env`
- `快捷键`：Enter编辑、t AI翻译、d Dead过滤、m Missing过滤、s保存、q退出

### `locale-tui/config.yml`

locale-tui 多模块多语言翻译工具配置
- `project_root`: 项目根路径
- `modules`: app/ai/highlight/search/rag 模块资源与源码路径
- `languages`: 英语/简中/繁中/日/韩/俄语
- `translation`: AI 翻译模型、批大小、提示模板
- `display`: 列宽、分页大小

### `locale-tui/pyproject.toml`

定义项目元数据、依赖及入口脚本的配置文件。
- `project.name`：locale-tui
- `project.scripts`：`locale-tui` 入口 (`src.main:main`)
- `dependencies`：Click, lxml, OpenAI, dotenv, PyYAML, Textual
- `dependency-groups.dev`：pytest, pytest-asyncio
- `tool.pytest.ini_options`：asyncio_mode=auto

### `locale-tui/src/__init__.py`

locale-tui 包初始化文件，无关键符号。

### `locale-tui/src/app.py`

Textual 主应用，管理 Android 区域翻译界面。
- `LocaleTuiApp`：Textual 应用
- `BINDINGS`：q 退出，? 帮助
- `on_mount`：启动时推送模块选择屏
- `action_help`：显示快捷键帮助

### `locale-tui/src/config.py`

定义配置数据类，从YAML文件加载应用配置。
- `LanguageConfig`：语言配置
- `ModuleConfig`：模块配置
- `Config`：应用配置，含加载及查询方法
- `Config.load()`：从YAML和.env构建配置

### `locale-tui/src/main.py`

Android 语言资源管理 TUI/CLI 入口，提供配置加载、条目增改及翻译。
- `load_config`：加载并验证 config.yml
- `cli`：Click 命令组，无子命令时启动 TUI
- `test-connection`：测试 AI 翻译服务连接
- `add`：添加新键并自动翻译到目标语言
- `set`：手动设置指定语言条目值
- `list-keys`：列出源语言所有条目键

> 符号导航：[files/locale-tui/src/main.py.md](../files/locale-tui/src/main.py.md)

### `locale-tui/src/models/__init__.py`

模型包初始化，导出 `TranslationEntry` 类。
- `TranslationEntry`：翻译条目模型

### `locale-tui/src/models/entry.py`

```markdown
定义翻译条目数据模型，封装键、翻译字典及死条目标记。
- `TranslationEntry`：翻译条目数据类
- `get_translation`：获取指定语言翻译
- `set_translation`：设置翻译值
- `has_missing_translations`：检查缺失翻译
- `get_missing_languages`：返回缺失语言列表
```

### `locale-tui/src/screens/__init__.py`

包的初始化，导出两个屏幕类。
- `ModuleSelectScreen`：模块选择界面
- `TranslationTableScreen`：翻译表格界面

### `locale-tui/src/screens/module_select.py`

模块选择屏幕，展示模块列表供用户选择并跳转到翻译表格。
- `ModuleSelectScreen`：模块选择界面，包含列表和导航逻辑。
- `__init__(config)`：接收配置，存储模块数据。
- `compose`：构建UI，包含Header、列表、Footer。
- `on_list_view_selected`：处理选择，跳转到翻译表格屏幕。
- `action_quit`：退出应用。

### `locale-tui/src/screens/translation_table.py`

翻译表屏幕，管理多语言翻译条目的显示、编辑、过滤与保存。
- `TranslationTableScreen`：主屏幕，提供表格、搜索、过滤、翻译、保存功能。
- `load_entries`：加载所有语言翻译条目。
- `apply_filters`：应用搜索与死词/缺失过滤。
- `BINDINGS`：定义快捷键（t翻译、d死词过滤、m缺失过滤、s保存等）。

> 符号导航：[files/locale-tui/src/screens/translation_table.py.md](../files/locale-tui/src/screens/translation_table.py.md)

### `locale-tui/src/services/__init__.py`

服务模块聚合导出，统一暴露核心API。
- `StringsXmlParser`：XML字符串解析
- `AITranslator`：AI翻译执行
- `TranslationError`：翻译异常
- `DeadEntryFinder`：死条目检测

### `locale-tui/src/services/dead_entry_finder.py`

检测未引用翻译条目的服务。
- `DeadEntryFinder`：死条目查找器
- `PATTERNS`：匹配引用模式的正则列表
- `RESERVED_KEYS`：保留键，永不被标记为死条目
- `find_referenced_keys`：扫描源码找出所有引用键
- `_extract_keys_from_file`：从单个文件提取键
- `mark_dead_entries`：标记无引用条目并返回计数

### `locale-tui/src/services/translator.py`

AI翻译服务，基于OpenAI SDK批量翻译缺失条目。
- `TranslationError`：翻译异常
- `AITranslator`：翻译器，含`test_connection`、`translate_batch`、`translate_all_missing`

### `locale-tui/src/services/xml_parser.py`

解析与操作 Android strings.xml 资源文件。  
- `StringsXmlParser`：静态工具类  
- `parse`：解析为 {name: value}  
- `write`：写入完整文件  
- `update_entry`：更新/新增单条  
- `delete_entry`：删除单条

### `locale-tui/src/styles/app.tcss`

Textual TUI 全局及组件样式表。
- `Screen`：全局背景色
- `#status-bar`：状态栏
- `#search`：搜索框
- `#progress`：进度条
- `#table`／`DataTable`：翻译表格与光标/表头
- `#module-list`：模块选择列表
- `#edit-modal`：编辑弹窗
- `#button-row`：按钮行

### `locale-tui/src/widgets/__init__.py`

Widgets 包入口，统一导出编辑模态框组件。
- `EditModal`：编辑模态框组件类

### `locale-tui/src/widgets/edit_modal.py`

翻译条目编辑模态框，支持多语言输入保存。
- `EditModal`：翻译编辑模态屏幕
- `BINDINGS`：Esc 取消，Ctrl+S 保存
- `action_save`：保存翻译并关闭
- `action_cancel`：取消编辑

### `locale-tui/tests/test_translator.py`

集成测试，验证 AI 翻译质量与关键约束。
- `translator` fixture：初始化 AITranslator
- `test_prompt_translates_to_tishici`：prompt 应译为“提示词”
- `test_placeholder_preserved`：占位符保持不变
- `test_brand_name_not_translated`：品牌名不翻译
- `test_token_not_over_translated`：Token 保留
- `test_keys_preserved_in_response`：响应键一一对应
- `test_non_empty_translations`：翻译结果非空
