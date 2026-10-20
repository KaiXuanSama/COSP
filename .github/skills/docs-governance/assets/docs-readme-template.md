# 文档索引

> 本项目的文档结构遵循 `docs-governance` 规范。找文档前先读本节。

## 目录地图

| 目录 | 内容 | 典型问题 |
|---|---|---|
| [`adr/`](./adr/) | 架构决策记录（只增不改） | "为什么当初选了这个方案？" |
| [`architecture/`](./architecture/) | 当前架构（活文档） | "系统现在长什么样？" |
| [`guides/`](./guides/) | 操作指南 | "怎么做 X？" |
| [`reference/`](./reference/) | API / 配置 / schema | "这个字段的取值是什么？" |
| [`explanations/`](./explanations/) | 原理与背景 | "为什么这样设计？" |
| [`working/`](./working/) | 进行中的调研 / RFC / 纪要 | "这个提案讨论到哪了？" |
| [`archive/`](./archive/) | 已过时的文档 | "以前那个版本怎么写的？" |

## 常用直达

- 决策历史：[docs/adr/](./adr/)
- API 参考：[docs/reference/api.md](./reference/api.md) <!-- 按实际替换 -->

## 更新规则（摘要）

1. 同一事实只维护一处，其余用链接
2. 新决策 → 新建 ADR；决策变更 → 新 ADR 取代 + 旧 ADR 状态行标记
3. 功能实现完毕、合并前必须更新受影响文档（详见项目内 docs-governance skill）
4. 过时文档归档而非删除，归档文件头部加归档说明
