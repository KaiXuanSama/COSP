# Claude Desktop prompt 缓存命中率持续下降 —— 调查与修复

> **状态**：已定位并修复（2026-10-17），实测命中率 17% → 99%+
>
> **需求背景**：Claude Desktop 走 COSP 的 M→M 直连通道时，使用日志里的缓存命中率
> 从 98% 一路降到 17%，且随对话变长持续走低。供应商与模型配置全用「兜底」档，
> 理论上不应影响缓存。需要在**不修改 Claude Desktop** 的前提下找出断点位置并恢复命中率。
>
> 相关：[LIVE_TEST_MATRIX.md](../../reference/LIVE_TEST_MATRIX.md)、
> [ADAPTATIONS.md](../../architecture/ADAPTATIONS.md)（请求转换系统的能力边界）

## 1. 结论

**COSP 没有改写请求体，问题出在 Claude Desktop 自己。**

Claude Desktop 把 `system` 发成 4 个 content block，其中**第 4 块（索引 3）没有
`cache_control`，且每轮都在末尾追加内容**。Anthropic 的前缀缓存要求
「请求开头 → 断点」连续命中，而这一块位于 `messages` **之前**，
所以它一变，后面 `messages` 的滚动缓存断点整段失效。

命中的那 37K token 恒定是 `tools + system[0..2]` —— 由 `system[2]` 的
`cache_control` 断点保护。`messages` 侧的上万到上千百 K token 全部未命中。

## 2. 实测证据

### 2.1 请求体结构（M→M 直连，`mimo-v2.6-pro`）

| 位置 | 字符数 | `cache_control` | 是否稳定 |
|---|---|---|---|
| `tools`（38 个） | 64286 | 无 | 稳定 |
| `system[0]` | 78 | 无 | 稳定（billing header） |
| `system[1]` | 62 | `{"ttl":"1h","type":"ephemeral"}` | 稳定 |
| `system[2]` | 69592 | `{"ttl":"1h","type":"ephemeral"}` | 稳定（agent 主提示词） |
| **`system[3]`** | **12681 → 14126** | **无** | **每轮追加** |
| `messages`（144+ 条） | 800 万+ | 仅最后 1 条 | 滚动断点 |

`tools + system[0..2]` = 64286 + 69732 = **134018 字符 ≈ 37K token**
—— 与 `cached_tokens` 恒定在 37K±1.4K 精确吻合。**这就是命中的全部内容。**

### 2.2 命中率与 `system[3]` 是否变化严格对应

同一模型连续请求，`system[3]` 未变的轮次命中率 98%~100%，一变就掉到 30% 左右：

| 请求 → 请求 | `system[3]` | `cached_tokens` | 命中率 |
|---|---|---|---|
| 34372 → 34373 | 长度都是 12647，**未变** | 139520 | **99.9%** |
| 34373 → 34374 | 12647 → 13834，**变** | 35328 | 32.3% |
| 34381 → 34385 | 都是 14614，**未变** | 129280 | **98.2%** |
| 34385 → 34397 | 14614 → 15088，**变** | 37248 | 28.2% |

修复前的完整衰减曲线（`prompt` 涨、`cached` 不动，比值必然下降）：

```
19:19  131676 / 129280 = 98.2%   ← system[3] 未变的那轮
19:25  131987 /  37248 = 28.2%   ← system[3] 追加了计数行
19:33  195718 /  37888 = 19.4%
20:01  209379 /  38272 = 18.3%
次日 13:24  227881 / 38656 = 17.0%
```

### 2.3 为什么「纯追加」也会失效

对相邻两轮 `system[3]` 做最长公共前缀比对，结果**恰好等于上一轮全长**，
说明变化形式是纯尾部追加，而非中间改写：

```
34414 追加: "\n\n<total_tokens>14949802 tokens left</total_tokens>"
34415 追加: "\n\nThe task tools haven't been used recently…\n\n<total_tokens>…"
```

