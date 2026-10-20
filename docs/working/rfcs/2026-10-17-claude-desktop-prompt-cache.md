# RFC：Claude Desktop prompt 缓存命中率持续下降 —— 调查与修复

- **日期**：2026-10-17（立项日）
- **状态**：**已定稿且实施完毕**
- **相关 ADR**：（无独立决策 ADR；本次修复是既有规则引擎能力的应用）
- **配置样例**：[`claude-desktop-cache-rule.json`](../../reference/samples/claude-desktop-cache-rule.json)

> 命中率 98% → 17% 的断点定位：Claude Desktop 把 `system` 发成 4 个 block，
> 第 4 块（无 `cache_control`）每轮追加内容，使其后 9.4 MB 的 `messages` 前缀缓存失效。
> 修复靠新增规则引擎能力（`matches` 条件 + `regex_replace` 操作）。命中率恢复至 99%+。

## 正文


> **状态**：已定位并修复（2026-10-17 / 2026-10-18 两次形态），实测命中率 17% → 99%+，
> 崩塌期 3.2% → 99.0%
>
> **需求背景**：Claude Desktop 走 COSP 的 M→M 直连通道时，使用日志里的缓存命中率
> 从 98% 一路降到 17%，且随对话变长持续走低。供应商与模型配置全用「兜底」档，
> 理论上不应影响缓存。需要在**不修改 Claude Desktop** 的前提下找出断点位置并恢复命中率。
>
> **注意**：同一机制有**多种表现形态**，见 §4.2。首次修复后仍可能被新形态击穿。
>
> 相关：[LIVE_TEST_MATRIX.md](../../reference/LIVE_TEST_MATRIX.md)、
> [ADAPTATIONS.md](../../architecture/ADAPTATIONS.md)（请求转换系统的能力边界）

## 1. 结论

**COSP 没有改写请求体，问题出在 Claude Desktop 自己。**

Claude Desktop 把 `system` 发成 4 个 content block，其中**第 4 块（索引 3）没有
`cache_control`，且每轮都在末尾追加内容**。Anthropic 的前缀缓存要求
「请求开头 → 断点」连续命中，而这一块位于 `messages` **之前**，
所以它一变，后面 `messages` 的滚动缓存断点整段失效。

命中的那部分恒定是 `tools + system[0..2]` —— 由 `system[2]` 的
`cache_control` 断点保护。`messages` 侧的上万到上千百 K token 全部未命中。

**这一块里变化的内容有三种形态**，且会随时间交替出现：

| # | 形态 | 首次观测 | 规则 |
|---|---|---|---|
| 1 | `<total_tokens>\d+ tokens left</total_tokens>` 计数行 | 2026-10-17 | 已覆盖 |
| 2 | `<system-reminder>` task 工具提醒 | 2026-10-17 | 已覆盖 |
| 3 | **空 `<system-reminder></system-reminder>` 段** | 2026-10-18 | 已覆盖（§4.2） |

另有**刻意不处理**的两种（语义信号，删了会让模型基于过期内容工作）：
「文件已变更」提醒（`changed on disk`）与跨天日期变更（`Today's date is …`）。

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

完整 JSON 见 [RULE-EXAMPLE.json](../../reference/samples/claude-desktop-cache-rule.json)。要点：

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

### 4.2 第二形态：空 `<system-reminder>` 段追加（2026-10-18）

首轮修复生效后命中率稳定在 99%，但次日同一通道再次崩塌，且**比第一次更严重**
（命中率 3.2%~9.6%，`ttfb` 60~68 秒）。原因是同一机制的**另一种表现形态**：

```
34743 末块 23396 字符，199 段
34744 末块 23434 字符，200 段   ← 新增 38 字符："\n\n<system-reminder>\n</system-reminder>"
34745 末块 25030 字符，204 段   ← 新增 1596 字符（文件变更提醒，有内容）
34749 末块 25068 字符，205 段   ← 新增 38 字符（空段）
34750 末块 25106 字符，206 段   ← 新增 38 字符（空段）
```

**关键发现：`system[3]` 是由约 200 个 `<system-reminder>` 段拼成的**，
其中前 199 段（`# Environment`、agent 类型、skills 列表、各类 `Note:` 提醒）
跨 5 轮**逐字节完全稳定**，只有末尾会不断追加**空的** `<system-reminder></system-reminder>`。

首轮的规则只匹配 `<total_tokens>` 与 task 提醒，抓不到空段 ——
**规则本身仍在生效，但挡不住新形态**。这是本次最重要的一条经验：
「已修复」不等于「已穷尽该机制的所有形态」。

#### 为什么这次比第一次严重得多

| 项 | 首次（`mimo-v2.6-pro`） | 本次（`mimo-v2.6-flash`） |
|---|---|---|
| 稳定区（可命中） | ≈ 37K token | ≈ 43.7K token |
| 被作废的 `messages` | 19 万字符 | **940 万字符 ≈ 380K token** |
| 空段引入的失效率 | — | 38 字符毒性比 ≈ **1 : 248,000** |

38 个字符的空段，作废了 9.4MB 的前缀。这解释了「个位数命中率」：
命中率 = 43,726 / 380,081 ≈ 11%，与实测 3.2%~9.6% 吻合。

#### 首字延迟归因

