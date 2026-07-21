---
source_commit: 4e68436185cb505db643c54224ab82d2f2745844
generated: 2026-07-21
---


# 模块：search

搜索模块封装 20+ 搜索引擎的 API 集成，提供统一的搜索与网页抓取接口。
模块以 `SearchService<T>` 抽象接口为核心，各服务实现类（如 `BingSearchService`、`TavilySearchService` 等）独立封装 API 调用、参数 schema 与响应解析，统一产出 `SearchResult`/`ScrapedResult`。`SearchServiceOptions` 密封类管理所有服务选项，UI 层通过选项选择服务。模块依赖 `:ai` 与 `:common` 模块，使用 OkHttp 做网络请求，Kotlinx Serialization 处理 JSON，QuickJS 支持自定义脚本搜索。
**修改指引**：新增搜索服务时，创建 `SearchService` 实现类，并在 `SearchServiceOptions` 中注册对应子类。

## 文件摘要

### `search/build.gradle.kts`

Android 搜索模块 Gradle 构建脚本，配置 Compose、序列化及实验性 API。
- 插件：`androidLibrary`, `kotlinSerialization`, `kotlinCompose`
- 实验性 API：`ExperimentalMaterial3Api`, `ExperimentalAnimationApi`, `ExperimentalFoundationApi`, `ExperimentalLayoutApi`, `ExperimentalUuidApi`, `ExperimentalTime`, `ExperimentalCoroutinesApi`
- 依赖：`project(:ai)`, `project(:common)`, `okhttp`, `kotlinx.serialization.json`, `jsoup`, `quickjs`

### `search/consumer-rules.pro`

- 空消费者规则文件，供该模块发布时保留入口。  
- 无关键符号。

### `search/proguard-rules.pro`

搜索模块的 ProGuard 混淆规则文件，当前为空模板。

- 无自定义规则定义，仅含注释示例（保留 WebView JS 接口、行号调试等）。

### `search/src/androidTest/java/me/rerere/search/ExampleInstrumentedTest.kt`

示例Android仪器化测试，验证包名。
- `ExampleInstrumentedTest`：Android仪器化测试类
- `useAppContext`：测试方法，验证应用上下文包名

### `search/src/main/AndroidManifest.xml`

Android 清单文件，声明网络权限。
- `uses-permission`：请求 `android.permission.INTERNET` 权限。

### `search/src/main/java/me/rerere/search/AnySearchService.kt`

AnySearch 搜索服务实现，封装 API 调用与结果解析。
- `AnySearchService`：实现 SearchService 的单例对象
- `search`：执行搜索，解析 AnySearch 响应
- `scrape`：不支持抓取，直接返回失败
- `parameters`：定义查询参数 schema
- `AnySearchResponse`/`AnySearchData`/`AnySearchResultItem`：响应数据类

### `search/src/main/java/me/rerere/search/BingSearchService.kt`

Bing 网页搜索服务实现，解析必应搜索结果。
- `BingSearchService`：实现必应搜索的单例服务
- `search`：抓取并解析必应搜索结果
- `scrape`：暂不支持网页抓取

### `search/src/main/java/me/rerere/search/BochaSearchService.kt`

Bocha 网页搜索服务实现，封装 Bocha API 调用。
- `BochaSearchService`：`SearchService` 实现，调用 Bocha 搜索 API。
- `search`：发起搜索请求，解析响应为 `SearchResult`。
- `scrape`：不支持抓取，返回失败。
- `Description`：提供获取 API Key 的链接。
- `BochaResponse` 等数据类：映射 API 响应结构。

### `search/src/main/java/me/rerere/search/BraveSearchService.kt`

实现 Brave 搜索 API 集成，不支持网页抓取。
- `BraveSearchService`：单例搜索服务，调用 Brave Web Search API。
- `parameters`：定义搜索参数 schema（需 query）。
- `search`：执行搜索请求，返回结果列表。
- `scrape`：不支持，返回失败。
- `BraveSearchResponse`：搜索响应数据类。

### `search/src/main/java/me/rerere/search/CustomJsSearchService.kt`

执行用户自定义 JS 搜索与抓取脚本的搜索服务实现。
- `CustomJsSearchService`：SearchService 实现，通过 QuickJS 运行 JS 脚本
- `executeScript`：注入 fetch 后执行用户脚本
- `quoteJsString`：转义 JS 字符串字面量

### `search/src/main/java/me/rerere/search/DoubaoSearchService.kt`

豆包搜索服务实现，处理API请求与响应解析
- `DoubaoSearchService`：搜索服务入口
- `buildDoubaoSearchRequest`：构造HTTP请求
- `parseDoubaoSearchResponse`：解析响应
- `DoubaoSearchResponse`：响应数据模型
- `DoubaoWebResult`：结果项模型
- `DOUBAO_SEARCH_URL`：API端点

### `search/src/main/java/me/rerere/search/ExaSearchService.kt`

