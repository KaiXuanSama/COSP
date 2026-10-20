# ADR-0008: 出站鉴权头由供应商配置决定，不按上游协议分派

- **状态**：Accepted
- **日期**：2026-10-05 前后（schema V14）
- **取代**：取代「按 `WireProtocol` 分派鉴权头名」的旧做法
- **相关**：[AUTH_HEADER_ASSEMBLY.md](../architecture/AUTH_HEADER_ASSEMBLY.md)、
  [全局常量三处一致](../architecture/AUTH_HEADER_ASSEMBLY.md)

## 背景

出站鉴权头曾经**按上游协议**分派：`MESSAGES` 发 `x-api-key`，`CHAT` / `RESPONSES` 发
`Authorization: Bearer`。依据是「Anthropic 官方用 `x-api-key`」。

实测证明这个依据不成立：

| 事实 | 说明 |
|---|---|
| Claude CLI 的头名由**凭据环境变量**决定，与协议无关 | `ANTHROPIC_API_KEY` → `x-api-key`；`ANTHROPIC_AUTH_TOKEN` → `Authorization: Bearer` |
| cc-switch 默认走后者 | 与 cc-switch 对接时下游发的是 `Authorization` |
| 部分中转站只认 `Authorization` | anyrouter 实测：改发 Bearer 后响应由 503 变 429（通过鉴权层、被配额挡住） |

**根因不是「选错了那一档」**，而是这个选择本身不该由代码替用户做。它与项目既有的
「会进入出站请求的参数都建模成 `{值, 模式}`」是同一条原则——单个值无法表达
「下游自己做了选择怎么办」。

## 决策

**出站鉴权头名成为供应商级配置**，`provider_config.auth_header`（V14 新增纯加列）。

### 数据模型

```sql
auth_header TEXT NOT NULL DEFAULT '{"mode":"DOWNSTREAM","header":"AUTHORIZATION"}'
    CHECK (json_valid(auth_header))
```

JSON 形态 `{mode, header}`，**值用枚举名**而非头名字面量：

| 维度 | 取值 | 含义 |
|---|---|---|
| `mode` | `DOWNSTREAM` | 下游恰好带了一种就沿用那一种 |
| | `CONFIGURED` | 始终用配置的 `header` |
| `header` | `AUTHORIZATION` | `Authorization: Bearer <供应商 key>` |
| | `X_API_KEY` | `x-api-key: <供应商 key>` |

### 为什么存枚举名而非头名字面量

头名**大小写不敏感**且存在拼写变体（`X-Api-Key` / `x-api-key`）。存字面量等于把
「第几处写的是哪种拼写」变成配置的一部分。头名字面量只在
`AuthHeaderSetting.Header.headerName()` 一处映射。

### 三处必须逐字一致

`schema.sql` 的 DEFAULT、V14 迁移的 DEFAULT、`AuthHeaderSetting.DEFAULT_AUTH_HEADER_JSON`
**必须逐字一致**。这是本项目最容易踩的坑之一（history：三处不一致会导致
「新库默认值 ≠ 迁移后默认值」）。

## 后果

**正面**

- 用户可自行匹配任何上游的头名要求，不必等项目发版
- 与「会进入出站请求的参数都建模成 `{值, 模式}`」的统一原则一致
- `DOWNSTREAM` 模式让代理能**沿用下游的选择**（如 cc-switch 发 Bearer 就顺着用）

**负面**（知情成本）

- **三处常量必须同步维护**（schema.sql / 迁移 / Java 常量）。这是已知的维护税，
  已在 `AUTH_HEADER_ASSEMBLY.md` 与 AGENTS.md 双处标注
- **配置错误的表现是上游 401/403**，而不是明确的「头名配错了」提示
- `DOWNSTREAM` 模式的语义依赖下游真的带了头。若下游没带任何认证头，该模式会退化为
  使用供应商 key 走默认头——这个回退路径需要测试钉住

## 备选方案

### 方案 A：继续按上游协议分派（`MESSAGES` → `x-api-key`）

- 描述：保持简单，代码里一个 switch 搞定
- 拒绝理由：实测反例充分——Claude CLI 用 `ANTHROPIC_AUTH_TOKEN` 时发 Bearer，
  anyrouter 只认 Bearer。按协议分派会让这些场景全部失败，且用户无法自救

### 方案 B：让用户在请求头规则里配置鉴权头

- 描述：不新增字段，用既有的请求头规则覆盖 `Authorization` / `x-api-key`
- 拒绝理由：请求头规则需要用户**同时知道要删掉默认头**（否则会发两个）。
  这是两步操作，且默认头的行为不透明。专门的配置项让「用哪个头」成为一个显式的、
  可校验的选择

### 方案 C：总是发两个头（`Authorization` + `x-api-key`）

- 描述：两个都发，总有一个能用
- 拒绝理由：部分上游对**多余的头**会报错或行为异常；且会把供应商 key 暴露在两种头里，
  增加日志泄露面。更重要的是——这不是一个好代理该做的隐式猜测

## 参考

- 完整设计说明（含入站侧、实测记录、三处一致约束）：[AUTH_HEADER_ASSEMBLY.md](../architecture/AUTH_HEADER_ASSEMBLY.md)
- 迁移规则：[数据库迁移 Skill](../../.github/skills/cosp-schema-migration-skill/SKILL.md)
- 规则引擎（另一条用户自助路径）：[ADR-0007](adr-0007-request-rules-over-provider-classes.md)
