---
source_commit: 4e68436185cb505db643c54224ab82d2f2745844
generated: 2026-07-21
---


# 模块：web-ui

**聊天 AI 对话前端界面**  
模块以 React Router 7 SPA 组织，`routes/` 定义对话页面与首页路由，`components/` 按消息、输入、侧边栏、工作台等拆分 UI。核心数据流：用户输入通过 `ChatInput` 组件提交，经 `ky` 客户端调用后端 REST/SSE 接口，流式消息更新由 `useConversationDetail` 驱动，状态通过 Zustand 切片（设置、草稿、时钟）管理。对外接口即路由页面（`/` 与 `/c/:id`），构建产物复制到 Ktor 静态资源目录。依赖后端 API 代理（`/api` → `localhost:8080`）及 SSE 事件总线。  
**修改指引**：修改对话交互逻辑从 `routes/conversations.tsx` 入手；新增 UI 组件在 `components/` 对应目录；调整全局状态在 `stores/slices/` 中修改相应切片。

## 文件摘要

### `web-ui/.vscode/settings.json`

VSCode 工作区设置：配置 i18n 路径和 TS/TSX 格式化
- `i18n-ally.localesPaths`：国际化文件路径 `app/locales`
- `[typescriptreact]`、`[typescript]`：oxc 格式化，保存时格式化

### `web-ui/AGENTS.md`

定义 AI 代理的配置文件引用，指向 `CLAUDE.md`。

### `web-ui/CLAUDE.md`

AI代理指导文件，定义项目架构、命令与开发规范。
- `Commands`：dev / build / typecheck
- `Tech Stack`：React Router 7, Vite, Tailwind, Zustand, ky, i18next
- `Structure`：routes, components, stores, types, services
- `Type Mapping`：TypeScript ↔ Kotlin 严格对齐
- `State`：Zustand 组合 slices
- `Routing`：文件路由 SPA
- `i18n`：命名空间 zh-CN / en-US
- `API`：ky REST + SSE 流
- `Build`：构建 → 复制到 Ktor 静态资源

### `web-ui/README.md`

React Router 全栈模板说明文档。

- 特性：SSR、HMR、TypeScript、TailwindCSS
- 开发：`npm install` / `npm run dev`（:5173）
- 构建：`npm run build`
- 部署：Docker 或 diy（`build/` 输出）

### `web-ui/app/app.css`

定义全局主题CSS变量、多主题（claude/t3-chat/mono/bubblegum）及暗黑模式，配置Tailwind主题与滚动条。
- `:root`：默认亮色主题变量
- `.dark`：暗黑模式变量
- `[data-theme="claude"]`等：各主题亮暗变量
- `@theme inline`：映射CSS变量到Tailwind颜色/字体/阴影/圆角
- `@layer base`：全局边框、背景样式与滚动条

### `web-ui/app/components/conversation-quick-jump.tsx`

对话快速跳转侧边导航组件，根据滚动位置高亮当前消息。
- `getConversationMessageAnchorId`：生成消息锚点 ID
- `ConversationQuickJumpItem`：跳转项数据结构
- `getRoleLineClass` / `getRoleDotClass` / `getRoleLabel`：角色样式与标签
- `ConversationQuickJump`：渲染可点击导航条，支持滚动同步与 Tooltip 预览

### `web-ui/app/components/conversation-search-button.tsx`

对话搜索按钮组件，弹窗内输入关键词搜索消息并跳转。
- `ConversationSearchButtonProps`：onSelect 回调接口
- `ConversationSearchButton`：导出组件，处理搜索与结果展示
- `SnippetText`：内部函数，渲染带高亮的搜索片段
- `formatRelativeTime`：内部函数，返回相对时间文本

### `web-ui/app/components/conversation-sidebar.tsx`

会话侧边栏，展示会话列表、文件夹、主题语言切换与用户信息。

- `ConversationSidebar`：主侧边栏组件，管理会话列表、助理切换、主题/语言设置。
- `ConversationSidebarProps`：定义侧边栏所有回调与数据属性。
- `ConversationListRow`：单条会话行，含置顶、重命名、移动、删除等操作。
- `FolderBar`：文件夹筛选与增删改查。
- `LanguageSwitcher`：中英文切换控件。
- `groupConversations`：按日期与置顶分组会话。

### `web-ui/app/components/custom-theme-dialog.tsx`

自定义主题 CSS 编辑弹窗，支持合并亮/暗模式并保存。
- `CUSTOM_THEME_EDITOR_ROWS`：编辑器行数
- `mergeThemeCss`：合并 light/dark CSS
- `CustomThemeDialogProps`：对话框属性类型
- `CustomThemeDialog`：导出组件，提供编辑与保存功能

### `web-ui/app/components/extended/conversation.tsx`

提供可吸附底部的对话容器组件及下载功能
- `Conversation`：吸附底部对话容器
- `ConversationContent`：内容区
- `ConversationEmptyState`：空状态占位
- `ConversationScrollButton`：滚动到底部按钮
- `ConversationDownload`：导出对话为.md
- `messagesToMarkdown`：消息转Markdown
- `ConversationMessage`：消息类型

### `web-ui/app/components/extended/infinite-scroll-area.tsx`

可自补齐的无限滚动区域组件  
- `InfiniteScrollAreaProps`：组件属性接口  
- `InfiniteScrollArea`：组件，内容不足时自动加载下一页

### `web-ui/app/components/input/chat-input.tsx`

聊天输入组件，支持文本输入、附件上传/删除、拖拽、快速消息和发送。
- `ChatInput`：主聊天输入组件（memo）
- `ChatInputProps`：组件属性接口
- `detectUploadFile`：Magic bytes 检测文件类型
- `toMessagePart`：上传响应→UIMessagePart
- `QuickMessageButton`：快速消息快捷按钮
- `IMAGE_UPLOAD_ACCEPT`：`"image/*"`

### `web-ui/app/components/input/extension-picker.tsx`

