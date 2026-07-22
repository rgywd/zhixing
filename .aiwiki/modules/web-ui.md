---
source_commit: 762c37f2ba2c6f3f982c8b1ae5761fadb904b8af
generated: 2026-07-21
---


# 模块：web-ui

Web UI 是 AI 对话应用的单页前端，基于 React Router 7 + Vite 构建，负责界面渲染、路由与状态管理。

模块按 `app/` 目录组织，采用文件路由（routes）、组件、Zustand 状态切片和 API 服务层。开发时 Vite 代理 `/api` 到后端 `:8080`；构建产物通过 `copy.ts` 同步至 Ktor 静态资源目录。核心依赖 React Query 管理请求状态，i18next 支持中英文，Shadcn UI 提供组件库，SSE 实现流式响应。

**修改指引**：UI 改动从 `app/routes` 或 `app/components` 入手；新功能遵循类型映射在 `app/types` 对齐 Kotlin 模型，状态添加至 `app/stores` 切片。


## 子模块

| 页面 | 文件数 | 职责 |
|---|---|---|
| [`.vscode`](web-ui/.vscode.md) | 1 | VSCode 工作区配置，统一编辑器格式化与国际化开发体验。 |
| [`app`](web-ui/app.md) | 4 | web-ui/app 是AI对话应用的前端模块，负责界面渲染、路由、主题、国际化与状态管理。 |

## 文件摘要

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

> 符号导航：[files/web-ui/CLAUDE.md.md](../files/web-ui/CLAUDE.md.md)

### `web-ui/README.md`

React Router 全栈模板说明文档。

- 特性：SSR、HMR、TypeScript、TailwindCSS
- 开发：`npm install` / `npm run dev`（:5173）
- 构建：`npm run build`
- 部署：Docker 或 diy（`build/` 输出）

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
