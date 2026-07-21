# 知行知识空间 v0.1

状态：v0.1 已实现，本文继续作为目录、检索、引用与工具边界契约（2026-07-21 核对）

## 1. 产品边界

知识空间复用现有 Workspace，不新增一套孤立的“知识库数据库”。一个 Workspace 可以被初始化为项目知识空间，并继续拥有普通文件区与可选 RootFS/Shell：

```text
项目知识空间
  = 持久文件目录
  + 标准项目结构
  + 文档归一与本地检索
  + 专用 AI 工具
  + 可选 Shell
```

v0.1 的目标是完成“导入资料 -> 本地检索 -> 精确读取 -> 带来源回答”的真实闭环。向量数据库、云端索引、跨空间自动路由和多人协作不在本阶段范围内。

## 2. 目录契约

初始化必须幂等，已有文件不得被覆盖。标准结构如下：

```text
/PROJECT.md                         项目目标、约束和工作方式
/knowledge/sources/                 用户导入的原始资料，事实来源
/knowledge/notes/                   人工或 AI 整理的长期笔记
/knowledge/decisions/               重要决策与理由
/knowledge/outputs/                 可交付成果
/knowledge/drafts/                  临时草稿
/.zhixing/knowledge-space.json      格式版本和空间标识
/.zhixing/knowledge/normalized/     从文档提取的可检索 Markdown
/.zhixing/knowledge/metadata/       原文与归一文件的来源映射
```

`knowledge/sources`、`PROJECT.md`、notes、decisions 和 outputs 是用户数据；备份不得遗漏。`.zhixing/knowledge/normalized` 与 metadata 是本地派生数据，可从原文重建，不能成为唯一事实来源。

## 3. 导入与归一

- UI 的“导入知识”把原文件复制到 `knowledge/sources/`，同名冲突使用安全后缀，不覆盖旧文件。
- PDF、DOCX、PPTX、EPUB 转换为 Markdown；UTF-8 文本、Markdown、代码和常见结构化文本直接归一。
- 每个归一文件必须保留原始相对路径，检索结果以原文路径为来源；解析失败时原文仍保留，并返回可见错误。
- 单次导入不修改用户原文件，不执行其中脚本，不通过网络上传内容。

## 4. 检索与引用

v0.1 使用文件级、逐行、大小写不敏感的本地全文检索，覆盖 `PROJECT.md`、notes、decisions、outputs、drafts 和 normalized。原始二进制文件不直接扫描。

每条命中至少返回：

- `path`：可读取的归一文件或项目文件；
- `sourcePath`：用户原始资料路径；
- `line` 与 `excerpt`：命中行和上下文；
- `citation`：稳定的 `workspace://<path>#L<line>` 引用。

AI 在基于知识空间作答前应先搜索，再精确读取相关片段；没有命中时必须明确说明，不得把模型常识伪装成项目事实。

## 5. AI 工具契约

- `knowledge_status`：只读，无需 RootFS，报告是否初始化、资料数和可检索文档数。
- `knowledge_search`：只读，无需 RootFS，返回有限数量的匹配与来源。
- `knowledge_read`：只读，无需 RootFS，按行读取知识文件并返回引用。
- `knowledge_ingest`：把 `/upload` 中的指定文件导入知识空间；属于持久写入，默认需要用户批准。

普通 `workspace_*` 工具继续服务于项目执行和文件编辑；`workspace_shell` 只有 RootFS 就绪时才可用。知识检索不得因为 Shell 未安装而失效。

## 6. v0.1 验收

1. 初始化空 Workspace 后标准文件存在；重复初始化不会覆盖用户修改过的 `PROJECT.md`。
2. 导入受支持文档后，原文和归一文本同时存在，来源映射可读。
3. 中文查询可以命中归一文本，结果包含行号、原文路径和 citation。
4. 助手只绑定 Workspace、未安装 RootFS 时，仍可调用 status/search/read。
5. 导入工具需要批准；只读工具默认无需批准。
6. 路径逃逸、读取非知识路径和超限读取被拒绝。
