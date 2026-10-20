# ADR-0001: 采纳 docs-governance 规范并迁移现有 docs/

- **状态**：Accepted
- **日期**：2026-10-20
- **取代**：（无）
- **相关**：[docs-governance skill](../../.github/skills/docs-governance/SKILL.md)（规范来源）、
  [RFC：docs 迁移实施记录](../working/rfcs/2026-10-20-docs-migration.md)（含阶段 1 的 38 行映射表）

---

## 背景

现有 `docs/` 采用「**功能优先 + 生命周期下沉**」的组织方式：

- `features/<功能>/` 按功能聚拢，内部用固定文件名（`RESEARCH.md` / `PLAN.md` / `SUMMARY.md` /
  `*-CONTRACT.md` / `samples/`）表达生命周期
- `architecture/` 沉淀跨功能的机制说明
- `reference/` 存放实测数据与调查记录

这套结构在 COSP 的两年演进中工作良好，但随文档量增长（38 个文件，文本约 500 KB）暴露出三个问题：

1. **决策不可追溯**。决策散在各文档的 `§0 决策与理由`、`§9 决策定案`、`§7 明确不做的事` 中。
   决策被推翻时只能**直接改原文**——2026-10 的 C2R reasoning 修复就是这样处理的（在
   `C2R-PLAN.md` 里手写「后记」标注过时）。历史因此丢失：无法回答「当初为什么这么选」。
2. **缺乏合并前文档门禁**。文档同步依赖人工自觉。R2C 收尾时才发现 `translate/` 的实现计数、
   多处「R2C 未实现」的状态声明集体过时——这类漂移没有任何机制拦截。
3. **没有归档纪律**。过时文档要么被改写（丢失历史），要么留在原地冒充当前事实。

引入 `docs-governance` skill 后，上述三项均有现成方案：ADR 决策链（只增不改 + supersede）、
合并前文档门禁、`archive/` 归档纪律。

## 决策

**采纳 docs-governance 规范，用 `docs-new/` 逐步重建，迁移完成后一次性切换。**

采用「影子目录 + 最后切换」策略：`docs/` 在迁移全程保持可用，`docs-new/` 独立建设，
阶段 7 才执行 `docs → docs-old`、`docs-new → docs`。

### 目标结构

```text
docs/
├── README.md                    文档体系索引（Diátaxis 目录地图 + 更新规则）
├── adr/                         架构决策记录（只增不改，永不归档）
├── architecture/                当前架构（活文档）
├── guides/                      操作指南（本项目暂空，占位）
├── reference/                   查事实
│   ├── contracts/               C2M 请求/响应契约
│   ├── samples/                 抓包与配置样例
│   ├── reports/                 实测报告
│   └── *.md                     模型支持、实测矩阵、调查报告
├── explanations/                原理综述（本项目暂空，占位）
├── working/
│   ├── rfcs/                    功能提案容器（含已实施完毕的）
│   ├── investigations/          空
│   └── meeting-notes/           空
├── archive/                     归档（附归档说明）
├── diagrams/
└── images/
```

## 三处有意偏离

规范不能原样照搬，以下三处刻意偏离，理由记录于此以免后人重新争论。

### 偏离一：功能档案改为 `working/rfcs/` 的「提案容器」

**规范做法**：Diátaxis 的四象限（tutorials / how-to / reference / explanation）没有
「功能档案」这个类别，规范建议调研归 `working/investigations/`、定稿结论晋升到各正式目录。

**我们发现的问题**：COSP 的主力文档正是功能档案——协议翻译的调研（70 KB）、计划与实施记录
（62 KB）、Responses 直连（67 KB）合计占文本总量 40%。它们无法拆进四象限任一：
- 契约（93 KB）是长期规范 → 归 `reference/contracts/`（这一条**不偏离**）
- 但调研 + 计划 + 实施记录是**一个功能的完整生命周期**，拆进 `working/investigations/`
  会在功能完成后变成「已完成结论留在 working」的状态，与规范「working 只放未定稿材料」冲突

**采用方案**：`working/rfcs/` 作为**带状态的提案容器**。一个功能一个 RFC 文件，
文件头标注状态（草稿 / 讨论中 / 已定稿 / 已否决）与相关 ADR。功能实施完毕后：
- 结论晋升为 ADR（决策的原子记录，冻结）
- RFC 文件**不删除、不归档**，状态改为「已定稿且实施完毕」，长期留作该功能的完整档案

**为什么不是 `features/`**：`features/` 是目录（一个功能多个文件），RFC 是文件（一个功能一个文件）。
后者用 front-matter 的 `状态` 字段表达生命周期，替代了三个阶段文件名，且与 ADR 形成
「决策原子 / 功能容器」的清晰分工。此做法来自同级项目 `text-embedding-api-rs` 的实践。

**这个方案的代价**：单个 RFC 可能很长（C2R + R2C 合并后约 130 KB）。缓解措施：文件开头
必须加导航目录。

### 偏离二：已完成的调查报告留在 `reference/`，不进 `working/investigations/`

**规范做法**：调研报告归 `working/investigations/`，结论定稿后晋升或归档。

**采用方案**：`ANYROUTER_INVESTIGATION.md`、`COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md`、
`OLLAMA-EXTENSION-REVIEW.md` 留在 `reference/`。

