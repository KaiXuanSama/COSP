# RFC：调用日志检索

- **日期**：2026-11-05（立项日）
- **状态**：已定稿，实施中（[阶段 1](#call-log-search-stage-1) 已完成）
- **相关 ADR**：[ADR-0020：调用日志检索扩展现有列表端点，检索模式下前端忽略 SSE 信号](../../adr/adr-0020-call-log-search.md)
- **分支**：`feat/log-search`

## 背景

> **需求复述**：调用日志目前只能从最新往最旧线性翻页，排查历史问题不方便。需要给日志列表端点加检索条件：
> 时间范围（精确到秒）、状态（多选）、供应商名称（全字匹配 / 部分匹配）、模型名（全字匹配 / 部分匹配）。
> 前端因此有两种状态：「无检索条件的最新日志查看」和「应用了检索规则的日志查看」。
> 后者不再根据后端 SSE 做增量显示，以免列表状态不一致。

## 1. 现状调研

### 1.1 后端

| 位置 | 现状 |
|---|---|
| `api/CallLogController.java` | `GET /config/api/logs?cursor&pageSize`（默认 20）；`GET /config/api/logs/{id}`；`GET /config/api/logs/stream`（SSE） |
| `application/logging/CallLogQueryService.java` | 只做透传，把 JDBC 调用桥接到 `boundedElastic` |
| `infrastructure/persistence/ApiCallLogRepository.findLogs` | 单条 SQL：`api_call_log` LEFT JOIN 最新一行 `api_call_usage`；首屏 `ORDER BY created_at DESC, id DESC LIMIT ?`，翻页加 `WHERE l.id < ?`；`pageSize` 钳制到 [1, 100] |
| `buildPage` | 返回 `{items, nextCursor, hasMore, pageSize}`；`hasMore` 用「本页恰好取满」判定 |
| SSE | 每次落库流程收尾发一个 `event: log` 信号帧，不带内容；15 秒注释帧心跳；受 `SseConnectionGate` 总连接数限制 |

`api_call_log` 中与检索相关的列和索引（`schema.sql`）：

- `provider_key VARCHAR(30)`：落库时取 `ctx.provider().providerKey()`，即路由用的小写标识（如 `stepfun`），**不是** `provider_config.display_name`（如 `StepFun`）。
- `model_name VARCHAR(100)`：落库时取**出站请求体**的 `model`（`UpstreamCallRunner.modelOf`），即上游模型名，不带 `[stepfun] ` 前缀。
- `status_code INTEGER`：上游 HTTP 状态码；`-1` 表示非 HTTP 异常（连接、DNS、TLS、截断、空响应耗尽等，见前端约定）。
- `created_at TEXT`：`strftime('%Y-%m-%dT%H:%M:%S','now','localtime')`，**服务器本地时间、无时区、精确到秒**。
- 索引：`idx_api_call_log_created_id(created_at DESC, id DESC)`、`idx_api_call_log_provider_created_id(provider_key, created_at DESC, id DESC)`。`status_code`、`model_name` 无索引。

### 1.2 前端

- `views/CallLog.vue`（调用者视角）与 `views/UsageLog.vue`（消费者视角）**都**调用 `fetchLogs(cursor, pageSize)`，pageSize 均为 50。
- 两者都订阅 `/logs/stream`，收到信号后经 `createCoalescingSync` 串行拉第一页，把 `id > knownMaxId` 的行插到表顶，再用 `prependWithCursorShift` 砍尾并前移游标。CallLog 逐条播放入场动画（`enterQueue` + `flushEnterQueue`），UsageLog 整批插入。
- `CallLog.vue` 计划废弃（2026-11-05 用户确认），前端检索只接入 `UsageLog.vue`。UsageLog 的状态有：`rows`、`nextCursor`、`hasMore`、`knownMaxId`、新行高亮 `freshIds`（1.4 秒后按批清除）、行内展开 `expandedId` 与详情缓存 `detailCache`。
- 两个视图的列表行都只显示 `HH:MM:SS`（`formatTime` 丢掉了日期）。跨天检索时用户无法分辨日期，这一点需要随检索一起处理。
- `tools/live-test/lib.js` 的 `recentLogs` 也调用 `/config/api/logs?pageSize=n`，依赖「无参数 = 最新 N 条」的现有语义。

### 1.3 本机数据实测

只读查询本机 `admin.db`（约 3.7 GB，`api_call_log` 27,336 行，2026-08-06 ~ 2026-11-05），用 Node 内置 `node:sqlite` 执行，取 50 行：

| 条件 | 查询计划 | 耗时 |
|---|---|---|
| 时间范围 2 天 | SEARCH `idx_api_call_log_created_id` (created_at 范围) | 0.3 ms |
| 供应商精确 `= 'stepfun'` | SEARCH `idx_api_call_log_provider_created_id` | 0.7 ms |
| 供应商 `LIKE '%step%'` | SCAN `idx_api_call_log_created_id` | 14.6 ms |
| 状态 `IN (429, 503)` | SCAN `idx_api_call_log_created_id` | 13.8 ms |
| 模型 `LIKE` 无命中（最坏情况，全表扫） | SCAN `idx_api_call_log_created_id` | 142 ms |
| 状态 + 时间/id 复合游标 | SEARCH `idx_api_call_log_created_id` (created_at<?) | 24 ms |

另外验证了相邻 id 的 `created_at` 没有倒序（0 对），即本库中 id 顺序与时间顺序一致。

状态码分布：200 占 24,185 行；其余为 500/-1/400/502/429/503/403/401/504/524/522/404/402/530/520，共 16 种取值。

结论：当前数据量下，即使最坏情况的全表扫描也在 150 ms 内，**第一版不需要新增索引**。数据量增长一个数量级后再看 `status_code` 索引。

## 2. 范围

**做**：

- 扩展 `GET /config/api/logs`，增加可选检索参数，游标分页语义不变。
- 前端日志页增加检索面板，区分「实时模式」与「检索模式」两种状态。
- 列表行在非当天时显示日期。

**不做**：

- 不新增检索结果总数（`COUNT` 在部分匹配下要全表扫，且需求没有提）。
- 不按请求体/响应体内容检索（载荷会被瘦身清空，且全文检索成本高）。
- 不改 SSE 端点和信号语义。
- 不做 schema 变更。

## 3. 后端设计

### 3.1 方案比较

| 方案 | 优点 | 缺点 |
|---|---|---|
| **A. 扩展现有 `GET /config/api/logs`，加可选查询参数**（推荐） | 响应形状、游标、排序只有一份实现；无参数时行为完全不变，`recentLogs` 与 UsageLog 不受影响 | 控制器方法签名变长 |
| B. 新增 `GET /config/api/logs/search` | 与原端点隔离 | 两个端点做同一件事，后续很容易分叉；这正是当初合并调用者/消费者两个端点的原因（见 `findLogs` 注释） |
| C. `POST /config/api/logs/search`，条件放 body | 条件复杂时更容易扩展 | 当前条件都是标量或短列表，用不上；查询语义用 POST 不符合习惯，也不能直接分享 URL |

### 3.2 参数

全部可选；不传任何检索参数时等价于现状。

| 参数 | 类型 | 语义 |
|---|---|---|
| `from` | `yyyy-MM-ddTHH:mm:ss` | `created_at >= from`（含） |
| `to` | `yyyy-MM-ddTHH:mm:ss` | `created_at <= to`（含）。精确到秒，闭区间与「到 12:00:00 为止」的直觉一致 |
| `status` | 可重复，如 `status=2xx&status=429&status=-1` | 多选，条件之间取 OR。取值见 §3.3 |
| `provider` | 字符串，≤ 30 字符 | 匹配 `provider_key` |
| `providerMatch` | `exact`（默认）/ `contains` | 全字匹配 / 部分匹配 |
| `model` | 字符串，≤ 100 字符 | 匹配 `model_name` |
| `modelMatch` | `exact`（默认）/ `contains` | 全字匹配 / 部分匹配 |

不同参数之间取 AND。长度上限与列宽一致，超出直接 400，因为超过列宽的值不可能命中。

### 3.3 状态取值

推荐同时接受两类 token：

- **状态类**：`2xx`、`4xx`、`5xx`，展开为 `status_code BETWEEN 200 AND 299` 等。与前端 `getStatusClass` 的配色分组一致。
- **精确码**：任意整数，包括 `-1`（非 HTTP 异常）和 `429`、`503` 这类排查时常单独看的码。

只给状态类时，用户无法单独筛出 429；只给精确码时，前端多选框要列出十几个码。两类并存的成本只是解析时多一个分支。前端多选的选项来源见 §7 Q1。

### 3.4 匹配语义

- **全字匹配**：`provider_key = ?`，区分大小写。供应商精确匹配能用上 `idx_api_call_log_provider_created_id`。
- **部分匹配**：`LIKE ? ESCAPE '\'`，参数为 `%` + 转义后的输入 + `%`，`%`、`_`、`\` 都要转义，否则用户输入 `gpt_5` 时 `_` 会被当成通配符。SQLite 的 `LIKE` 默认只对 ASCII 不区分大小写，供应商标识和模型名基本是 ASCII，正好符合「部分匹配更宽松」的直觉。
- 空字符串或只有空白的 `provider` / `model` 视为未传（先 trim）。

两个容易让用户困惑的点，前端需要用提示文案说明：

1. 供应商匹配的是 `provider_key`（列表上显示的就是它），不是设置页的显示名。按显示名匹配需要 JOIN `provider_config`，而且已删除的供应商查不到。见 §7 Q2。
2. 模型匹配的是上游模型名，不带 `[provider] ` 前缀。

### 3.5 SQL 与游标

在 `findLogs` 的现有 SQL 上按条件拼接 `WHERE` 子句，值全部走 `?` 占位符：

```sql
SELECT ...现有列... FROM api_call_log l LEFT JOIN api_call_usage u ON ...
WHERE 1=1
  [AND l.created_at >= ?]
  [AND l.created_at <= ?]
  [AND (l.status_code BETWEEN 200 AND 299 OR l.status_code IN (?, ?))]
  [AND l.provider_key = ? | AND l.provider_key LIKE ? ESCAPE '\']
  [AND l.model_name = ? | AND l.model_name LIKE ? ESCAPE '\']
  [AND l.id < ?]                        -- 游标
ORDER BY l.created_at DESC, l.id DESC LIMIT ?
```

**游标沿用 `id < cursor`**，不改成 `(created_at, id)` 复合游标。理由：

- 现状本来就是 `ORDER BY created_at DESC, id DESC` 配 `id < ?` 游标，它成立的前提是 id 与 `created_at` 同序。本库实测成立（§1.3），检索只是多加过滤条件，不改变这个前提。
- 复合游标要改响应格式（`nextCursor` 变成两个值），前端两个视图都要跟着改。

这个前提在服务器时钟回拨时会失效（`created_at` 用本地时间），但现状已经承担了同样的风险，本 RFC 不扩大它。

检索条件不进游标：服务端无状态，前端翻页时必须带上与首屏相同的条件。

### 3.6 分层

- 新增 `application/logging/CallLogFilter`（record）：持有解析后的条件，构造时校验。仓储方法签名改为 `findLogs(CallLogFilter filter, Long cursor, int pageSize)`，`CallLogFilter.NONE` 表示无条件。
- 参数解析与校验放在控制器边界。非法参数返回 400，响应体 `{ ok: false, message }`，`message` 指明哪个参数错在哪。项目没有 `@ControllerAdvice`，参考 `RuntimeConfigController` 的 `ResponseEntity.badRequest()` 写法，`listLogs` 返回类型因此改为 `Mono<ResponseEntity<Map<String, Object>>>`。
- 校验项：时间格式能被 `LocalDateTime.parse` 解析且秒级（不接受毫秒和时区后缀）；`from <= to`；`status` token 合法且最多 20 个；匹配模式只能是 `exact` / `contains`；字符串长度不超过列宽。

## 4. 前端设计

### 4.1 两种状态

| | 实时模式 | 检索模式 |
|---|---|---|
| 进入 | 页面加载；清空检索条件 | 点击「检索」且条件非空 |
| 首屏 | `fetchLogs` 无条件 | 带条件 |
| 翻页 | 无条件 + 游标 | 同一组条件 + 游标 |
| SSE 信号 | 增量插入（现状） | **忽略** |
| 手动刷新 | 重新拉第一页 | 用当前条件重新拉第一页 |

检索模式下 **SSE 连接保持不断开**，只是丢弃信号。断开再重连要重新占用 `SseConnectionGate` 名额，还会产生一段重连窗口；保持连接也让「退出检索」后不需要等连接建立。

退出检索时整表重新加载第一页并重置 `knownMaxId`，不尝试把检索期间的新行合并进旧列表。

### 4.2 并发竞态

切换模式时可能有请求还在路上，这是本次最容易出错的地方：

1. 实时模式下 `syncLatest` 正在拉取，用户点了「检索」。拉取回来后会把无条件的最新行插进检索结果。
2. 实时模式的 `loadMore` 在途，用户切到检索模式，无条件的下一页被追加到检索结果末尾。
3. 用户连续两次修改条件并检索，先发的请求后返回，覆盖了新结果。
4. 切换前展开的行在新列表里可能不存在，`expandedId` 与 `freshIds` 残留。

统一用「列表代次」解决：每次进入实时模式、进入检索模式或修改条件，代次加一，同时清空 `freshIds`、收起展开行（与现有 `refresh` 的做法一致）。所有拉取在发起时记录代次，返回时代次不一致就丢弃结果。SSE 处理函数在检索模式下直接返回，不进入 `createCoalescingSync`。

这套状态机抽成 `features/call-log/` 下的纯逻辑模块（例如 `searchMode.ts`），用 Vitest 按受控时序覆盖上述四种竞态，与现有 `sync.ts` 的做法一致。

### 4.3 检索面板

- 时间：Naive UI `NDatePicker` 的 `datetimerange`，格式 `yyyy-MM-dd HH:mm:ss`，提交时转成 `yyyy-MM-ddTHH:mm:ss` 本地时间字符串，**不经过 `Date.toISOString()`**（那会转成 UTC，和库里的本地时间对不上）。
- 状态：多选，选项分两组。状态类组：`2xx 成功`、`4xx 客户端错误`、`5xx 服务端错误`、`非 HTTP 异常（-1）`。常见状态码组：`400`、`401`、`403`、`429`、`500`、`502`、`503`、`504`。同时选中 `5xx` 和 `503` 不报错，后端取 OR 后结果相同。选项是前端常量，不从后端拉取。
- 供应商、模型：输入框 + 「全字 / 部分」切换，默认「全字」。供应商输入用已配置供应商的 `providerKey` 做候选（`useProviderStore`），但允许输入不在列表中的值，以便查已删除的供应商。输入框提示文案写明「供应商标识，如 stepfun」「上游模型名，不含 [供应商] 前缀」。
- 检索模式下在列表顶部显示明显的「检索中」标记和「清除条件」入口，让用户知道列表不会自动更新。
- 列表行时间：当天只显示 `HH:mm:ss`，非当天显示 `MM-dd HH:mm:ss`（跨年时带年份）。

### 4.4 API 封装

`fetchLogs(cursor, pageSize, filter?)`，`filter` 为空时不发任何检索参数，保证 CallLog 等未改造的调用方行为不变。`status` 用重复参数形式，需要给 axios 配置 `paramsSerializer`（默认会序列化成 `status[]=`）。条件类型放在 `features/call-log/`，不在 `api/` 复制 DTO。

## 5. 测试与验证计划

**后端**（`src/test/java`，遵循 java-tests 约定）：

- 仓储：在 `ApiCallLogRepositoryCursorTests` 的数据构造方式上新增检索测试：时间闭区间边界（恰好等于 from/to 的行要命中）、状态类与精确码混合、`-1`、精确/部分匹配、`%` `_` 转义、多条件 AND、带条件翻页不重复不遗漏、无条件时结果与改造前一致。
- 控制器：参数解析和 400 场景（格式错误、`from > to`、未知 status token、超长字符串、非法 match 模式）。
- 全量：`./mvnw surefire:test`。不涉及 schema，不需要跑迁移测试。

**前端**：

- `searchMode.ts` 的 Vitest：§4.2 的四种竞态、模式切换后 SSE 信号被忽略、退出检索后整表重载。
- `npm run build`（`vue-tsc` 类型检查）与 `npm run test:run`。

**实机**：启动 COSP，用 mock 上游制造 200 / 429 / 5xx / -1 几类日志，在检索模式下持续发请求，确认列表不变；退出检索后新行正常出现。

## 6. 文档影响

- 本 RFC 进入 `working/rfcs/README.md` 索引；端点与 SSE 行为决策晋升 [ADR-0020](../../adr/adr-0020-call-log-search.md)，进入 `docs/README.md` 的 ADR 表。
- 实施完成后：`CallLogController.listLogs`、`ApiCallLogRepository.findLogs` 的注释补充检索参数语义；`UsageLog.vue` 头部注释说明两种模式。
- `CallLog.vue` 本次不接入检索，继续用无参数的 `fetchLogs`，行为不变。废弃它是另一项工作，不在本 RFC 范围内。
- 本次不涉及 schema、协议翻译、pipeline，`architecture/` 与 `reference/contracts/` 无需更新。

## 7. 决策记录

2026-11-05 用户确认全部按推荐执行。端点形式（Q2、Q3、Q6）与检索模式下的 SSE 行为（Q4）属于对外行为契约，已晋升 ADR-0020；其余是实施范围选择，只记录在本 RFC。

| # | 问题 | 结论 |
|---|---|---|
| Q1 | 状态多选的选项：固定的「2xx / 4xx / 5xx / 非 HTTP 异常(-1)」，还是额外列出常见精确码（429、500、502、503 等），还是两者都有 | 两者都有：先列四个状态类，再列常见精确码。后端两类都接受（§3.3） |
| Q2 | 「供应商名称」匹配 `provider_key` 还是设置页的 `display_name` | `provider_key`：列表上显示的就是它，无需 JOIN，已删除的供应商也能查 |
| Q3 | 扩展现有端点还是新增端点 | 扩展现有 `GET /config/api/logs`（§3.1 方案 A） |
| Q4 | 检索模式下 SSE 连接保持并忽略信号，还是断开 | 保持并忽略（§4.1） |
| Q5 | 检索功能覆盖哪些页面：只做调用者视角 `CallLog.vue`，还是消费者视角 `UsageLog.vue` 同时接入 | 后端一次做完；前端只做 UsageLog。最初推荐先做 CallLog，2026-11-05 用户调整：CallLog 计划废弃，改为 UsageLog |
| Q6 | 部分匹配是否需要区分大小写 | 不区分（SQLite `LIKE` 默认行为）；全字匹配区分 |
| Q7 | 检索模式下是否需要提示「检索期间有 N 条新日志」 | 第一版不做，避免引入计数状态；有需要再加 |

<a id="call-log-search-plan"></a>

## 8. 实施计划

按依赖顺序分三个阶段，每个阶段结束时对应测试必须通过。

### 阶段 1：后端检索

1. 新增 `application/logging/CallLogFilter`（record）：字段 `from`、`to`（`LocalDateTime`，可空）、`statusClasses`（`2xx`/`4xx`/`5xx` 集合）、`statusCodes`（整数集合）、`provider`、`providerMatch`、`model`、`modelMatch`。提供 `CallLogFilter.NONE` 与 `isEmpty()`。
2. 新增解析入口（`CallLogFilter.parse(...)` 或独立的 `CallLogFilterParser`），输入原始查询参数，输出 filter 或抛 `IllegalArgumentException`（带参数名的消息）。校验项见 §3.6。
3. `ApiCallLogRepository.findLogs(CallLogFilter, Long cursor, int pageSize)`：按 §3.5 拼接条件，值全部走占位符，`LIKE` 参数做转义。保留 `findLogs(Long, int)` 委托到 `NONE`，避免改动现有测试与调用方。
4. `CallLogQueryService.listLogs` 透传 filter。
5. `CallLogController.listLogs` 接收新参数，`status` 用 `@RequestParam(required = false) List<String>`；返回类型改为 `Mono<ResponseEntity<Map<String, Object>>>`，`IllegalArgumentException` 转 400。
6. 测试：§5 后端各项。`ApiCallLogRepositoryCursorTests` 中已有用例不改，新用例放新测试类（如 `ApiCallLogRepositorySearchTests`），解析校验放 `CallLogFilterTests`。

### 阶段 2：前端状态机与 API

1. `features/call-log/searchFilter.ts`：检索条件类型、状态选项常量、条件 → 查询参数的转换（含本地时间格式化，不经 `toISOString()`）、「条件是否为空」判断。
2. `features/call-log/searchMode.ts`：模式与列表代次。对外提供「进入检索」「退出检索」「发起拉取时取代次」「结果返回时判断是否作废」「SSE 信号是否应处理」。不依赖 Vue 与 DOM。
3. `api/index.ts`：`fetchLogs(cursor, pageSize, params?)`，配置 `paramsSerializer` 让数组序列化为重复参数（`status=2xx&status=429`）。不传 `params` 时请求与现状相同。
4. Vitest：`searchFilter.spec.ts`（参数转换、本地时间、空条件）、`searchMode.spec.ts`（§4.2 四种竞态、检索模式忽略信号、退出后代次变化）。

### 阶段 3：UsageLog 页面接入

1. 在 `UsageLog.vue` 卡片头部加检索入口，面板按 §4.3 实现；检索面板拆到 `components/calllog/CallLogSearchPanel.vue`（UsageLog.vue 已有 900 多行），以受控组件方式回写条件。
2. `loadFirstPage`、`loadMore`、`refresh`、`syncLatest` 接入代次检查；SSE `log` 处理函数在检索模式下直接返回。
3. 进入或退出模式时清空 `freshIds`、收起展开行，退出时整表重载并重置 `knownMaxId`。`detailCache` 按 id 缓存，内容不随模式变化，可以保留。
4. 检索模式下列表顶部显示「检索中，列表不会自动更新」与「清除条件」。检索结果为空时空状态文案改为「没有符合条件的消费记录」。
5. `formatTime` 改为当天 `HH:mm:ss`、非当天 `MM-dd HH:mm:ss`、非当年 `yyyy-MM-dd HH:mm:ss`。这个函数抽到 `features/call-log/` 并补单测。
6. 验证：`npm run test:run`、`npm run build`；最后跑一次后端全量（`./mvnw test` 会带前端构建）；按 §5 做实机检查。

### 完成标准

- 不带检索参数时，`/config/api/logs` 的响应与改造前逐字段一致，CallLog 与 live-test 行为不变。
- 检索模式下持续产生新日志，列表内容和游标都不变化。
- 在任意时刻切换模式或修改条件，列表里不出现不符合当前条件的行。
- 实施完成后在本 RFC 追加实施记录，状态改为「已定稿且实施完毕」，并按文档门禁输出报告。

<a id="call-log-search-stage-1"></a>

## 9. 实施记录

### 9.1 阶段 1：后端检索（2026-11-05）

**改动**

- 新增 `application/logging/CallLogFilter.java`：检索条件 record，内含 `MatchMode`（`EXACT` / `CONTAINS`）与 `StatusClass`（`2xx` / `4xx` / `5xx`）；`parse(...)` 负责解析与校验，非法时抛带参数名的 `IllegalArgumentException`。
- `ApiCallLogRepository`：新增 `findLogs(CallLogFilter, Long, int)`，原 `findLogs(Long, int)` 委托到 `CallLogFilter.NONE`；新增 `escapeLike`。
- `CallLogQueryService.listLogs` 增加 filter 参数。
- `CallLogController.listLogs` 接收 7 个检索参数，返回类型改为 `Mono<ResponseEntity<Map<String, Object>>>`，非法参数返回 400 `{ ok: false, message }`。成功响应体不变。

**与计划的偏差**

- 文本长度上限统一为 200，没有按列宽（30 / 100）拒绝。SQLite 不强制 `VARCHAR(n)`，库里可能有超过声明长度的值，按列宽拒绝会让这些行查不到。§3.2 中「超出列宽直接 400」的说法以此为准。
- 状态精确码限定为 `-1` 或 100~599，其它整数返回 400。单个 `status` 参数内也接受逗号分隔，方便手写 URL。
- 时间只接受 `yyyy-MM-ddTHH:mm:ss`（`ResolverStyle.STRICT`，拒绝 2 月 30 日等无效日期）。
- 匹配方式参数大小写不敏感（`Contains` 等价于 `contains`）。

**测试**

- `CallLogFilterTests`（16 条）：空白即未传、时间格式与边界、状态类与精确码、逗号分隔、上限、匹配方式。
- `ApiCallLogRepositorySearchTests`（10 条）：`NONE` 与旧重载结果逐字段相同、时间闭区间、状态 OR、`-1` 不属于任何状态类、全字匹配区分大小写、部分匹配不区分、`_` `%` `\` 转义、多条件 AND、带条件翻页连续。
- `CallLogControllerSearchTests`（3 条）：无参数传 `NONE`、重复 `status` 参数解析、5 类非法参数返回 400 且不调用服务层。
- 默认全量 `mvnw.cmd -o surefire:test`：**2039 通过，0 失败 / 错误**。不涉及 schema，未跑迁移测试。
- 未做：实机 HTTP 验证（留到阶段 3 与前端一起做）。
