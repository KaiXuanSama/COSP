---
applyTo: "frontend/**"
description: "COSP 管理后台前端约定。Use when: 修改 frontend/ 下的 Vue 组件、Pinia store、API/SSE、路由、SCSS 主题、请求体规则编辑器或 Vitest 单测。"
---

# 前端开发指南

Vue 3 + TypeScript + Pinia + Naive UI + SCSS，构建工具 Vite。无 ESLint / Prettier；静态检查随
`npm run build` 执行 `vue-tsc -b`（`strict: true`），行为测试需另跑 `npm run test:run`。

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
| `src/features/**` | 可单测的领域逻辑与轻量 composable；不放 SFC |
| `src/components/<feature>/` | 对应 UI 组件；导出边界以目录 `index.ts` 和 README 为准 |
| `src/stores/` | Pinia store 与其拥有的状态类型 |
| `src/types/` | 跨页面、跨 store 的共享协议/DTO 类型 |
| `src/views/` | 路由页面 |
| `src/api/` | Axios 实例、共享端点函数与认证 SSE 封装 |

**新增可测逻辑优先放 `features/` 或组件目录下的 `useXxx.ts` / 纯数据模块**，让组件保持薄。纯数据与类型模块用小写模块名（`heatmap.ts`、`usagechart.ts`），组合式函数用 `useXxx.ts`。

## 约定

- `.vue` 默认使用 `<script setup lang="ts">` 与 scoped SCSS；全局入口、布局/Teleport 或组件契约
  明确要求时可用非 scoped 样式，已有 plain CSS 组件不要为统一语法而改写。
- 普通 `/config/api/**` 请求复用 `@/api` 的 Axios 实例（拦截器负责 Bearer 与管理员会话 401）；
  跨页面共享端点函数可放 `src/api/`。`/auth/login`、`/auth/me` 和认证 SSE 是明确的 `fetch` 例外。
- 需要 SSE 时用 `@/api/authEventSource` 的 `createAuthEventSource`，原生 `EventSource` 无法带 Authorization 头。
- 类型放在其所有者处：store 状态类型随 store，跨页面协议放 `src/types/`，领域响应放对应 feature；
  不要在 `api/` 再复制一套 DTO。
- Token 存 `localStorage['cosp_token']`，只用 `auth` 工具对象读写。
- 401 后的登录跳转统一调 `api/index.ts` 导出的 `redirectToLogin()`（axios 拦截器与 SSE 共用），它会带上 `?redirect=` 供登录后回跳；组件内部能拿到 router 时用 `router.replace({ name: 'login', query: { redirect: route.fullPath } })`。
- 模型拉取端点的 401 可能来自上游 API Key，调用必须保留 `skipAuthRedirect`，不能把管理员误登出。
- 路由表末尾有 catch-all 指向 `views/NotFound.vue`。后端把所有非 API 的浏览器请求都回退成 index.html，拼错的地址会进入前端路由 —— 删了这条就会渲染成空白页。
- 上游响应、调用日志和 Markdown 都是不可信输入；未经 HTML 净化不得交给 `v-html`。JWT 位于
  localStorage，这类 XSS 会直接扩大为管理员会话泄露。
- HTTP 状态 `-1` 只表示非 HTTP 异常，可能是连接、DNS、TLS、截断或空响应耗尽；前端不得固定显示为“空响应”。

## 主题

设计 token 在 `src/styles/_variables.scss`（SCSS 变量，如 `$accent #c27a3e`、`$bg #f5f3ee`）。**同一套色值在 `src/App.vue` 的 Naive UI `GlobalThemeOverrides` 里第二次硬编码，改主题色必须同步两处。** 没有全局 CSS custom-property 主题体系，也没有深色模式；组件族可以拥有局部 CSS 变量。

Naive UI 组件的内联 CSS 变量优先级高于 scoped class 里的同名变量覆盖，遇到样式不生效先查这一点（`views/Preferences.vue` 有实例注释）。

