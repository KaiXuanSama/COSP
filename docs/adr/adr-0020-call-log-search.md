# ADR-0020: 调用日志检索扩展现有列表端点，检索模式下前端忽略 SSE 信号

- **状态**：Accepted
- **日期**：2026-11-05
- **取代**：无
- **相关**：[RFC：调用日志检索](../working/rfcs/2026-11-05-call-log-search.md)、
  `api/CallLogController.java`、`infrastructure/persistence/ApiCallLogRepository.java`（`findLogs`）、
  `frontend/src/views/UsageLog.vue`

## 背景

管理后台的调用日志只能从最新往最旧线性翻页，排查历史问题时需要按时间、状态、供应商、模型检索。

现有约束：

- `GET /config/api/logs` 同时服务调用者视角（`CallLog.vue`）、消费者视角（`UsageLog.vue`）和
  live-test 的 `recentLogs`。它们依赖「无参数 = 最新若干条」的语义。这个端点本身就是由两个
  端点合并而来，合并的理由是主表、游标、排序和分页格式完全相同。
- 列表实时更新靠 `GET /config/api/logs/stream` 的纯信号 SSE：前端收到信号后拉第一页，把
  `id > knownMaxId` 的行插到表顶。这套逻辑默认列表内容就是「最新的 N 条」。检索结果不满足这个前提，
  把新行插进去会让列表出现不符合条件的行，或让游标和已渲染范围错位。
- SSE 连接受 `SseConnectionGate` 总连接数限制，重连有窗口期。
- `api_call_log` 存的是 `provider_key`（路由用小写标识），不是 `provider_config.display_name`；
  供应商删除后日志行仍保留原 key。

## 决策

**在现有 `GET /config/api/logs` 上增加可选检索参数；前端进入检索模式后保持 SSE 连接，但丢弃收到的信号。**

1. **端点**：参数 `from`、`to`、`status`（可重复）、`provider` + `providerMatch`、`model` + `modelMatch`
   全部可选。一个检索参数都不传时，SQL、响应格式与改造前完全相同。参数非法返回 400，
   响应体 `{ ok: false, message }`，与 `RuntimeConfigController` 一致。
2. **游标不变**：仍是 `id < cursor`，响应仍是 `{items, nextCursor, hasMore, pageSize}`。
   服务端不保存检索条件，前端翻页时必须带上与首屏相同的条件。
3. **供应商按 `provider_key` 匹配**，不按显示名。列表上显示的就是 key，不需要 JOIN，已删除的供应商也能查到。
4. **状态同时接受状态类与精确码**：`2xx` / `4xx` / `5xx` 与任意整数（含 `-1`）。多个取值之间是 OR。
5. **匹配语义**：全字匹配用 `=`，区分大小写；部分匹配用 `LIKE ... ESCAPE '\'`，转义 `%`、`_`、`\`，
   ASCII 不区分大小写（SQLite 默认行为）。
6. **前端两种模式**：实时模式按现状增量插入；检索模式下 SSE 连接不断开，信号直接丢弃，
   只有手动刷新会重新拉取。退出检索时整表重载第一页。

## 后果

**正面**

- 响应格式、游标、排序只有一份实现，不接入检索的 `CallLog.vue` 与 `recentLogs` 无需改动。
- 检索结果不会被实时流插入不符合条件的行。
- 不需要 schema 变更或新索引：本机 27,336 行实测最坏情况（部分匹配无命中，全表扫）142 ms。

**负面**

- 检索模式下新日志不会出现，用户需要手动刷新或退出检索。界面必须明确提示这一点。
- 前端切换模式时要处理在途请求的结果作废（列表代次），这是新增的状态复杂度。
- 游标继续依赖「id 与 `created_at` 同序」；服务器时钟回拨时排序可能错位。现状已承担同样风险，本决策不扩大它。
- 用户在设置页看到的是显示名（如 `StepFun`），检索要输入 key（`stepfun`）；需要前端提示文案和候选列表弥补。

## 备选方案

### 新增 `GET /config/api/logs/search`
- 描述：检索单独一个端点。
- 拒绝理由：两个端点的主表、游标、排序、响应格式相同，后续容易分叉，这正是此前合并两个列表端点时要消除的问题。

### `POST /config/api/logs/search`，条件放请求体
- 描述：用 JSON 表达条件。
- 拒绝理由：当前条件都是标量或短列表，查询参数足够；读操作用 POST 不符合习惯。

### 检索模式下断开 SSE，退出时重连
- 描述：检索期间不占连接。
- 拒绝理由：重连要重新占用 `SseConnectionGate` 名额并经历重连窗口；断开和保持对检索结果的效果相同，保持连接更简单。

### 检索模式下按条件在前端过滤新行后插入
- 描述：收到信号后拉最新页，前端用相同条件过滤再插入。
- 拒绝理由：前端要重写一份与 SQL 一致的匹配逻辑（`LIKE` 大小写规则、时间闭区间、状态类展开），
  两份实现必然漂移；带 `to` 上限的检索本来也不该出现新行。

### 按 `provider_config.display_name` 匹配供应商
- 描述：JOIN 供应商表按显示名查。
- 拒绝理由：已删除供应商查不到；列表上展示的是 key，按显示名查会让「看到的」和「能搜的」不一致。

## 参考

- [RFC：调用日志检索](../working/rfcs/2026-11-05-call-log-search.md) §1 现状调研、§1.3 实测数据
- `src/main/java/com/kaixuan/copilot_ollama_proxy/api/CallLogController.java`
- `src/main/java/com/kaixuan/copilot_ollama_proxy/infrastructure/persistence/ApiCallLogRepository.java`
- `frontend/src/features/call-log/sync.ts`、`frontend/src/features/call-log/pagination.ts`