此文件定义一个扩展选择器按钮组件，用于切换模式注入、知识库与快速消息。  
- `ExtensionPickerButton`：主组件，处理选择状态与提交。  
- `ExtensionPickerButtonProps`：组件属性接口。  
- `getModeInjections` / `getLorebooks` / `getQuickMessages`：从设置中提取并验证对应数组。

### `web-ui/app/components/input/mcp-picker.tsx`

MCP 工具选择按钮组件，管理助手的 MCP 服务器启停。
- `McpPickerButtonProps`：按钮组件属性接口
- `McpPickerButton`：主组件，切换助手选用的 MCP 服务器

### `web-ui/app/components/input/model-list.tsx`

模型选择弹窗组件，支持按提供商筛选、搜索、收藏模型。
- `ModelList`：导出组件，含搜索、收藏、切换模型功能。
- `ModelListProps`：接口，定义 disabled、className、onChanged 属性。
- `ModelOptionRow`：内部组件，渲染单个模型行，支持选择/收藏。
- `normalizeKeyword`：标准化搜索关键词（小写去空格）。
- `formatModality`：格式化模型输入输出模态。
- `getAbilityLabel`：获取能力标签翻译文本。
- `FAVORITE_SECTION_ID`：收藏分组标识常量。
- `ModelSection`：接口，描述提供商分组结构。

### `web-ui/app/components/input/picker-error-alert.tsx`

显示选择器错误提示的警告组件。
- `PickerErrorAlert`：接收`error`字符串或null，为空时隐藏，否则渲染带样式的错误消息。

### `web-ui/app/components/input/reasoning-picker.tsx`

推理强度选择器弹窗组件，含滑块与预设切换。
- `ReasoningPickerButton`：导出组件，渲染推理级别选择按钮与弹窗
- `ReasoningSlider`：滑块控件，切换推理等级
- `ReasoningIcon`：根据等级渲染对应图标
- `REASONING_LEVELS`：推理等级常量数组
- `isReasoningModel`：判断模型是否支持推理

### `web-ui/app/components/input/search-picker.tsx`

搜索服务选择器按钮，支持内置搜索、网页搜索开关及服务切换。
- `SearchPickerButton`：搜索开关与选择器组件
- `SearchPickerButtonProps`：组件属性类型
- `SEARCH_SERVICE_LABELS`：搜索服务显示名映射
- `getServiceLabel`：获取服务标签
- `hasBuiltInSearch`：检测模型内置搜索
- `isGeminiModel`：判断 Gemini 模型
- `getToolType`：提取工具类型字符串

### `web-ui/app/components/logo.tsx`

SVG 图标组件，可复用并接受 SVG 属性。
- `Logo`：默认导出组件，渲染品牌 Logo 矢量图。

### `web-ui/app/components/markdown/code-block.tsx`

渲染 Markdown 代码块，含语法高亮、复制、下载、预览。
- `CodeBlock`：主组件
- `highlightCode`：核心高亮函数
- `CodeBlockCopyButton` / `CodeBlockDownloadButton` / `CodeBlockPreviewButton`：操作按钮
- `MAX_SHIKI_CODE_LENGTH`=12000

### `web-ui/app/components/markdown/markdown.css`

Markdown 内容展示样式表（shadcn 风格）。
- `.markdown`：根容器样式，定义各级标题、列表、代码块等子元素
- `.code-block`：代码块包装器，含头部、语言标签、复制按钮
- `.inline-code`：内联代码样式（字体、背景）
- `.citation-badge`：引用徽章样式

### `web-ui/app/components/markdown/markdown.tsx`

渲染 Markdown 内容，支持代码块高亮、LaTeX 公式与引用徽章。  
- `preProcess`：转换 \(...\) / \[...\] 为 $...$ / $$...$$，跳过代码块  
- `Markdown`：默认导出组件，集成 Streamdown、代码预览、引用点击  
- `MarkdownProps`：属性类型定义  
- `getNodeText`：提取 React 节点文本

### `web-ui/app/components/message/chain-of-thought.tsx`

可折叠思维链步骤展示组件，支持受控/非受控模式。
- `ChainOfThought`：渲染步骤列表，支持折叠/展开。
- `ChainOfThoughtStep`：非受控单步，可展开子内容。
- `ControlledChainOfThoughtStep`：受控单步，外控展开状态。
- 相关Props类型：`ChainOfThoughtProps`、`ChainOfThoughtStepProps`、`ControlledChainOfThoughtStepProps`。

### `web-ui/app/components/message/chat-message-annotations.tsx`

渲染聊天消息的URL引用注释行。
- `ChatMessageAnnotationsRow`：显示引用链接列表，支持左右对齐。
- `getCitationLabel`：提取引用标题或域名作为显示标签。

### `web-ui/app/components/message/chat-message-avatar-row.tsx`

聊天消息头像行，按角色显示头像/名称/时间戳
- `ChatMessageAvatarRowProps`：组件属性接口
- `formatMessageTimestamp`：格式化时间戳
- `ChatMessageAvatarRow`：主组件，根据角色与显示设置渲染头像行

### `web-ui/app/components/message/chat-message.tsx`

React 聊天消息组件，展示内容、操作按钮、分支切换与 token 统计。
- `ChatMessage`：主组件，渲染消息气泡与操作
- `ChatMessageActionsRow`：操作按钮行（复制、编辑、重新生成、分支切换、删除、导出）
- `ChatMessageNerdLineRow`：token 使用统计行
- `ChatMessageProps`：组件属性接口
- `buildCitationUrlMap`：从工具输出构建引用 URL 映射
- `getNerdStats`：计算 token 统计信息
- `hasRenderablePart`：判断消息部分是否可渲染

### `web-ui/app/components/message/index.ts`

消息组件统一导出入口。
- `MessagePart`/`MessageParts`：消息部分基础组件
- `ChatMessageAnnotationsRow`：消息注解行
- `TextPart`：文本片段
- `ReasoningPart`：推理片段
- `ReasoningStepPart`：推理步骤
- `ImagePart`：图片片段
- `VideoPart`：视频片段
- `AudioPart`：音频片段
- `DocumentPart`：文档片段
- `ToolPart`：工具片段

### `web-ui/app/components/message/message-part.tsx`