## 请求体规则编辑器

`features/request-body-rules/` 与后端 `application/provider/RequestBodyRuleEngine`、`ProviderRequestTransformService` 是一套契约：操作类型只有 `edit_object` / `set_value` / `delete`，条件只有 `exists` / `equals`，模板键有 7 个白名单值。扩展任一侧都要同步另一侧，并补 `*.spec.ts`。

规则集是 V2（`{version:2, groups:[...]}`）：每个规则组自带 `protocols`、`templateKeys`、`previewBody`。读旧数据一律过 `migration.ts` 的 `migrateRuleSet`，**不要直接 `JSON.parse` 后当 V2 用**；`ruleSetJson.ts` 只校验 V2，遇到 V1 会报错而不是默默升级。

编辑器分两层：`RequestBodyRuleEditor.vue` 是**规则组列表容器**（新增/排序/删除组、双视图切换、整体应用），`RuleGroupCard.vue` 是单组卡片（组元信息 + 该组专属双栏预览 + 该组规则列表）。卡片是受控组件，不持有组数据，改动一律 `emit('update:group')` 回写。组数组的增删改序在 `features/request-body-rules/groupOperations.ts`，其中 `order` 必须与数组下标同步 —— 运行时按 `order` 排序执行，只换位置不改 `order` 会让界面顺序与执行顺序分叉，该约束有单测钉住。

JSON 值输入统一走 `JsonValueInput.vue`（左侧类型档位 + 右侧按类型切换的控件），映射逻辑在 `features/request-body-rules/jsonValueEditing.ts`。「设置字段值」与「条件 · 等于」共用它 —— 两者面对的都是「用户想表达哪个 JSON 值」，各写一套会让同一语义在两处分叉（条件侧原先用宽松解析，导致「等于 null」只能靠留空试出来、且无法表达「等于字符串 "10"」）。两个不显然的点：档位在值暂时无法表达它时要独立存在（切到「数值」时输入框为空、值写不回，档位会被反推弹回旧类型），以及解析成功后**文本与规范形式不一致时仍保留草稿**（否则 `12.` 被规范化成 `12`，小数点根本打不出来）。

预览由后端计算，**不要在前端重建引擎** —— 那份 TS 引擎已删除，理由见 AGENTS.md「引擎只有一份实现，预览走接口」。异步取值的防抖、请求竞态、加载态与失败降级都在 `features/request-body-rules/preview.ts` 的 `createPreviewScheduler`，竞态守卫按请求序号而非「是否有在途请求」判断。

`WireProtocol` 统一从 `@/types/protocol` 导入（同时提供 `ALL_WIRE_PROTOCOLS`、`WIRE_PROTOCOL_LABELS` 与 `isWireProtocol`），字面量与后端枚举常量名逐字一致，不要在各处重写联合类型。

## 自研组件契约

修改热力图、范围滑块、用量柱图或折线图前，先读对应目录 README；改变 Props、事件、类型、动画或
barrel 导出时同步更新文档：

- [`components/heatmap/README.md`](../../frontend/src/components/heatmap/README.md)
- [`components/rangeslider/README.md`](../../frontend/src/components/rangeslider/README.md)
- [`components/usagechart/README.md`](../../frontend/src/components/usagechart/README.md)
- [`components/usageline/README.md`](../../frontend/src/components/usageline/README.md)

## 手动验证

四个零依赖 Node 脚本，用法见各自 README：

| npm script | 模拟谁 | 端口 |
|---|---|---|
| `mock:stream` | OpenAI 流式上游 | 8081 |
| `mock:nonstream` | OpenAI 非流式上游 | 8082 |
| `mock:anthropic` | Anthropic 上游（流式与非流式共用端点） | 8083 |
| `mock:cosp` | COSP 自己，供 Copilot 直连以嗅探入站请求头 | 11333 |
