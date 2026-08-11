---
applyTo: "frontend/**"
description: "COSP 管理后台前端约定。Use when: 修改 frontend/ 下的 Vue 组件、Pinia store、SCSS 主题、请求体规则编辑器或前端单测。"
---

# 前端开发指南

Vue 3 + TypeScript + Pinia + Naive UI + SCSS，构建工具 Vite。无 ESLint / Prettier，唯一质量关卡是 `npm run build` 中的 `vue-tsc -b`（`strict: true`）。

`tsconfig.json` 只做项目引用聚合，实际配置在两个子项目里：`tsconfig.app.json` 管 `src/**`（浏览器侧，**刻意不含 Node 类型**，防止误用 `process` / `__dirname` 后仍通过检查），`tsconfig.node.json` 管 `vite.config.ts`（含 `@types/node`）。新增构建期脚本要加进 `tsconfig.node.json` 的 `include`，否则不受类型检查覆盖。

## 构建与测试

```bash
cd frontend
npm run test:run        # vitest run，CI 用
npm run build           # vue-tsc -b && vite build
```

- **`npm run build` 的 `outDir` 是 `../src/main/resources/static` 且 `emptyOutDir: true`**：构建会清空并覆写 Spring Boot 静态资源目录。
- `./mvnw test` 通过 `frontend-maven-plugin` 在 `generate-resources` 阶段自带一次前端构建（Node v22.14.0）。
- Vitest 复用 `vite.config.ts`，没有独立配置文件。测试文件与被测源文件同目录，命名 `*.spec.ts`。
- dev server 在 5173，**只代理 `/config/api` 与 `/auth`** 到 `http://localhost:11434`。登录接口是 `POST /auth/login`，已被 `/auth` 覆盖。
- **绝不要把 `/login` 或其它前端路由加入代理**：它们必须由 Vite 的 history fallback 返回 dev 版 index.html。一旦代理到后端，浏览器拿到的是 `src/main/resources/static/` 里的构建产物（引用带 hash 的 `/assets/*.js`），dev server 上不存在这些文件 → 404 → 整页白屏。

## 目录职责

| 目录 | 内容 |
|---|---|
| `src/features/**` | 纯 TS 领域逻辑，可单测，不含 UI（当前只有 `request-body-rules/`） |
| `src/components/<feature>/` | 对应 UI 组件，用 `index.ts` 做 barrel 导出 |
| `src/stores/` | Pinia store，同时是 DTO 类型定义的所在处 |
| `src/views/` | 路由页面 |
| `src/api/` | axios 实例与 SSE 封装 |

**新增可测逻辑优先放 `features/` 或组件目录下的 `useXxx.ts` / 纯数据模块**，让组件保持薄。纯数据与类型模块用小写模块名（`heatmap.ts`、`usagechart.ts`），组合式函数用 `useXxx.ts`。

## 约定

- 所有 `.vue` 统一 `<script setup lang="ts">`，样式统一 `<style lang="scss" scoped>`。
- 请求一律 `import http from '@/api'` 复用那个 axios 实例（`baseURL: '/config/api'`，拦截器负责注入 Bearer 和 401 跳登录）。**具体端点写在各 store / view 里，`api/index.ts` 不是端点清单**。
- 需要 SSE 时用 `@/api/authEventSource` 的 `createAuthEventSource`，原生 `EventSource` 无法带 Authorization 头。
- DTO 接口从对应 store 导出（如 `stores/providers.ts` 的 `Provider`、`ProviderModel`），不要在 `api/` 下另建类型层。
- Token 存 `localStorage['cosp_token']`，只用 `auth` 工具对象读写。
- 401 后的登录跳转统一调 `api/index.ts` 导出的 `redirectToLogin()`（axios 拦截器与 SSE 共用），它会带上 `?redirect=` 供登录后回跳；组件内部能拿到 router 时用 `router.replace({ name: 'login', query: { redirect: route.fullPath } })`。
- 路由表末尾有 catch-all 指向 `views/NotFound.vue`。后端把所有非 API 的浏览器请求都回退成 index.html，拼错的地址会进入前端路由 —— 删了这条就会渲染成空白页。

## 主题

设计 token 在 `src/styles/_variables.scss`（SCSS 变量，如 `$accent #c27a3e`、`$bg #f5f3ee`）。**同一套色值在 `src/App.vue` 的 Naive UI `GlobalThemeOverrides` 里第二次硬编码，改主题色必须同步两处。** 没有 CSS 自定义属性体系，也没有深色模式。

Naive UI 组件的内联 CSS 变量优先级高于 scoped class 里的同名变量覆盖，遇到样式不生效先查这一点（`views/Preferences.vue` 有实例注释）。

## 请求体规则编辑器

`features/request-body-rules/` 与后端 `RequestBodyRuleEngine`、`ProviderRequestTransformService` 是一套契约：操作类型只有 `edit_object` / `set_value` / `delete`，条件只有 `exists` / `equals`，模板键有 7 个白名单值。扩展任一侧都要同步另一侧，并补 `*.spec.ts`。

## 手动验证

`npm run mock:stream` 启动流式 mock 上游（8081），`npm run mock:nonstream` 启动非流式 mock 上游（8082），`npm run mock:cosp` 启动 mock COSP（11333）。三者都是零依赖 Node 脚本，用法见各自 README。