消息组件将消息部分分组并渲染为思维链与内容块。
- `groupMessageParts`：将消息部分分组为思维块和内容块。
- `MessageParts`：渲染消息部分列表，含思维链和多类型内容。
- `MessagePart`：渲染单个消息部分的包装组件。
- `ThinkingStep`：思维步骤联合类型（reasoning/tool）。
- `MessagePartBlock`：消息块联合类型（thinking/content）。

### `web-ui/app/components/message/parts/audio-part.tsx`

渲染音频消息部件，含播放器与错误提示。
- `AudioPart`：接收 `url`，渲染音频播放器，失败时显示错误信息。

### `web-ui/app/components/message/parts/document-part.tsx`

渲染消息中的文档附件，显示可点击的文件名和图标。
- `DocumentPart`：文档链接组件，接收url、fileName、mime
- `DocumentPartProps`：组件属性接口
- `getDocumentIcon`：根据mime返回对应图标

### `web-ui/app/components/message/parts/image-part.tsx`

渲染图片消息，处理加载、失败、空状态。  
- `ImagePart`：图片组件，通过 `resolveFileUrl` 解析地址，加载失败显示错误提示，加载中显示占位符。

### `web-ui/app/components/message/parts/reasoning-part.tsx`

显示AI推理过程的可折叠面板组件。
- `ReasoningPart`：接收`reasoning`和`isFinished`，控制展开/折叠渲染Markdown。

### `web-ui/app/components/message/parts/reasoning-step-part.tsx`

渲染推理步骤卡片，支持折叠/预览/展开状态与时长显示。
- `ReasoningStepPart`：推理步骤卡片组件
- `ReasoningCardState`：卡片状态枚举
- `formatDuration`：计算耗时（秒）
- `ReasoningStepPartProps`：组件属性接口

### `web-ui/app/components/message/parts/text-part.tsx`

渲染消息文本片段，委托Markdown组件处理内容。
- `TextPart`：消息文本组件，接收text、isAnimating、onClickCitation。

### `web-ui/app/components/message/parts/tool-part.tsx`

- 渲染聊天消息中的工具调用部件（搜索、记忆、剪贴板、询问用户等），支持审批和结果展示。
- `ToolPart`：工具调用主组件，根据工具名路由到不同渲染分支。
- `AskUserToolStep`：处理“询问用户”工具的交互式表单。
- `SearchWebPreview` / `ScrapeWebPreview`：搜索和网页抓取结果的预览子组件。
- 常量 `TOOL_NAMES` / `MEMORY_ACTIONS` / `CLIPBOARD_ACTIONS`：工具名与动作枚举。
- 辅助函数 `safeJsonParse`、`toJsonString`、`getStringField`、`getArrayField`、`parseAskUserQuestions`。

### `web-ui/app/components/message/parts/video-part.tsx`

视频消息部件，内嵌视频播放器与错误处理。
- `VideoPart`：接收`url`，渲染视频；加载失败时展示错误提示。

### `web-ui/app/components/theme-provider.tsx`

主题提供器，管理亮/暗/系统模式、颜色主题和自定义CSS。
- `ThemeProvider`：提供主题上下文及持久化存储的组件
- `useTheme`：获取主题状态的 hook
- `ThemeMode`：主题模式类型（dark/light/system）
- `ColorTheme`：颜色方案类型（default/claude/…）
- `COLOR_THEMES`：预设颜色方案数组

### `web-ui/app/components/ui/ai-icon.tsx`

显示 AI 图标组件，支持图片加载与首字母回退。
- `AIIcon`：渲染圆形图标，加载失败显示首字母
- `AIIconProps`：name、size、loading、className、imageClassName
- `toFallbackText`：提取首字母作为回退文本

### `web-ui/app/components/ui/alert.tsx`

Alert 组件，提供警示框、标题和描述子组件。
- `Alert`：警示容器，支持 default/destructive 变体
- `AlertTitle`：警示标题
- `AlertDescription`：警示描述文本

### `web-ui/app/components/ui/avatar.tsx`

基于 Radix UI 的头像组件，支持尺寸变体、徽章及分组。
- `Avatar`：根容器，接收 size 属性（sm/default/lg）
- `AvatarImage`：头像图片
- `AvatarFallback`：头像占位
- `AvatarBadge`：角标徽章
- `AvatarGroup`：头像分组容器
- `AvatarGroupCount`：分组余数显示

### `web-ui/app/components/ui/badge.tsx`

Badge 标签组件，支持多种样式变体和 asChild 插槽渲染。
- `Badge`：渲染 span 或插槽元素，支持 variant 和 asChild 属性。
- `badgeVariants`：cva 样式配置，定义 default/secondary/destructive/outline/ghost/link 变体。

### `web-ui/app/components/ui/button-group.tsx`

按钮组组件：水平/垂直排列，带分隔符与文本插槽。
- `ButtonGroup`：容器，支持方向变体。
- `ButtonGroupText`：组内文本展示。
- `ButtonGroupSeparator`：分隔线。
- `buttonGroupVariants`：样式变体配置。

### `web-ui/app/components/ui/button.tsx`

基于 cva 的可变体按钮组件，支持 asChild 插槽渲染。
- `Button`：渲染按钮，接受 variant、size、asChild 属性。
- `buttonVariants`：cva 配置，定义样式变体与尺寸。

### `web-ui/app/components/ui/card.tsx`

卡片 UI 组件集合，提供标题、内容、操作等子组件。  
- `Card`：卡片容器  
- `CardHeader`：卡片头部  
- `CardTitle`：卡片标题  
- `CardDescription`：卡片描述  
- `CardAction`：头部操作区  
- `CardContent`：卡片内容  
- `CardFooter`：卡片底部

### `web-ui/app/components/ui/checkbox.tsx`

基于 Radix UI 的受控复选框组件，封装样式与指示图标。  
- `Checkbox`：导出复选框 UI 组件，接受 `className` 等 Radix `Root` 属性。

### `web-ui/app/components/ui/context-menu.tsx`

