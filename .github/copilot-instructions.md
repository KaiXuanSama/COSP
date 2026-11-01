# COSP Copilot 入口

开始任何根 COSP 任务前，先读并遵循 [AGENTS.md](../AGENTS.md)。它是 workspace-wide 的唯一规则真源；
本文件不复制架构、测试或运行时细节，以免两份说明漂移。

修改文件时还要加载匹配路径的 [instructions](./instructions/)；改 SQLite schema、迁移版本、表、列、
索引或约束时，使用 [数据库迁移 Skill](./skills/cosp-schema-migration-skill/SKILL.md)。
整理或新增 `docs/`、记录或替代架构决策、执行合并前文档检查时，使用
[文档治理 Skill](./skills/docs-governance/SKILL.md)；需要真实 HTTP 联测 COSP 与上游 mock 时，使用
[实机联测 Skill](./skills/cosp-live-mock-testing-skill/SKILL.md)。

`reference/` 下的 `cc-switch/`、`new-api/`、`sub2api/`、`CLIProxyAPI/` 是独立参考项目（被根仓库忽略）。任务未明确指向时只读，不修改、不构建、不测试。
