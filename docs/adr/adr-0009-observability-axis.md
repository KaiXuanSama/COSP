# ADR-0009: 观测轴的判据与单向依赖

- **状态**：Accepted
- **日期**：2026-09-19 前后（观测轴归拢完成）
- **取代**：（无）
- **相关**：[pipeline/README.md](../../src/main/java/com/kaixuan/copilot_ollama_proxy/pipeline/README.md)
  （四轴框架权威说明，在代码里）、[REQUEST_PIPELINE.md](../architecture/REQUEST_PIPELINE.md)

## 背景

项目按「四轴框架」组织顶层包，其中**观测轴**（`observability/`）的判据最难厘清。

归拢前发生过**三次误判**：

1. 把 `UpstreamCallReporter` 当成主干的一部分
2. 把 `api/shared/` 当成观测层
3. 把控制器的链当成观测

根因是一个**词义歧义**：中文「观察」既指「让人看见」（观测），也指「代码读数据决定怎么走」
（运行时判定）。两者在实现上都是"读数据"，但在架构上属于完全不同的轴。

## 决策

### 判据：看是为了什么

| 目的 | 归属 | 判据 |
|---|---|---|
| 让人看见发生了什么 | **观测轴**（`observability/`） | **整段删掉，下游收到的完全不变** |
| 读数据以决定数据怎么走 | **主干-支干轴**（`pipeline/`） | 删掉它，**下游收到的会变** |

这条「删除测试」是识别观测轴的关键。`UpstreamCallReporter` / `CallLifecyclePublisher` /
`ApiUsageCollector` 都满足它——删掉后请求照常成功，只是后台瞎了。

反之，`EmptyResponseGate`（看 chunk 够不够格）、内容判定器（看有没有内容）、
执行器（攒 usage）都**不满足**——删掉后行为会变。

### 依赖方向：主干 → 观测，单向

观测**永不**被主干依赖回来。与 `主干 → 应用服务` 同向。

**已实测兑现**：`observability/` 对外只 import `protocol/` 与自身，
对 `application/` / `infrastructure/` / `api/` / `control/` **零反向依赖**。

### 观测失败绝不重抛

`UpstreamCallReporter` 的契约是 best-effort：观测环节出问题不能影响请求。

## 后果

**正面**

- 「删掉试试」是一条**可执行**的判据，不需要理解代码语义就能判断归属
- 观测层可整段禁用（用于性能诊断或故障隔离），不影响主链路
- 零反向依赖让观测层可以独立演进、独立测试

**负面**（知情成本）

- **「观测」这个词在本项目是特指**，与日常语义不同。新人第一次接触时必须先读
  `pipeline/README.md` 的这段说明，否则会按直觉归类
- **best-effort 意味着观测数据可能丢失**。当观测失败时，请求成功但日志/统计有缺口。
  这是刻意的取舍：宁可少记录，不可因记录失败而影响业务
- `api/shared/` 这类**混合职责**的包容易被误判。判定时必须逐个类看，不能按包名归类

## 备选方案

### 方案 A：按「是否包含 IO」划分观测

- 描述：写日志/发网络请求的算观测，纯计算的算主干
- 拒绝理由：`ApiUsageCollector` 写数据库但属于观测；`EmptyResponseGate` 只读内存但属于主干。
  **IO 不是判据**

### 方案 B：不单独设观测轴，观测代码就近放在使用处

- 描述：谁需要记录谁自己写，不抽公共层
- 拒绝理由：会导致观测逻辑与主干耦合（主干调用观测），破坏「可整段删除」的性质；
  且同类指标（调用次数、token 用量）会散在多处重复实现

### 方案 C：把观测并入 `application/`

- 描述：观测也是「应用服务」的一种
- 拒绝理由：`application/` 的职责是**用例编排与后台查询**，它本身会被 `api/` 调用。
  把观测放进去会让「删掉观测」变得不可能——它已经和用例缠在一起

## 参考

- 四轴框架权威说明（含三次误判的教训）：[pipeline/README.md](../../src/main/java/com/kaixuan/copilot_ollama_proxy/pipeline/README.md)
- 请求链路中的观测点：[REQUEST_PIPELINE.md](../architecture/REQUEST_PIPELINE.md)
- 观测层代码：`observability/`（`notify/`、`record/`、`log/`）