封装 Radix UI 右键菜单的样式化组件集。
- `ContextMenu`：根
- `ContextMenuTrigger`：触发器
- `ContextMenuContent`：内容
- `ContextMenuItem`：项
- `ContextMenuCheckboxItem`：复选框项
- `ContextMenuRadioItem`：单选项
- `ContextMenuLabel`：标签
- `ContextMenuSeparator`：分隔符
- `ContextMenuShortcut`：快捷键
- `ContextMenuGroup`：分组
- `ContextMenuPortal`：传送门
- `ContextMenuSub`：子菜单
- `ContextMenuSubContent`：子内容
- `ContextMenuSubTrigger`：子触发器
- `ContextMenuRadioGroup`：单选组

### `web-ui/app/components/ui/dialog.tsx`

基于 Radix UI 的 Dialog 组件封装，提供可组合的子组件和统一样式。

- `Dialog`：对话框根组件
- `DialogTrigger`：触发按钮
- `DialogPortal`：挂载到 body 的容器
- `DialogClose`：关闭按钮
- `DialogOverlay`：遮罩层
- `DialogContent`：对话框内容，含可选关闭按钮
- `DialogHeader`：标题区域
- `DialogFooter`：底部操作区，可显关闭按钮
- `DialogTitle`：标题
- `DialogDescription`：描述文本

### `web-ui/app/components/ui/drawer.tsx`

基于 `vaul` 的 Drawer 组件集合，提供多方向抽屉 UI。
- `Drawer`：抽屉根组件
- `DrawerTrigger`：打开触发器
- `DrawerPortal`：传送门容器
- `DrawerClose`：关闭按钮
- `DrawerOverlay`：遮罩层
- `DrawerContent`：内容面板，支持上下左右方向
- `DrawerHeader`：头部布局
- `DrawerFooter`：底部布局
- `DrawerTitle`：标题
- `DrawerDescription`：描述

### `web-ui/app/components/ui/dropdown-menu.tsx`

封装 Radix UI DropdownMenu 为可组合 UI 组件。
- `DropdownMenu`：根容器
- `DropdownMenuTrigger`：触发按钮
- `DropdownMenuContent`：弹出内容
- `DropdownMenuItem`：菜单项，支持 variant/destructive
- `DropdownMenuCheckboxItem`：勾选项
- `DropdownMenuRadioItem`：单选项
- `DropdownMenuSeparator`：分割线
- `DropdownMenuSub`/`SubTrigger`/`SubContent`：子菜单

### `web-ui/app/components/ui/empty.tsx`

提供空状态占位 UI 组件集合。
- `Empty`：根容器
- `EmptyHeader`：头部区域
- `EmptyMedia`：图标/媒体，支持变体
- `EmptyTitle`：标题
- `EmptyDescription`：描述文本
- `EmptyContent`：内容区

### `web-ui/app/components/ui/input-group.tsx`

提供可组合的输入组 UI 组件，支持内联/块级对齐与附加元素。
- `InputGroup`：输入组容器，统一样式与焦点/错误状态。
- `InputGroupAddon`：附加元素，可放图标/按钮，支持四种对齐。
- `InputGroupButton`：输入组内按钮，大小变体。
- `InputGroupText`：文本标签或图标容器。
- `InputGroupInput`：无边框自适应输入框。
- `InputGroupTextarea`：无边框自适应文本域。

### `web-ui/app/components/ui/input.tsx`

一个带统一样式的 `<input>` 封装组件。

- `Input`：返回添加了 Tailwind 样式、数据属性及焦点/无效状态样式的 `<input>` 元素。

### `web-ui/app/components/ui/item.tsx`

定义可组合列表项子组件（Item/ItemMedia/ItemContent等）及布局容器。
- `Item`：核心列表项容器，支持variant/size/asChild
- `ItemGroup`：列表容器，渲染为`role="list"`的div
- `ItemSeparator`：水平分隔线
- `ItemMedia`：缩略图/图标容器，支持icon/image变体
- `ItemContent`：主内容弹性容器
- `ItemTitle`：标题
- `ItemDescription`：描述文本
- `ItemActions`：操作区
- `ItemHeader`：全宽头部
- `ItemFooter`：全宽底部

### `web-ui/app/components/ui/popover.tsx`

基于 Radix UI 封装 Popover 组件集，含动画与样式。
- `Popover`：根组件
- `PopoverTrigger`：触发器
- `PopoverContent`：弹出内容，带动画和定位
- `PopoverAnchor`：锚点
- `PopoverHeader`：头部容器
- `PopoverTitle`：标题
- `PopoverDescription`：描述

### `web-ui/app/components/ui/progress.tsx`

基于 Radix UI 的进度条组件，展示进度并支持样式定制。
- `Progress`：进度条组件，接受 `value` 和 `className` 属性。

### `web-ui/app/components/ui/resizable.tsx`

封装可调整大小面板组、面板和分隔条组件。
- `ResizablePanelGroup`：可调整面板组
- `ResizablePanel`：面板
- `ResizableHandle`：分隔条，可选拖拽手柄

### `web-ui/app/components/ui/scroll-area.tsx`

封装 Radix UI ScrollArea，提供带样式的滚动区域与滚动条。  
- `ScrollArea`：包含 viewport、corner、ScrollBar 的根容器。  
- `ScrollBar`：带垂直/水平样式、thumb 的滚动条。  
- 导出：`ScrollArea`, `ScrollBar`

### `web-ui/app/components/ui/select.tsx`

Radix UI Select 封装，提供样式化下拉选择组件。
- `Select`：选择根组件
- `SelectTrigger`：触发按钮
- `SelectValue`：当前值显示
- `SelectContent`：弹出内容
- `SelectGroup`：选项分组
- `SelectLabel`：分组标签
- `SelectItem`：选择项
- `SelectSeparator`：分隔线
- `SelectScrollUpButton`：上滚按钮
- `SelectScrollDownButton`：下滚按钮

### `web-ui/app/components/ui/separator.tsx`

渲染水平或垂直分隔线。
- `Separator`：基于 Radix UI 的分隔线组件，支持水平和垂直方向。