Exa搜索API集成服务，实现搜索、参数定义与UI。
- `ExaSearchService`：Exa搜索服务单例
- `search`：执行搜索请求
- `parameters`：定义搜索参数schema
- `Description`：提供API Key获取链接
- `ExaData`：搜索响应数据模型
- `ExaResult`：搜索结果项

### `search/src/main/java/me/rerere/search/FirecrawlSearchService.kt`

实现Firecrawl搜索与抓取，处理API调用及响应解析。  
- `FirecrawlSearchService`：搜索/抓取服务对象  
- `search`：POST搜索请求，返回结果  
- `scrape`：POST抓取请求，返回Markdown内容  
- `parameters`/`scrapingParameters`：定义输入参数schema  
- `Description`：渲染API密钥获取链接  
- `FirecrawlSearchResultData`/`WebItem`/`NewsItem`：API响应数据类  
- `asStringList`：JSON元素转字符串列表扩展

### `search/src/main/java/me/rerere/search/GrokSearchService.kt`

实现 Grok API 搜索，解析响应并返回结果项。
- `GrokSearchService`：搜索服务实现，提供 `search`、`scrape`、`parameters`、`Description` 方法。
- `GrokResponse`、`GrokOutputItem`、`GrokContent`、`GrokAnnotation`：响应数据类。

### `search/src/main/java/me/rerere/search/JinaSearchService.kt`

Jina搜索服务实现，封装搜索与网页抓取。
- `JinaSearchService`：单例搜索服务，实现SearchService
- `search` / `scrape`：执行搜索/抓取
- `parameters` / `scrapingParameters`：参数schema
- `JinaSearchResponse` / `JinaScrapeResponse`：响应数据类
- `DEFAULT_SEARCH_URL` / `DEFAULT_SCRAPE_URL`：默认API端点

### `search/src/main/java/me/rerere/search/LegacyHostedSearchService.kt`

已禁用旧版搜索服务的占位实现，防止框架引用缺失。
- `LegacyHostedSearchService`：占位服务，所有方法返回失败或空
- `name`：标识为"Legacy hosted search (disabled)"
- `Description`：空Composable
- `parameters`：返回简单查询参数schema
- `scrapingParameters`：返回null
- `search`：返回失败，提示不可用
- `scrape`：返回失败，提示不可用

### `search/src/main/java/me/rerere/search/LinkUpService.kt`

封装 LinkUp 搜索/抓取 API 的服务实现。
- `LinkUpService`：搜索服务对象
- `search`：执行搜索，返回 `SearchResult`
- `scrape`：抓取网页，返回 `ScrapedResult`
- `LinkUpSearchResponse`：搜索响应数据结构
- `LinkUpFetchResponse`：抓取响应数据结构
- `Description`：API 密钥获取提示 UI

### `search/src/main/java/me/rerere/search/MetasoSearchService.kt`

实现 Metaso 搜索引擎的搜索服务对象。
- `MetasoSearchService`：搜索服务入口
- `MetasoSearchResponse`：API 响应数据类
- `MetasoSearchParameters`：搜索参数
- `MetasoWebpage`：网页结果
- `Description`：Composable 描述
- `search`：执行搜索
- `scrape`：不支持抓取

### `search/src/main/java/me/rerere/search/OllamaSearchService.kt`

实现 Ollama Web 搜索与网页抓取服务。
- `OllamaSearchService`：搜索服务单例
- `search`：调用搜索 API
- `scrape`：抓取网页内容
- `OllamaSearchResponse`：搜索响应数据类
- `OllamaScrapeResponse`：抓取响应数据类

### `search/src/main/java/me/rerere/search/PerplexitySearchService.kt`

实现 Perplexity API 搜索服务，支持搜索请求与参数构建。
- `PerplexitySearchService`：搜索服务单例，实现 `SearchService<PerplexityOptions>`
- `search()`：执行搜索，解析响应为 `SearchResult`
- `PerplexityResponse`：JSON 响应模型，含 `answer` 与 `results`

### `search/src/main/java/me/rerere/search/SearXNGService.kt`

实现 SearXNG 搜索 API 调用的服务对象。
- `SearXNGService`：搜索服务实现，支持搜索/参数定义
- `search`：执行 SearXNG 查询并返回标准结果
- `parameters`：定义输入 schema（query 字段）
- `SearXNGResponse`/`SearXNGResult`：API 响应数据类

### `search/src/main/java/me/rerere/search/SearchService.kt`

定义搜索服务抽象接口、数据模型及20+种服务选项配置。
- `SearchService<T>`：搜索服务接口，定义 search/scrape 等方法
- `SearchServiceOptions`：密封类，含 Bing/Tavily/Exa/Zhipu 等子类
- `SearchCommonOptions`：通用搜索选项（resultSize）
- `SearchResult`/`ScrapedResult`：搜索结果/抓取结果数据类
- `Call.await()`：OkHttp 扩展挂起函数

### `search/src/main/java/me/rerere/search/SerperSearchService.kt`

