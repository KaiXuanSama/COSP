# 协议翻译契约（请求侧 · M2C 链路）

> **方向**：本契约覆盖 **M2C 链路（下游 /v1/messages · 上游 Chat）** 的**请求去程**：
> 下游 Anthropic Messages 请求 → 上游 OpenAI Chat Completions 请求。
>
> **易混警告**：`C2M-REQUEST-CONTRACT.md` 覆盖的是 **C2M 链路（下游 Chat · 上游 Messages）**
> 的请求方向。两条链方向相反，本文件不续写 C2M 内容，也独立于
> [M2C-RESPONSE-CONTRACT.md](./M2C-RESPONSE-CONTRACT.md)（同链响应回程）。
>
> 状态：**M2C 去程已实现并注册（`MessagesToChatRequestTranslator`，声明 `(MESSAGES, CHAT)`），
> 自动化回归通过，三家真实上游实机存档**（2026-10-24，见
> [实施记录](../../working/rfcs/2026-10-20-m2c-c2m-translation.md#m2c-live-2026-10-24)）。
> **ADR-0016 角色透传已实现并通过自动化回归**（2026-10-25，见
> [角色透传实施记录](../../working/rfcs/2026-10-20-m2c-c2m-translation.md#m2c-role-passthrough-implementation-2026-10-25)）；
> 该新策略尚未重跑真实 Claude CLI / 上游验证，不能用上述旧实机记录证明扩展角色兼容。
> 本文档是字段规格与测试矩阵来源；「必须 / 不要」是约束，标注实测日期的段落才是已验证事实。

**决策链**：
- [ADR-0012：M2C 去程采用忠实映射与显式损失边界](../../adr/adr-0012-m2c-request-translation.md)
- [ADR-0013：旧 output_format 别名](../../adr/adr-0013-m2c-output-format-alias.md)
- [ADR-0014：三个顶层服务端配置字段省略](../../adr/adr-0014-m2c-drop-server-fields.md)
- [ADR-0016：角色字符串原样透传，枚举兼容性由上游裁决](../../adr/adr-0016-m2c-role-passthrough.md)
- [ADR-0017：工具 defer_loading 省略并告警，不再本地拒绝](../../adr/adr-0017-m2c-defer-loading-drop.md)

**相关**：
- [M2C 链路响应契约](../contracts/M2C-RESPONSE-CONTRACT.md)（回程）
- [M2C/C2M RFC](../../working/rfcs/2026-10-20-m2c-c2m-translation.md)（完整档案：调研/计划/实施记录）
- [C2M-REQUEST-CONTRACT.md](./C2M-REQUEST-CONTRACT.md)（反向链请求契约，术语体系一致）

---

## 1. 翻译层在链条中的位置

翻译是**下游请求进来后的第一层**，在设置层之前：

```text
下游 Claude 客户端（POST /v1/messages）
  → ★ M2C 去程翻译：Messages 请求 → Chat 请求（MessagesToChatRequestTranslator 白名单重建）
  → 协议归一化 / 设置层（思考深度、max_tokens、请求体规则）
  → 以 OpenAI Chat 协议发出
```

翻译器套在上游服务**外侧**（装饰器），不进 `GenericOpenAiChatService` 内部；
**翻译必须在重试边界之外**——空响应判定与落库用上游原生形态。
规则组按翻译后的 `bodyProtocol` 筛选：M2C 生效的是 `CHAT` 规则组。

## 2. 顶层字段与流式上下文

| 下游字段 | 目标字段/处置 |
|---|---|
| model | 原样交译器；后续主干装配按现有规则剥供应商前缀 |
| stream | 入口还原已实现：true/false/null/缺省与端点分流一致；后续执行和装配仍以 ctx.stream 为准 |
| max_tokens | 同名原值，不补默认、不抬高、不按模型名换别名 |
| temperature / top_p | 原值搬运，不因开启思考而自动删除 |
| top_k | 无通用 Chat 对等字段，省略 |
| stop_sequences | 非空数组转同序 stop 数组，单元素也不压成字符串；空数组/null 省略；非数组、非字符串元素报路径错误，不排序或静默丢元素 |
| system | 暂留顶层作为约定中间态，交 CHAT system 支线处理；不能被顶层白名单提前丢掉 |
| thinking / output_config.effort | 见 §3 |
| output_config.format | 见 §6；不能因只处理 effort 而丢失 |
| tools / tool_choice | 见 §5 |
| metadata / service_tier / 额外追踪信息 | 仅明确无映射的辅助字段省略；不把内容与执行要求当元数据清掉 |
| cache_control | 仅协议外壳处省略；不递归删除 schema/input 中同名业务字段 |
| mcp_servers / container / context_management | **顶层字段直接省略，不校验值或类型、不报错、不模拟服务端能力**（ADR-0014）；schema/input 等业务内容中的同名键保留 |

**流式：**只在 M2C 的 stream=true 时新建 `stream_options.include_usage:true`；
false/缺省不发该选项。额外混入 Messages 请求的 Chat stream_options 不是本方向的透传契约，
不将其它成员原封不动搬到上游。

`TranslationContext` 从注入前的源请求提取，不能把"向上游索取统计"写成"Chat 下游要求统计"。
Anthropic 回程是否输出 usage 不依赖该 Chat 专用标志。后续 CHAT 请求体规则可改 false/删除选项，
译器/执行器不得在规则之后偷偷补回。

## 3. 当轮思考配置与显式 null

| 下游表态 | 映射 |
|---|---|
| thinking.type=disabled | thinking.type=disabled |
| thinking.type=adaptive 或 enabled | 仅转成 enabled 开关；不搬 budget_tokens、不反推 effort |
| output_config.effort 为字符串 | reasoning_effort 同值，未知档位不自动降级 |
| thinking:null | 保留顶层 null 到设置层，避免 fallback 误判未表态 |
| output_config.effort:null | 写 reasoning_effort:null，同上 |
| output_config:null | 按现有 Anthropic 表态判据，用 reasoning_effort:null 保留显式空意见 |
| output_config:{} 或仅 format | 不代表深度意见，不阻止正常 fallback |
| 未提供 thinking/effort | 译器不推导，现有 ChatThinkingStage 按模型配置处理 |

未知开关类型或非对象的配置报路径化错误，不擅自修正；
有效 effort 与 disabled 同时出现时不加供应商特定整流，仍交现有四模式/目标上游处理。
装配末阶段才清顶层 null。历史 reasoning_content 与当轮思考设置不是同一个字段，不能联动误删。

`thinking` 是兼容生态字段，不能承诺每家官方/兼容 Chat 上游都接受；保持现有设置层的删除模式
和请求规则出口，不在本方向加模型名识别表。

## 4. system 归一化支线

已实现（阶段 1）：`ChatSystemPromptStage` 只声明 CHAT，并委托
`SystemPromptNormalizer.inlineSystemIntoMessages`。现有注册表按 bodyProtocol 自动命中，不修改主干。
实际过程：

1. 无顶层 system 时完整 no-op，不重排现有消息或改 developer/system 角色。
2. 字符串保留正文；数组按原序取 text 块，以 `\n\n` 拼接。未知非文本 system 块不应静默丢内容。
3. 仅在**整体提示词前导区域**剥已知的行首 `x-anthropic-billing-header:` 归属行，处理 LF/CRLF/CR，
  保留同块后续正文；遇到普通正文后不再过滤后续块，同名引用和普通身份描述不被通配删除。
4. 删除顶层 system，非空文本放消息首部。若首条已有字符串 system，按
   "顶层文本 + 双换行 + 原首条文本"合并；其它消息的相对顺序不动。
  首条数组 content 和 developer 不合并；首条空白字符串由顶层文本替换，不添多余分隔。
5. 空 system 不造占位消息；已存在的 messages 即使遇空 system 也要求为数组（包括显式 null 的错误）。
  非空 system 且 messages 缺失时报 messages 路径错误；空 system 且缺 messages 时只删除空字段。
  错误检查在修改 body 前完成，不将非法消息列表凭空替换成新数组。
6. 复制要改动的容器，不能修改原始嵌套 Map/List；再次执行应幂等。

注意现有 `stringifyContent` 用单 LF，不能直接当成 system 双 LF 的同义方法。
标准直连 CHAT 无顶层 system 保持 no-op；当前 OpenAiController 不回填顶层 system，
支线的非标准组合单测不能被当作已存在的 HTTP 契约变更。

## 5. 消息、工具调用与因果配对

**消息形态：**`role` 必须存在且为字符串；缺失、null 或非字符串仍报源 `messages[i].role` 路径。
**所有字符串角色原样透传**到 Chat 消息的 `role` 字段，不校验枚举、不 trim、不改大小写，
不因未识别而报错、丢弃消息或降为 user（ADR-0016，对齐 new-api）。空串与纯空白字符串
也交上游裁决，不在本地增加非空要求。

普通字符串与 text 块内容继续走既有转换路径。块的位置要求独立保留：
`tool_use`、`thinking`、`redacted_thinking` 仅允许 assistant，`tool_result`、`image`、`document`
仅允许 user；扩展角色不会自动获得这些块的使用资格。错置块仍报源 `content[j].type` 路径。
相邻源消息只有角色字符串完全相同时才合并为同一轮；不跨其它角色合并，保留源索引用于报错。
这不是对最终 Chat 消息强制交替，也不调用 C2M 的 merge→pair→merge。

字符串正文原样，纯文本块按原序以 LF 拼成字符串；保留非空文本内部空白；含图片/文件才保留 parts。
assistant 的正文、tool_calls、reasoning_content 保持在同一条消息；工具-only assistant 可用空字符串 content。

| Anthropic 输入 | Chat 目标 |
|---|---|
| tool_use.id / name | tool_calls[].id / function.name，原样、不加前缀；缺失/空白/非字符串报错 |
| tool_use.input 对象 | function.arguments 的 JSON 字符串，不用 Map.toString、不双重编码 |
| input 缺省/null | 无参兼容映射为 `{}`；不把字符串或数组猜测成对象 |
| input 非 null 且非对象（包括空字符串/空数组） | 不猜测修正，给出 input 字段路径 |
| user.tool_result | 独立 role=tool，tool_call_id 原样匹配前置调用 |

**已确认的配对策略：**

- 请求内建索引，结果只能应答先前调用，不能把前置结果拉去应答未来调用。
- 重复 tool_use ID 或重复 tool_result ID 报错，不使用 first/last-wins 掩盖歧义。
- 未获应答的调用清理并告警；assistant 有正文则保留，清空才删除。
- 孤儿结果连同其附属媒体一起丢弃并留痕，不凭空制造对应调用。
- 真正空结果仍是有效应答，保持 tool 消息的 `content:""`，不会因此清理其调用。
- 本组有效结果按调用顺序紧邻 assistant 连续发出，媒体和普通正文不得插到结果组中间。
- Chat 允许连续 tool/user；不能将「无相邻同角色」当反向验收标准。

**历史思考：**在配对清理后仍有 tool_calls 的 assistant 轮，把已有 thinking 明文按原序
用 LF 拼为 reasoning_content；清掉最后一个工具调用后不再回放。signature 不搬、redacted_thinking
丢弃并告警；没有明文不补任何占位。纯文本轮不回放，也不建跨请求存储。

末尾普通 assistant 原样作为历史消息保留，不改为 user，也不额外拒绝。
Chat 没有通用的 prefill 续写保证，实际效果由目标上游决定；未应答工具清理仍独立执行。

### 5.1 工具结果内容、错误与媒体

Chat tool 消息只发 `role/content/tool_call_id`，**不发 name、is_error 或协议 cache_control**。

| tool_result 内容 | 目标方案 |
|---|---|
| 缺省/null/空串/空数组 | 空字符串，仍是已应答；不造 `(empty)` 等结果 |
| 字符串或纯文本块 | 保留文本，按原序合并；不把块 JSON 当正文 |
| is_error=true | 用最小固定文本标记表达已有错误状态，正文保留；false/缺省不添加成功文案 |
| 图片、含媒体文档 | 共用 §6.2 转换，从 tool.content 抽出，所有 tool 结果之后附媒体 user 消息 |
| 纯文本文档 | 保留在工具文本通道；标题/上下文与文档内容保持关联 |

**固定文本编码**（不含随机 ID 或时间戳）：

- 错误：在工具正文之前追加 `[tool_result is_error=true]\n`；无正文仍保留该已存在的错误事实。
- 媒体：工具文本尾部追加 `[media moved to next user message]`，已有正文时用一个 LF 分隔。
- 媒体 user：每个有效结果的媒体组前放 `[media from tool_use_id=<JSON 字符串>]` 文本 part；
  ID 使用 JSON 引号与转义。组按调用顺序排列，含媒体文档整组迁移，纯文本文档留 tool。
- 文档元数据：`[document title]\n<原标题>`、`[document context]\n<原上下文>` 放在源内容之前；
  缺省/null/空串不造标记，正文与元数据仍按源块相邻排列。

媒体组按有效工具 ID 标注来源，含媒体文档的标题/上下文随媒体一起放置；
不同工具的媒体不可混为无来源图片列表。随后再输出普通 user 内容，不能让来源标记提升为 system 指令。
ID/标题等文本通过 JSON 正确转义，不插入可执行模板。

允许固定的"媒体已移到后续 user 消息"说明，但**标记必须由真实元数据触发**，
无错误、无媒体的空结果仍是空串。文案和混合内容的 golden 排列属于 RFC §12.7 的工程细节，
不编造思考或工具返回，不加随机 ID/时间戳。标记会计入 token，是已接受的知情成本。

### 5.2 工具定义、schema 与选择

| 字段 | 目标方案 |
|---|---|
| 自定义 tool（type 缺省/null/custom） | type=function，name/description/input_schema 分别进入 function.name/description/parameters |
| 服务端原生工具 | 无等价执行能力时明确拒绝，不把 server tool 伪装成客户端函数 |
| input_schema | 缺省/null 使用最小 object schema；对象只补缺失的根 type 和 type=object 时缺失的 properties，显式 null 不替换；根非对象报错，布尔子 schema 保留 |
| strict | 显式布尔进入 function.strict；缺省/null 省略，非布尔报路径错误 |
| tool_choice auto / any / none | auto / required / none |
| tool_choice tool+name | `{type:"function",function:{name}}`；要求工具已声明 |
| disable_parallel_tool_use 显式 true / false | parallel_tool_calls false / true |
| 并行开关缺省/null | 不注入，不凭空补 auto 选择策略 |

无声明工具时 auto/none 省略；显式 required/具名选择无法满足时，报路径化错误，
不是悄悄放宽成 auto。无工具时不发无意义并行参数。

保留 `required/additionalProperties/$defs/$ref/format/pattern/examples` 和布尔子 schema。
不复制参考项目的删约束、bool→object 或按特定工具名修改参数。
协议字段清理只作用于协议外壳，**schema、tool_use.input 中的 cache_control/signature 等业务键不动**。

工具扩展仅在协议外壳处理，不递归查找 schema/input 中的同名业务属性：

| 字段 | 值级处置 |
|---|---|
| input_examples | 提示信息省略；非空值告警，不重写为 schema 约束 |
| eager_input_streaming | 显式布尔省略并告警；null/缺省省略，非布尔报类型错误。它描述参数流形态，不是执行权限 |
| defer_loading | false/null/缺省省略；true 按 ADR-0017 省略并告警——它是客户端延迟加载提示，Chat 无对等字段、上游不消费，且 deferred 工具本就不参与提示缓存；非布尔报类型错误 |
| allowed_callers | null/缺省或仅 direct 的数组省略；其它 caller 无法等价表达调用方式，报对应元素路径错误。空数组省略并告警，不承诺与服务端缺省行为等价；非数组/非字符串元素报类型错误 |

caller 是工具调用方式声明，不是代理可用来证明安全权限的边界。

## 6. 文档与结构化输出

OpenAI 官方 file part 形态见 [SDK 固定版本](https://github.com/openai/openai-python/blob/v1.109.1/src/openai/types/chat/chat_completion_content_part_param.py)。
Anthropic document 有多种源形态，不能只实现 base64 后声称支持全部文档。

### 6.1 图片与文档源映射

**普通图片与工具内图片共用转换**：

- `image.source.type=base64` → `image_url.url=data:<media_type>;base64,<原 data>`，
  MIME 须为 `image/*`，不重编码或解析图片。
- `image.source.type=url` → 原 URL 放入 `image_url.url`，仅接受 HTTP(S)，
  scheme 大小写不敏感，不下载或改写路径/签名参数；未知源及字段类型错误报告源路径。

| 文档源/字段 | 目标行为 |
|---|---|
| base64 PDF | Chat file.file_data=`data:application/pdf;base64,...`，原数据搬运；不解码提取正文 |
| 文件名 | 存在的 filename 扩展优先，否则使用稳定 document.pdf；不从标题/模型名猜 |
| source.type=text | media_type 必须为 text/plain；data 转 text，内容保留 |
| source.type=content | 展开字符串或其中 text/image parts，保持顺序，不 stringify 成 JSON 正文 |
| title/context | 作为相邻文本元数据保留；固定编码见 §5.1 |
| tool_result 内 document | 共用转换；含媒体时按 §5.1 的来源/顺序规则输出 |
| URL 文档 | 首版明确报错，不把 URL 塞进 file_data，也不替用户下载 |
| source.type=file | 首版明确报错：Anthropic file_id 不等于 OpenAI 账号/服务的上传 ID |
| citations.enabled=true / 需要引用定位 | 首版明确报错，不能承诺 Chat 回程重建引用坐标；false/缺省不造引用 |

格式必需字段缺失/类型错误报告精确 source 路径，不把包含不支持文档的混合文本请求当成功处理。
其它 MIME/扩展源的支持不凭模型名推断；文件内容有效性与实际模型是否支持由目标上游检验。
未列出的内容/执行型块不默认透传；需要新增拒绝策略时先按 RFC §12.7 冻结边界。

### 6.2 结构化输出

**上游能力差异按忠实映射处理，不自动降级**：目标上游不支持已正确映射的 `json_schema` 时
（实测 DeepSeek 返回 `This response_format type is unavailable now`，而 stepfun 与 mimo
接受同一请求体并返回合规 JSON），保留可理解的失败，不偷偷改写成 `json_object` 或其它形态——
那会静默丢掉 schema 约束。实机证据与差分复核见
[实施记录](../../working/rfcs/2026-10-20-m2c-c2m-translation.md#m2c-live-2026-10-24)。

**`response_format` 的联合类型结构**（官方 SDK 定义）：`type` 与伴随字段成对出现，
但 `json_object` **没有伴随字段** —— 它只保证合法 JSON，不携带 schema，约束完全靠 prompt。

| `type` | 伴随字段 | 说明 |
|---|---|---|
| `text` | 无 | 默认 |
| `json_object` | 无 | 只保证语法合法，无 schema |
| `json_schema` | `json_schema` | `name` / `description` / `schema` / `strict` |

Anthropic 侧 `output_config.format` 当前只有 `json_schema` 一种类型，故本方向只映射该类型；
若将来新增类型，译器以路径化错误拒绝。`json_object` 是未来唯一可选的降级目标，
但降级需同时解决 schema 约束丢失与 prompt 引导（必须在 prompt 中完整定义字段结构），
不是改一个字段名就能完成。

**结构化输出包装：**

- `output_config.format.type=json_schema` → `response_format.type=json_schema`。
- `format.schema` 必须为对象，再原样拷入 `response_format.json_schema.schema`；
  目标 name 固定 `cosp_output`，以 strict=true 表达源格式约束。
- 不为目标上游的 schema 子集偷偷改用户约束；不支持时错误可见。
- format 缺省/null 不生成 response_format；存在但形态不合法不能静默丢弃。
- effort 与 format 独立处理；旧 beta `output_format` 作为相同格式的别名使用同一转换逻辑。
  若同时出现 `output_format` 和 `output_config.format`（包括显式 null），报 `output_format` 路径错误；
  仅有 `output_config.effort` 不构成重复格式要求。

## 7. 实现不变量与错误边界

- 不改变输入 Map 的深层内容；只用请求内局部状态，复制被改动的容器并保持后续规则可写。
- 同一输入产生确定性结果，消息/schema/input 不因无依据排序、随机标记或时间戳变化。
- 不对整份大文档反复 JSON 序列化/解析，不引入下载、上传、OCR 或文件存储。
- `RequestTranslationException` 带源字段路径；日志只记录方向、路径和必要 ID，
  不输出文档/base64、工具参数或 thinking 明文作为新诊断内容。
- 本地错误沿既有 defer 信号到出口：非流式 400，流式 HTTP200 + event:error；
  不等价于已实现完整的跨协议上游错误体转换。
- 字符串角色若被上游拒绝，保留其 HTTP 状态与错误体，走既有上游错误出口，
  不重新归类为本地 `RequestTranslationException`，也不新增角色回退或重试特例。
- 主干顺序、鉴权设置、路由优先级、重试与空响应预算不在此方向重定义。
- 去程 + 回程均已注册并实机验证过三家上游，但完整客户端验收前不宣称可生产使用。