### `web-ui/app/components/ui/sheet.tsx`

基于 Radix Dialog 的 Sheet 侧边栏组件封装。
- `Sheet`：根容器
- `SheetTrigger`：打开触发器
- `SheetClose`：关闭按钮
- `SheetContent`：面板内容（含遮罩、关闭按钮、侧边动画）
- `SheetHeader`：头部区域
- `SheetFooter`：底部区域
- `SheetTitle`：标题
- `SheetDescription`：描述

### `web-ui/app/components/ui/sidebar.tsx`

可折叠侧边栏组件集，支持移动端适配与键盘快捷键。
- `SidebarProvider`：上下文与状态管理
- `Sidebar`：侧边栏容器
- `SidebarTrigger`：切换按钮
- `SidebarMenuButton`：菜单项按钮
- `useSidebar`：访问上下文 hook
- 常量：`SIDEBAR_WIDTH` 等定义尺寸

### `web-ui/app/components/ui/skeleton.tsx`

骨架屏占位组件，带脉冲动画和圆角。
- `Skeleton`：骨架屏占位组件，渲染脉冲动画圆角div。

### `web-ui/app/components/ui/slider.tsx`

封装 Radix UI 滑块组件的样式化 Slider 组件。
- `Slider`：可拖拽滑块，支持单/多拇指，水平和垂直方向，默认 min=0 max=100。

### `web-ui/app/components/ui/sonner.tsx`

自定义 Sonner 通知组件，集成主题与图标。
- `Toaster`：包装 sonner 的 Toaster，应用主题、自定义图标和样式变量。

### `web-ui/app/components/ui/spinner.tsx`

加载动画组件，基于 lucide-react 图标并默认旋转。
- `Spinner`：渲染旋转加载图标，支持 `className` 等 SVG 属性。

### `web-ui/app/components/ui/switch.tsx`

基于Radix UI的开关组件，支持sm/default两种尺寸。
- `Switch`：可切换开关组件，接受size属性控制大小。

### `web-ui/app/components/ui/textarea.tsx`

多行文本输入框 UI 组件，封装统一样式与交互。
- `Textarea`：导出 React 函数组件，渲染带预设样式的 `<textarea>`，支持原生属性与 `className` 合并。

### `web-ui/app/components/ui/toggle-group.tsx`

基于 Radix UI 的切换按钮组，支持尺寸/变体/间距配置。

- `ToggleGroup`：切换组容器，传递变体、尺寸与间距上下文
- `ToggleGroupItem`：切换项，自动继承组合样式

### `web-ui/app/components/ui/toggle.tsx`

基于Radix UI的Toggle组件，支持 variant 和 size 变体。
- `Toggle`：切换按钮组件
- `toggleVariants`：样式变体配置

### `web-ui/app/components/ui/tooltip.tsx`

基于 Radix UI 封装 Tooltip 组件及样式。
- `TooltipProvider`：提供全局延迟等配置
- `Tooltip`：Tooltip 根组件
- `TooltipTrigger`：触发元素
- `TooltipContent`：弹出内容，含箭头与动画

### `web-ui/app/components/ui/typing-indicator.tsx`

显示打字中的动画指示器
- `TypingIndicator`：渲染三个跳动圆点，表示对方正在输入

### `web-ui/app/components/ui/ui-avatar.tsx`

头像组件，处理fallback文本和图片URL，支持强制重渲染。
- `UIAvatar`：封装头像组件
- `UIAvatarProps`：定义组件属性（name, avatar, size, className）

### `web-ui/app/components/web-auth-gate.tsx`

Web 密码验证门禁组件，监听事件弹出密码输入框，验证后刷新页面。
- `WebAuthGate`：Web 密码验证门禁组件，弹出密码输入框，验证通过后刷新页面。

### `web-ui/app/components/workbench/code-preview-language.ts`

管理代码预览语言别名映射和规范化语言获取逻辑。
- `CODE_PREVIEW_LANGUAGE_ALIASES`：原始语言到标准语言的映射字典
- `SUPPORTED_CODE_PREVIEW_LANGUAGES`：所有支持的语言键集合
- `getCodePreviewLanguage`：输入语言字符串，返回规范化标准语言名，否则 null

### `web-ui/app/components/workbench/workbench-context.tsx`

为工作台面板提供上下文状态管理。
- `WorkbenchPanel`：面板类型定义
- `WorkbenchContextValue`：上下文值类型
- `useWorkbenchController`：管理面板开关状态
- `WorkbenchProvider`：上下文提供者
- `useWorkbench`：消费上下文（必填）
- `useOptionalWorkbench`：消费上下文（可选）

### `web-ui/app/components/workbench/workbench-host.tsx`

工作台面板宿主组件，根据类型渲染代码预览或回退展示。
- `WorkbenchHost`：渲染面板头部与关闭按钮，按类型分发渲染器
- `CodePreviewPanel`：代码预览面板，支持HTML/SVG/Mermaid/Markdown 预览/源码切换
- `UnknownPanel`：未知类型面板回退，展示JSON
- `PANEL_RENDERERS`：类型到渲染器的映射表
- `readStringField`：从payload安全提取字符串

### `web-ui/app/hooks/use-conversation-list.ts`

管理会话列表的 React Hook，支持分页、排序、实时更新。
- `UseConversationListOptions`：Hook 入参选项
- `UseConversationListResult`：Hook 返回值类型
- `useConversationList`：核心 Hook，获取并管理会话列表
- `toConversationSummaryUpdate`：将 `ConversationDto` 转为摘要更新对象

### `web-ui/app/hooks/use-current-assistant.ts`

React hook，从store获取当前助手配置。
- `useCurrentAssistant`：返回settings、assistants、currentAssistantId、currentAssistant
- `UseCurrentAssistantResult`：hook返回类型接口

### `web-ui/app/hooks/use-current-model.ts`

根据当前助手与设置解析当前模型及提供商。  
- `UseCurrentModelResult`：返回类型接口  
- `useCurrentModel`：获取当前模型ID、模型对象、提供商

### `web-ui/app/hooks/use-folders.ts`