```
10-16 正常:  body 0.18MB   → ttfb 0.6~7.8s    命中率 78~90%
10-16 膨胀:  body 17~34MB  → ttfb 8~16s       命中率 56~78%
10-18 崩塌:  body 9.13MB   → ttfb 60~68s      命中率 3~9.6%
```

**体积本身不是主因**（17~34MB 时 ttfb 只有 8~16 秒），
主因是「前缀全部未命中 → 上游必须完整 prefill 380K token」。
三个因素叠加：未命中 prefill + 9.4MB 上传 + `effort=xhigh` 推理开销。

#### 补充规则

在首轮规则组里追加第三条嵌套规则（`order: 2`），并把外层条件放宽：

```json
{
  "id": "rule-strip-empty-reminder",
  "order": 2,
  "field": "text",
  "array": false,
  "conditional": false,
  "conditionMode": "all",
  "conditions": [],
  "operations": [
    {
      "type": "regex_replace",
      "pattern": "\\n*<system-reminder>\\s*</system-reminder>",
      "replacement": ""
    }
  ]
}
```

外层 `matches` 条件必须同时加 `<system-reminder>` 分支，否则空段所在块里
没有 `<total_tokens>`，规则执行不到它：

```
"<system-reminder>|<total_tokens>|The task tools haven't been used recently"
```

`\s*` 覆盖三种空段形态：零内容、单换行、纯空格。

#### 验证结果

用生产同款规则集在真实 `request_body` 上回放：

```
id=34743 处理后末块=16418  残留<total_tokens>=false  残留空reminder=false
id=34744 处理后末块=16418  ...
id=34745 处理后末块=17937  ...
id=34749 处理后末块=17937  ...
id=34750 处理后末块=17937  ...

34743 → 34744 : ★ 一致
34744 → 34745 : 不同（有内容的文件变更提醒）
34745 → 34749 : ★ 一致
34749 → 34750 : ★ 一致
```

实测生效后（34795）：`prompt=388919 / cached=384960 = 99.0%`，`ttfb` 从 62s 降到 **22.5s**。

#### 体量本身仍需关注

规则只能救回「空段造成的失效」，救不了上下文体量。该会话的构成：

| 消息下标 | 字符数 | 内容 |
|---|---|---|
| `[223]` | 950,910 | 3 张图片 + system-reminder（instruction files 重读） |
| `[201]` | 783,579 | 2 个 `tool_result`，各含 base64 PNG |
| `[319]` | 490,855 | 2 张图片 + 文本 |
| `[173]` | 404,902 | 3 个 `tool_result`，各含 base64 图片 |
| `[99]` | 365,898 | `tool_result` 含 base64 图片 |

图片 base64 只占 1.37MB（15%），**其余 8MB 是工具返回的文本**（`ls -la`、脚本源码等）。
这是 Claude Desktop 自己的上下文管理策略，COSP 不改。**遇到此类情况应建议开新会话**。

## 5. 已知代价与限制

| 项 | 说明 |
|---|---|
| **上下文预算感知** | 删掉 `<total_tokens>` 后，Claude Desktop 看不到从这里读到的剩余 token 预算 |
| **跨天第一轮** | `system[3]` 含日期（`Today's date is …`），每天变一次，跨天首轮仍会未命中 |
| **文件变更提醒** | 见 §4.1，保留不动 |
| **空段删除** | 空 `<system-reminder>` 段本身无内容，删除零语义损失（§4.2）；但**非空**提醒不会删 |
| **体量不受控** | 规则只修前缀稳定性，不能减小请求体；9.4MB / 380K token 的会话仍需开新会话 |
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
| **「加过规则就等于这类问题绝迹」** | **同一机制有三种形态，首轮规则只覆盖前两种；次日被空段击穿（§4.2）** |
| 「字段末尾追加比中间改写友好」 | 对前缀缓存无区别：长度变了后面就匹配不上 |
| 「空段才 38 字符，可以忽略」 | 38 字符作废了 940 万字符前缀，毒性比 ≈ 1 : 248,000 |
| 「首字变慢是请求体太大」 | 17~34MB 时 ttfb 只有 8~16s；主因是命中率归零导致必须完整 prefill |

## 8. 复发排查清单

缓存再次下降时，按这个顺序查，不要重新从零推演：

1. **确认规则仍在**：`SELECT provider_key, body_rules_json FROM provider_config p
   JOIN provider_request_transform t ON t.provider_id = p.id`，
   检查 `enabled` 与规则是否含 `regex_replace`。
2. **对比相邻两轮 `system` 末块**：定位第一个不同字节的位置，打印断点附近原文。
3. **按段切分**：用 `<system-reminder>(.*?)</system-reminder>` 切开，
   逐段哈希，找出**哪一段**在变（绝大多数段是稳定的，变的通常是最后几段）。
4. **判断新形态是否已被规则覆盖**：通读现有 `pattern`，
   确认它对当前观测到的变化内容有效。
5. **若未覆盖**：新增一条 `regex_replace`，**并检查外层 `matches` 条件能否选中该块** ——
   这是最容易漏的一步（§4.2 实测踩过）。
6. **先离线验证再上线**：用 `admin.db` 里的真实 `request_body` 回放规则，
   确认「处理后相邻两轮是否逐字节一致」。