内容长度变了，后面就没有可供匹配的前缀 —— 追加与改写对前缀缓存效果相同。

### 2.4 COSP 侧逐段核对：未改写请求体

| 环节 | 结论 |
|---|---|
| `AnthropicController.buildRequestBody` | `system` 以 `Object` 原样放入，不做形态转换 |
| `SystemPromptNormalizer.extractSystemPrompt` | `messages` 里 `system` 角色数 = **0**，抬升逻辑未触发；数组未降级成字符串 |
| `AnthropicThinkingNormalizer` | 兜底档，`thinking`/`output_config.effort` 均原样透传 |
| `MaxTokensNormalizer` | 兜底档，下游已带 `max_tokens` 时不覆盖 |
| `RequestBodyRuleEngine` | 该供应商当时无规则 |
| `body.values().removeIf(Objects::isNull)` | 只清顶层 null，不触碰 `system` |

`<total_tokens>` 与 `<system-reminder>` 在 COSP 源码中检索不到，确认由客户端生成。

## 3. 修复方法

在供应商的请求体规则里新增一个 **MESSAGES** 协议组，用正则去掉 `system` 末块中
每轮变化的内容。

### 3.1 为何选这条路线

| 方案 | 评价 |
|---|---|
| **A. 不改** | 每轮约 184K token 按原价重复计费（以 34512 为例：`prompt=227881`、`cached=38656`） |
| **B. 正则规则删除变化内容** | ✅ **采用**。规则数据化、无需改代码、可随时开关 |
| C. 把无 `cache_control` 的 system 块下沉进 `messages` | 改成「`cache_control` 块之后还有块」的形态，需实测上游是否接受，风险更高 |

### 3.2 规则内容

完整 JSON 见 [RULE-EXAMPLE.json](./RULE-EXAMPLE.json)。要点：

- 外层规则遍历 `system` 数组，`matches` 条件只选中含变化内容的块
  —— 带 `cache_control` 的稳定块不受影响。
- 嵌套规则对 `text` 依次做两次 `regex_replace`，`replacement` 为空即删除。
- 条件路径是**相对数组元素**的 `./text`，不是 `./system[*]/text`。

两条正则（JSON 里反斜杠需写两层）：

| 用途 | JSON 写法 | 引擎拿到的 |
|---|---|---|
| token 计数行 | `"\\n*<total_tokens>\\d+ tokens left</total_tokens>"` | `\n*<total_tokens>\d+ tokens left</total_tokens>` |
| task 工具提醒 | `"(?s)\\n*The task tools haven't been used recently.*?ignore if not applicable\\."` | `(?s)\n*The task tools…` |

### 3.3 为此新增的规则引擎能力

原引擎只有 `exists` / `equals` 两个条件、`edit_object` / `set_value` / `delete`
三个操作，表达不了「删掉字符串里某段模式」。本次扩展：

| 类型 | 名称 | 语义 | 位置 |
|---|---|---|---|
| 条件 | `matches` | 字段为字符串且**包含**正则匹配（`Matcher.find`）；整串匹配自行写 `^...$` | `RequestBodyRuleEngine#regexMatches` |
| 操作 | `regex_replace` | `Matcher.replaceAll` 语义，`$1` 引用分组；`replacement` 留空即删除 | `RequestBodyRuleEngine#regexReplace` |

设计取舍：

- **正则用 Java 方言**，内联标志（`(?s)` / `(?i)`）写在 pattern 里，不另设开关。
- **保存时编译一次**（`ProviderRequestTransformService#validatePattern`）：
  非法正则在保存时报错，而不是运行时静默不生效 —— 后者极难排查。
- **运行时失败不抛异常**：字段不存在静默跳过；字段非字符串、正则非法、替换文本
  非法（如 `$9` 引用不存在的分组）各产生一条警告并保留原值。