管理当前助手的文件夹及会话移动操作。
- `useFolders`：提供文件夹列表、选中状态、loading 及增删改查/移动会话方法。
- `UseFoldersResult`：定义 hook 返回值的接口。

### `web-ui/app/hooks/use-mobile.ts`

检测视口宽度是否≤767px，判断移动端环境。  
- `useIsMobile`：返回布尔值，表示当前是否为移动端视口  
- `MOBILE_BREAKPOINT`：定义移动端断点宽度768px

### `web-ui/app/hooks/use-picker-popover.ts`

管理选择器弹出框的开关状态与错误信息。
- `usePickerPopover`：导出钩子，根据`canUse`权限控制弹出框开关并管理错误。

### `web-ui/app/i18n.ts`

初始化 i18next 国际化，支持中英文，从本地存储/浏览器语言获取初始语言。
- `i18n`（默认导出）：i18next 实例
- `SUPPORTED_LANGUAGES`：支持的语言常量 `["zh-CN", "en-US"]`
- `getInitialLanguage`：获取初始语言函数

### `web-ui/app/lib/clipboard.ts`

提供带降级策略的剪贴板复制功能。

- `copyTextToClipboard`：异步复制文本，优先使用 Clipboard API，失败时回退到 execCommand
- `copyViaExecCommand`：通过隐藏 textarea 和 execCommand 实现复制

### `web-ui/app/lib/display.ts`

提供显示名称的获取逻辑，含回退默认值。
- `getDisplayName`：通用显示名，空则回退
- `getAssistantDisplayName`：助手名，默认“默认助手”
- `getModelDisplayName`：模型名，优先展示名，否则模型ID，默认“未命名模型”

### `web-ui/app/lib/error.ts`

定义从未知错误中提取字符串消息的帮助函数。
- `extractErrorMessage`：从 `unknown` 错误中提取 `message` 或返回回退字符串。

### `web-ui/app/lib/export-markdown.ts`

将对话消息与对话导出为 Markdown 并提供下载功能。

- `convertMessageToMarkdown`: 单条消息转 Markdown
- `convertConversationToMarkdown`: 整个对话转 Markdown
- `downloadMarkdown`: 下载 Markdown 文件

### `web-ui/app/lib/files.ts`

将文件URL转换为对应的API端点，处理data/http/file协议及相对路径。
- `resolveFileUrl`：转换文件URL为API端点，data/http返回原值，file://提取路径转为/api/files/path/{path}，相对路径同理。

### `web-ui/app/lib/type-guards.ts`

类型守卫：将未知值安全转换为非空字符串数组。
- `safeStringArray`：过滤非字符串和空字符串，返回 `string[]`

### `web-ui/app/lib/utils.ts`

工具函数：类名合并、含时钟偏移的当前时间、提取加粗标题。
- `cn`：合并 Tailwind 类名
- `serverNow`：返回带时钟偏移的当前时间戳
- `extractThinkingTitle`：从文本中提取加粗标题

### `web-ui/app/locales/en-US/common.json`

英文国际化翻译文件，定义对话侧边栏、搜索、主题等UI文本。
- `conversation_sidebar`: 对话列表、管理操作、主题语言设置
- `conversation_search`: 搜索对话内容
- `quick_jump`: 消息跳转
- `workbench`: 代码预览面板
- `custom_theme_dialog`: 自定义CSS主题
- `infinite_scroll`: 无限滚动加载
- `web_auth_gate`: Web API密码解锁

### `web-ui/app/locales/en-US/input.json`

输入界面英文国际化文本（MCP/扩展/推理/模型/搜索/聊天）。
- `mcp`：MCP 服务器选择
- `injection`：扩展（快捷消息/模式注入/知识库）
- `reasoning`：推理强度与预算配置
- `model_list`：模型切换与收藏
- `search`：网页搜索与内置搜索
- `chat`：附件、上传、导出、发送提示

### `web-ui/app/locales/en-US/markdown.json`

英文Markdown组件UI文案的本地化资源文件。  
- `code_block`：代码块操作文案（复制、预览、下载等）  
- `markdown`：代码预览标题模板

### `web-ui/app/locales/en-US/message.json`

英文国际化文件，定义聊天消息、工具调用、媒体显示的UI文本。

- `message_parts`：思考步骤与深度思考提示
- `chat_message`：消息操作（复制/编辑/删除/导出/分支）及token统计
- `tool_part`：工具调用状态（搜索/记忆/剪贴板/询问用户等）
- `media_part`：视频/音频加载失败与新窗口打开提示

### `web-ui/app/locales/en-US/page.json`

为 Zhixing Web 客户端提供对话页面的英文文案。
- `conversations.meta`：页面标题与描述
- `conversations.preview`：消息内容预览（空、图片、视频等）
- `conversations.errors`：加载详情失败提示
- `conversations.empty_state`：选对话、加载中、错误、无消息等状态
- `conversations.welcome_prompt`：欢迎提问语
- `conversations.user.default_name`：默认用户名称
- `conversations.header.select_conversation`：选择对话标题

### `web-ui/app/locales/zh-CN/common.json`

简体中文翻译文件，定义会话侧边栏、搜索、跳转、工作台等界面的中文文本。

- `conversation_sidebar`：会话侧边栏文本
- `conversation_search`：会话搜索文本
- `quick_jump`：快速跳转文本
- `workbench`：工作台文本
- `custom_theme_dialog`：自定义主题对话框文本
- `infinite_scroll`：无限滚动文本
- `web_auth_gate`：Web认证解锁界面文本

### `web-ui/app/locales/zh-CN/input.json`

中文简体输入面板和对话界面的国际化文本。  
- `mcp`：MCP服务器面板  
- `injection`：扩展（快捷消息/模式/知识库）  
- `reasoning`：推理预算配置  
- `model_list`：模型选择面板  
- `search`：联网搜索配置  
- `chat`：对话附件/提示/导出

### `web-ui/app/locales/zh-CN/markdown.json`

Markdown 组件/代码块的中文简体翻译资源文件。
- `code_block`：代码块操作字符串（复制、下载、预览等）
- `markdown`：Markdown 预览标题模板