Serper 搜索服务实现，封装 Serper API 的搜索请求与结果解析。

- `SerperSearchService`：搜索服务单例，实现 `SearchService`。
- `SerperSearchResponse`：Serper API 响应数据类。
- `AnswerBox`、`KnowledgeGraph`、`OrganicResult`：搜索结果子结构。

### `search/src/main/java/me/rerere/search/TavilySearchService.kt`

Tavily搜索服务实现，封装搜索与网页抓取API。
- `TavilySearchService`：搜索服务对象
- `search`：执行Tavily搜索
- `scrape`：抓取URL内容
- `parameters`/`scrapingParameters`：定义输入schema
- `SearchResponse`/`SearchResultItem`/`ScrapeResponse`/`ScrapedResultItem`：序列化数据类

### `search/src/main/java/me/rerere/search/TinyfishSearchService.kt`

Tinyfish 搜索服务实现，提供搜索和网页抓取功能。
- `TinyfishSearchService`：实现 SearchService 的单例
- `search`：发起搜索请求，返回结果
- `scrape`：抓取网页内容，返回 markdown
- `parameters`：定义搜索参数 JSON Schema
- `scrapingParameters`：定义抓取参数 JSON Schema
- `Description`：提供获取 API Key 的 UI 链接

### `search/src/main/java/me/rerere/search/ZhipuSearchService.kt`

实现智谱 Web 搜索 API 调用，含 UI 描述和参数定义。
- `ZhipuSearchService`：智谱搜索服务实现，实现搜索、UI 描述及参数 schema
- `ZhipuDto` / `ZhipuSearchResultDto`：API 响应反序列化结构
- `search`：调用智谱 /v4/web_search 执行搜索
- `scrape`：不支持抓取，固定返回失败
- `parameters`：定义 query 字符串参数 schema
- `Description`：Composable 获取 API Key 链接

### `search/src/main/res/values-ja/strings.xml`

日语字符串资源，定义搜索提示文本。
- `bing_desc`：Bing搜索不推荐
- `click_to_get_api_key`：获取API Key
- `searxng_desc_1`：需启用json格式
- `searxng_desc_2`：配置示例
- `custom_js_desc`：自定义搜索提供者说明

### `search/src/main/res/values-ko-rKR/strings.xml`

这是一个韩语字符串资源文件，为搜索模块提供韩语界面文本。

- `bing_desc`：Bing 搜索说明
- `click_to_get_api_key`：获取 API 密钥提示
- `searxng_desc_1`：SearXNG 配置说明开头
- `searxng_desc_2`：SearXNG JSON 格式配置示例
- `custom_js_desc`：自定义 JS 搜索提供者文档

### `search/src/main/res/values-ru/strings.xml`

搜索模块俄语字符串资源，提供提示与说明文本。
- `bing_desc`：Bing搜索不稳定警告
- `click_to_get_api_key`：获取API密钥操作提示
- `searxng_desc_1`：SearXNG配置JSON支持说明
- `searxng_desc_2`：SearXNG配置示例代码
- `custom_js_desc`：自定义JS搜索实现指引

### `search/src/main/res/values-zh-rTW/strings.xml`

繁体中文搜索功能界面字符串资源。
- `bing_desc`：Bing搜索风险提示
- `click_to_get_api_key`：获取API密钥链接
- `searxng_desc_1`：SearXNG JSON格式说明
- `searxng_desc_2`：JSON格式配置示例
- `custom_js_desc`：自定义JS搜索提供者说明

### `search/src/main/res/values-zh/strings.xml`

中文简体字符串资源，为搜索功能提供本地化文本。
- `bing_desc`：Bing搜索爬虫易被拦截，不推荐使用
- `click_to_get_api_key`：提示点击获取API Key
- `searxng_desc_1`：要求开启json format支持
- `searxng_desc_2`：配置示例
- `custom_js_desc`：QuickJS自定义搜索提供程序说明

### `search/src/main/res/values/strings.xml`

定义搜索功能相关的用户提示与描述字符串。
- `bing_desc`：Bing搜索不稳定警告
- `click_to_get_api_key`：获取API Key链接提示
- `searxng_desc_1`：SearXNG需要启用json格式提示
- `searxng_desc_2`：settings.yml配置示例
- `custom_js_desc`：自定义JS搜索函数说明

### `search/src/test/java/me/rerere/search/DoubaoSearchServiceTest.kt`

单元测试 DoubaoSearchService 请求构建与响应解析。
- `DoubaoSearchServiceTest`：测试类
- `requestUses...`：验证请求端点、认证和负载
- `responsePrefers...`：验证摘要优先解析逻辑
- `missingWebResults...`：验证缺少 WebResults 报错
- `emptyApiKey...`：验证空 API Key 返回失败

### `search/src/test/java/me/rerere/search/ExampleUnitTest.kt`

单元测试示例，验证本地加法逻辑正确性。

- `ExampleUnitTest`：示例单元测试类
- `addition_isCorrect`：测试2+2等于4