- **前端只校验非空**，不用 JS `RegExp` 编译：两种方言不一致（所有格量词 `a*+`
  只有 Java 支持），用 JS 编译会误拒合法规则。

## 4. 修复后实测

规则生效后同一会话连续请求，命中率稳定在 98%~99.9%：

```
15:11  313919 / 311552 = 99.2%
15:21  321747 / 320384 = 99.6%
15:36  355624 / 355328 = 99.9%
15:40  368163 / 367488 = 99.8%
15:43  374155 / 372608 = 99.6%
```

对比修复前同量级请求（`prompt=227881` / `cached=38656` = 17.0%），
`cached` 从「恒定 37K」变为「与 `prompt` 同步增长」。

### 4.1 残余的两次谷底及其成因

修复后仍有两轮掉到 9%（34689、34712）。已定位为**另一类每轮变化内容**，
不是 token 计数行：

```
34688 → 34689  system[3] 追加: "\n\nNote: …\build_doc.js changed on disk since you last read it.…"
34711 → 34712  system[3] 追加: "\n\nNote: …\make_pagemap.py changed on disk…\n\nHere are the relevant changes…"
```

这是 Claude Desktop 在**工具读取过的文件发生变化**时插入的提醒。它的出现取决于
工作流（是否在编辑被读过的大文件），不是每轮都有，因此命中率整体仍在 99%，
只在那一轮掉一次。

**这一项不纳入当前规则**：删掉它会让模型失去「文件已被外部改动」的信号，
可能导致它基于过时内容继续工作 —— 那是语义损失，不是缓存问题。

## 5. 已知代价与限制

| 项 | 说明 |
|---|---|
| **上下文预算感知** | 删掉 `<total_tokens>` 后，Claude Desktop 看不到从这里读到的剩余 token 预算 |
| **跨天第一轮** | `system[3]` 含日期（`Today's date is …`），每天变一次，跨天首轮仍会未命中 |
| **文件变更提醒** | 见 §4.1，保留不动 |
| **规则绑定供应商** | 规则挂在供应商上；更换供应商或新建同类供应商需重新配置 |

## 6. 复现与验证方法

诊断用的临时探针脚本在 `target/` 下（不入库），思路是直接读 `admin.db` 的
`api_call_log.request_body` 做结构化比对：

1. 取同模型最近 N 条 `request_body`（`payload_trimmed = 0` 的行才有完整载荷）。
2. 解析 JSON，逐块打印 `system[i]` 的 `text` 长度、`cache_control` 与哈希。
3. 对相邻两轮做**最长公共前缀**比对，定位断点位置并打印断点附近的原文。
4. 与 `api_call_usage` 的 `prompt_tokens` / `cached_tokens` / `usage_raw` 对齐。

**关键判据不是「JSON 是否不同」，而是「`system` 中位于最后一次 `cache_control`
断点之前的内容是否逐字节相同」** —— 只有后者决定前缀缓存能否命中。

> 只看 `usage_raw` 容易误判：`input_tokens` 只统计未命中部分
> （`{"input_tokens":305912,"cache_read_input_tokens":32768}`），
> 它在命中正常时极小（几百），一旦变大就是缓存断掉了的强信号。

## 7. 排查陷阱（本项目实际踩过的）

| 陷阱 | 实情 |
|---|---|
| 「缓存占比下降 = COSP 改了请求体」 | 逐段核对后 COSP 全程未改写；`system` 数组形态也未降级 |
| 「兜底档配置不会影响缓存」 | 正确，但**只能排除 COSP 侧**，不能排除下游客户端 |
| 「命中的 37K 在缓慢增长，说明缓存工作正常」 | 那是以 128 为步长的计量粒度抖动，实际恒定 |
| 「中间出现过 98% 说明已恢复」 | 那是 `system[3]` 恰好未变的那一轮，不是修复 |
| 「追加内容比改写内容更友好」 | 对前缀缓存无区别：长度变了后面就匹配不上 |