### `web-ui/app/locales/zh-CN/message.json`

聊天消息及工具步骤的中文简体本地化文案。
- `message_parts`：思考步骤与工具步骤的提示文案
- `chat_message`：消息操作、复制、重新生成、删除、分支、导出、token 统计等
- `tool_part`：工具调用、记忆、联网搜索、剪贴板、询问用户等标签
- `media_part`：视频/音频加载失败提示

### `web-ui/app/locales/zh-CN/page.json`

会话页面中文文案翻译。
- `conversations.meta`：SEO标题与描述
- `conversations.preview`：消息预览占位符
- `conversations.errors`：错误提示
- `conversations.empty_state`：空状态引导
- `conversations.welcome_prompt`：欢迎语
- `conversations.user`：默认用户名
- `conversations.header`：选择会话提示

### `web-ui/app/root.tsx`

应用根组件，集成 Provider、布局、错误与加载状态。

- `links`：favicon 定义
- `Layout`：HTML 骨架
- `App`：QueryClient + 主题 + 路由出口
- `HydrateFallback`：加载动画
- `ErrorBoundary`：路由错误展示

### `web-ui/app/routes.ts`

React Router 路由配置，定义首页与动态聊天页映射。
- `默认导出`：路由配置数组
- `index("routes/home.tsx")`：首页路由
- `route("c/:id", "routes/c.$id.tsx")`：动态聊天页路由

### `web-ui/app/routes/c.$id.tsx`

重导出 `conversations` 路由的 `meta` 和默认组件。  
- `meta`：路由元数据  
- `default`：路由页面组件

### `web-ui/app/routes/conversations.tsx`

对话页面，含侧边栏、消息流、草稿编辑、分支选择等交互。
- `ConversationsPage`：默认导出页面组件
- `meta`：导出页面标题与描述
- `ConversationTimeline`：消息流渲染组件
- `useConversationDetail`：获取并流式更新对话详情
- `useDraftInputController`：管理输入草稿及提交
- `applyNodeUpdate`：应用节点更新到对话数据
- `toEditDraft`：从消息提取编辑草稿
- `EditingSession`：编辑会话类型定义

### `web-ui/app/routes/home.tsx`

作为首页路由，重新导出 `conversations` 路由的 meta 和默认组件。
- `meta`：路由元数据
- `default`：首页组件

### `web-ui/app/services/api.ts`

封装 HTTP API 客户端，含认证令牌管理与 SSE 支持。
- `ApiError`：自定义错误类
- `setWebAuthToken`：存储令牌
- `clearWebAuthToken`：清除令牌
- `onWebAuthRequired`：监听认证失效事件
- `appendWebAuthQuery`：URL 附加令牌
- `requestWebAuthToken`：请求并存储令牌
- `SSEEvent` / `SSECallbacks`：SSE 类型
- `sse`：发起 SSE 连接
- `api`（默认导出）：`get/post/put/patch/delete` 方法

### `web-ui/app/services/events.ts`

多路复用 SSE 客户端，管理 `/api/events` 共享连接。
- `EVENT_SETTINGS`：设置事件名
- `EVENT_CONVERSATION_LIST_INVALIDATE`：会话列表失效事件名
- `EVENT_FOLDERS`：文件夹事件名
- `subscribeToEvent`：订阅指定事件类型，返回取消订阅函数

### `web-ui/app/stores/app-store.ts`

- **组合 Zustand 切片的主 store**。
- `useAppStore`：组合 settings/chat-input/clock 切片的 store hook。
- `useSettingsStore`：同 useAppStore，别名。
- `useChatInputStore`：同 useAppStore，别名。
- `useClockStore`：同 useAppStore，别名。
- 导出类型：`AppStoreState`, `ChatInputSlice`, `ClockSlice`, `Draft`, `SettingsSlice`。

### `web-ui/app/stores/chat-input.ts`

聊天输入 store 统一导出入口。
- `useChatInputStore`：重导出聊天输入全局状态 store。

### `web-ui/app/stores/hooks/use-settings-subscription.ts`

订阅设置事件并同步到全局状态。
- `useSettingsSubscription`：在根组件调用一次，订阅 `EVENT_SETTINGS` 更新 `setSettings`。

### `web-ui/app/stores/index.ts`

集中导出 stores 与相关类型。
- `useAppStore`：应用全局状态 hook
- `useClockStore`：时钟状态 hook
- `useChatInputStore`：聊天输入状态 hook
- `useSettingsStore`：设置状态 hook
- `useSettingsSubscription`：设置订阅 hook
- `AppStoreState`：应用状态类型
- `ChatInputSlice`：聊天输入切片类型
- `ClockSlice`：时钟切片类型
- `Draft`：草稿类型
- `SettingsSlice`：设置切片类型

### `web-ui/app/stores/settings.ts`

重新导出设置相关的 Zustand store 和订阅 hook。

- `useSettingsStore`：设置状态管理 store
- `useSettingsSubscription`：设置变更订阅 hook

### `web-ui/app/stores/slices/chat-input-slice.ts`

管理每个会话的聊天输入草稿状态，支持文本、附件和注入配置。

- `EMPTY_DRAFT`：默认空草稿
- `getDraft`：获取或初始化草稿
- `createChatInputSlice`：Zustand切片工厂，提供setText/addParts/removePartAt/setPromptInjectionIds/getPromptInjectionIds/clearDraft/isEmpty/getSubmitParts

### `web-ui/app/stores/slices/clock-slice.ts`

Zustand 时钟切片，存储客户端与服务器时间偏移量。
- `createClockSlice`：创建含 `clockOffset` 状态和 `setClockOffset` 动作的切片。

### `web-ui/app/stores/slices/settings-slice.ts`

定义 Zustand 设置切片，提供 settings 状态与更新方法。
- `createSettingsSlice`：创建设置切片，含 `settings` 状态和 `setSettings` 更新函数

### `web-ui/app/stores/slices/types.ts`

