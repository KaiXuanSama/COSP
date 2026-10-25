# ADR-0013: M2C 将旧 output_format 作为结构化输出别名

- **状态**：Accepted
- **日期**：2026-10-24
- **取代**：（无；补充 ADR-0012 未冻结的旧字段兼容边界）
- **相关**：[ADR-0012](./adr-0012-m2c-request-translation.md)、
  [M2C/C2M RFC](../working/rfcs/2026-10-20-m2c-c2m-translation.md)

## 背景

Anthropic 的旧 beta 字段 `output_format` 与现行 `output_config.format` 表达相同的
结构化输出意图。ADR-0012 要求不静默丢弃显式格式要求，但尚未选择兼容旧字段还是明确拒绝。
仅接受新字段会使尚未更新的客户端失败；任取两个字段之一则会隐藏相互冲突的要求。

## 决策

**M2C 请求翻译将 `output_format` 作为 `output_config.format` 的别名，以同一转换逻辑处理；
两种格式字段同时出现时明确报错，不设置隐含优先级。**

只有 `output_config.effort` 不构成格式冲突。字段路径仍指向实际输入的旧或新字段，
schema 原样搬运，不为上游的格式子集改写约束。目标包装与空值规则由请求契约维护。

## 后果

- 旧、新客户端共享同一条结构化输出转换路径，不会静默失去格式要求。
- 同时发送两种格式字段的客户端必须自行选择一种，不能依赖代理择一。
- 支持字段别名不保证任意 Chat 上游支持 JSON Schema；上游拒绝仍保持可见。
- 此决策只适用于 M2C 去程，不改变 Messages 直连或其它翻译方向。

## 备选方案

### 拒绝旧 output_format

未选择：源格式可以进行同样的结构转换，拒绝会增加可避免的客户端升级成本。

### 新字段优先或旧字段优先

未选择：会静默忽略另一份显式要求，即便两份当前相同也会形成隐藏的优先级契约。

### 静默丢弃旧字段

拒绝：违反 ADR-0012 关于显式格式意图的边界，请求成功不能证明格式要求已被满足。

## 参考

- [请求契约 §10.7](../reference/contracts/C2M-REQUEST-CONTRACT.md#m2c-request-spec)
- [RFC 已确认边界](../working/rfcs/2026-10-20-m2c-c2m-translation.md#m2c-open-items)