**理由**：这三份的职责是「**查事实**」而非「推进中的材料」——
- `OLLAMA-EXTENSION-REVIEW.md` 记录「官方扩展现阶段支持到哪」，是长期可查的事实
- `COPILOT_BYOK_REASONING_REPLAY_INVESTIGATION.md` 记录「Copilot 客户端自行回放 thinking」的
  实测结论，随时可能被重新查证
- `ANYROUTER_INVESTIGATION.md` 记录供应商行为，同理

把它们放进 `working/` 会让「已完成、长期有效」的内容住在「未定稿材料」的居所里，
语义错位。`working/investigations/` 保留给**真正进行中**的调查。

### 偏离三：契约与说明文档保留全大写文件名

**规范做法**：文件名全小写 kebab-case、纯 ASCII。

**采用方案**：`reference/contracts/` 下的契约、`architecture/` 下的说明文档**保持全大写**
（`C2M-REQUEST-CONTRACT.md`、`REQUEST_PIPELINE.md` 等）。

**理由**：
1. **迁移成本**：改名会额外产生 100+ 处链接改写，且源码 Javadoc 里已有 18 处引用，
   收益（纯风格统一）与风险（链接失效）不成正比
2. **项目惯例已稳定**：全大写在这套文档里承载了「这是长期文档」的语义，与 `PLAN.md` /
   `RESEARCH.md` 等阶段性文件名形成对比
3. **ADR 与 RFC 遵循规范**：新增的 `adr-0001-*.md`、`2026-10-20-*.md` 采用小写 + 日期前缀，
   与规范一致——只有**存量**文档保持原样

## 后果

**正面**

- 决策有了独立、冻结、可追溯的记录；决策变更走 supersede，不再改写原文
- 合并前文档门禁成为强制步骤，文档漂移有机制拦截
- 过时文档有 `archive/` 归宿，不再冒充当前事实
- 目录职责由 Diátaxis 定义，新文档「该放哪」有明确判据

**负面**（知情成本）

- **一次性迁移成本约 8.5 个工作日**：11 篇 ADR（2 天）、7 个 RFC 合成（2 天）、
  24 篇文档重组（2 天）、31 处外部引用 + 18 处 Java 注释 + 127 处内部链接改写（1.5 天）、
  切换与验收（0.5 天）、准备与映射（0.5 天）
- **单个 RFC 变长**：C2R + R2C 合并后约 130 KB / 2100 行，需要导航目录才能高效阅读
- **`features/` 的功能聚拢优势减弱**：原来「一个功能一个目录」让相关文件（契约 + 调研 +
  计划 + 抓包）在一起；迁移后契约去了 `reference/contracts/`、抓包去了 `reference/samples/`、
  调研计划合并进 `working/rfcs/`。虽然每处都有索引，但**跨目录跳转**替代了目录内查看
- **两套命名风格并存**：存量全大写 + 新增小写，需要记住「新文档按规范命名，旧的不动」

## 备选方案

### 方案 A：维持现状，只增量吸收三项能力

在现有 `features/` 结构上增量添加：`docs/README.md` 增加门禁一节、`features/<功能>/DECISIONS.md`
记录决策变更、定归档规则。不改路径、不改命名。

- 描述：约 2 小时工作量即可获得 ADR 语义（简化版）、门禁、归档三项能力
- 拒绝理由：**这不是「统一规范」**。用户明确要求「即使是大工程，对长久的标准化管理都是个好处」。
  增量方案会让决策记录分散在各 `DECISIONS.md` 里，没有全局的 ADR 索引，
  也无法回答「本项目至今做过哪些重大决策」这类跨功能问题

### 方案 B：完全按规范重建，不保留任何偏离

严格采用 Diátaxis 四象限，`features/` 拆散：契约进 `reference/`、调研进 `working/investigations/`、
计划进 `working/rfcs/`、抓包进 `reference/samples/`，文件名全部改小写。

- 描述：最彻底、最规范
- 拒绝理由：**会真的丢东西**。93 KB 契约 + 62 KB 计划 + 500 KB 抓包的「一个功能」上下文被打散，
  阅读连续性受损；且全部改名会让 149 处引用（31 外部 + 127 内部 + 18 Java）中
  至少一半失效，风险高于收益

### 方案 C：只迁移新文档，存量不动

新文档按 docs-governance 规约创建，旧文档留在 `docs/features/` 等目录。

- 描述：零迁移风险，逐步过渡
- 拒绝理由：**两套体系并存比任何一套都糟**。读者无法判断该看哪边，索引要维护两份，
  违反「单一事实来源」铁律。这正是本次迁移要避免的状态

## 参考

- 规范来源：[docs-governance skill](../../.github/skills/docs-governance/SKILL.md)
- 目录宪法：[directory-constitution.md](../../.github/skills/docs-governance/references/directory-constitution.md)
- ADR 指南：[adr-guide.md](../../.github/skills/docs-governance/references/adr-guide.md)
- 合并前门禁：[pre-merge-gate.md](../../.github/skills/docs-governance/references/pre-merge-gate.md)
- 迁移映射表：见 [RFC 实施记录 §阶段 1](../working/rfcs/2026-10-20-docs-migration.md#阶段-12026-10-20)
  （原独立文件 `MIGRATION-MAP.md` 已在迁移完成后删除）
- RFC 容器实践参考：同级项目 `text-embedding-api-rs` 的 `docs/working/rfcs/`
