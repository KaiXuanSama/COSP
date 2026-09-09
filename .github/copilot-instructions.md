# COSP Copilot 入口

开始任何根 COSP 任务前，先读并遵循 [AGENTS.md](../AGENTS.md)。它是 workspace-wide 的唯一规则真源；
本文件不复制架构、测试或运行时细节，以免两份说明漂移。

修改文件时还要加载匹配路径的 [instructions](./instructions/)；改 SQLite schema、迁移版本、表、列、
索引或约束时，使用 [数据库迁移 Skill](./skills/cosp-schema-migration-skill/SKILL.md)。

`cc-switch/`、`new-api/`、`sub2api/` 是独立参考项目。任务未明确指向时只读，不修改、不构建、不测试。