定义 Zustand store 切片类型及组合状态。
- `Draft`：草稿结构（文本、部件、注入ID）
- `SettingsSlice`：设置状态与设置方法
- `ChatInputSlice`：聊天输入草稿管理方法
- `ClockSlice`：时钟偏移状态与设置方法
- `AppStoreState`：组合所有切片的 store 状态类型

### `web-ui/app/types/annotations.ts`

定义消息注解类型，包含 URL 引用注解。
- `UrlCitationAnnotation`：URL 引用注解接口
- `UIMessageAnnotation`：消息注解联合类型

### `web-ui/app/types/conversation.ts`

定义对话与消息节点TypeScript类型。
- `MessageNode`：消息分支容器，含消息列表和选择索引
- `Conversation`：对话数据，含标题、消息节点、置顶、自定义提示词、工作目录等

### `web-ui/app/types/core.ts`

定义 AI 消息角色与 Token 用量数据结构。
- `MessageRole`：消息角色联合类型
- `TokenUsage`：Token 用量统计接口

### `web-ui/app/types/dto.ts`

定义前后端通信的数据传输类型。

- `ConversationListDto`：会话列表项
- `FolderDto`：文件夹结构
- `FolderListEventDto`：文件夹变更事件
- `PagedResult<T>`：分页结果
- `UploadedFileDto`：上传文件信息
- `ConversationListInvalidateEventDto`：列表失效事件
- `MessageDto`：消息体
- `MessageNodeDto`：消息节点
- `ConversationDto`：完整会话
- `ConversationSnapshotEventDto`：会话快照事件
- `ConversationNodeUpdateEventDto`：节点更新事件
- `ConversationErrorEventDto`：错误事件
- `MessageSearchResultDto`：消息搜索结果

### `web-ui/app/types/helpers.ts`

为会话消息/节点提供获取当前消息与类型守卫工具函数。
- `getCurrentMessage`：获取节点当前选中的UIMessage
- `getCurrentMessageDto`：获取节点当前选中的MessageDto
- `getCurrentMessages`：获取会话所有当前消息
- `isTextPart`：判定是否为文本部分
- `isImagePart`：判定是否为图片部分
- `isReasoningPart`：判定是否为推理部分
- `isToolPart`：判定是否为工具部分
- `isDocumentPart`：判定是否为文档部分

### `web-ui/app/types/index.ts`

类型定义聚合导出入口。  
- `core`：核心类型  
- `parts`：UI 部件类型  
- `annotations`：标注类型  
- `message`：消息类型  
- `conversation`：对话类型  
- `dto`：数据传输对象  
- `settings`：设置类型  
- `helpers`：辅助类型

### `web-ui/app/types/message.ts`

定义前端聊天消息的UI数据结构。
- `UIMessage`：包含id、角色、内容片段、标注、时间戳等字段的消息接口。

### `web-ui/app/types/parts.ts`

定义聊天消息部分的数据类型。
- `ToolApprovalState`：工具审批状态联合类型
- `TextPart`：文本消息部分
- `ImagePart`：图片消息部分
- `VideoPart`：视频消息部分
- `AudioPart`：音频消息部分
- `DocumentPart`：文档消息部分
- `ReasoningPart`：推理消息部分
- `ToolPart`：工具调用消息部分
- `UIMessagePart`：所有消息部分的联合类型

### `web-ui/app/types/settings.ts`

定义前端设置、助手、模型、MCP等核心类型接口。
- `DisplaySetting`：显示选项
- `AssistantProfile`：助手配置
- `ProviderProfile`：模型提供者配置
- `McpServerConfig`：MCP服务器配置
- `Settings`：应用总设置聚合
- `ModelType`, `ModelModality`, `ModelAbility`：模型类型/模态/能力枚举

### `web-ui/components.json`

Shadcn UI 组件库配置，定义样式、路径别名及注册表。
- `style`: new-york
- `tsx`: true
- `tailwind`: css `app/app.css`, baseColor `neutral`, cssVariables true
- `iconLibrary`: lucide
- `aliases`: components `~/components`, utils `~/lib/utils`, ui `~/components/ui`, lib `~/lib`, hooks `~/hooks`
- `registries`: `@ai-elements` 源

### `web-ui/copy.ts`

将前端构建产物复制到后端静态资源目录
- `SOURCE_DIR`：源目录 `./build/client`
- `TARGET_DIR`：目标目录 `../web/src/main/resources/static`
- `copyDirectory`：递归复制目录函数

### `web-ui/package.json`

React Router 7 前端项目配置，含构建/开发/类型检查/代码规范脚本。  
- `build`：构建并复制文件  
- `dev`：开发服务器  
- `typecheck`：类型生成与检查  
- `lint`/`fmt`：代码检查与格式化  
- 依赖：`react-router`、`react-query`、`zustand`、`shiki`、`tailwindcss` 等

### `web-ui/pnpm-workspace.yaml`

pnpm 工作区配置，声明允许构建的包。
- `allowBuilds`：允许的构建包列表，此处仅允许 `esbuild` 构建。

### `web-ui/react-router.config.ts`

React Router 配置，禁用 SSR 启用 SPA 模式。
- `Config` 类型：用于类型校验的 React Router 配置接口
- `default export`：配置对象，关键条目 `ssr: false`（SPA 模式）

### `web-ui/tsconfig.json`

web-ui 的 TypeScript 配置，面向 React Router 项目。

- `include`：涵盖根目录、`.server/`、`.client/` 及 React Router 类型生成
- `compilerOptions`：ES2022 目标，bundler 模块解析，React JSX，路径别名 `~/*` → `./app/*`，严格模式，noEmit

### `web-ui/vite-env.d.ts`

Vite 环境声明文件，引入 SVG 组件类型支持。
- 无导出符号，仅引用 `vite-plugin-svgr/client` 类型声明。

### `web-ui/vite.config.ts`

Vite 配置，集成 Tailwind、React Router、SVGR 并代理 API
- `defineConfig`：导出 Vite 配置
- `plugins`：含 tailwindcss、reactRouter、tsconfigPaths、svgr
- `server.proxy`：将 `/api` 请求代理到 `http://localhost:8080`
