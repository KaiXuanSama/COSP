/**
 * 默认完整请求体模板和图片工具消息兼容规则。
 *
 * 模板覆盖 Copilot 可能发送的所有字段结构，不做场景切换。
 * 用户可在工作台中自由编辑或粘贴自定义 JSON。
 */

import type { FieldRule, RuleSet } from './types'

/** 默认完整请求体模板（覆盖文本、工具调用、图片、reasoning 等全部字段） */
export const DEFAULT_REQUEST_BODY = {
  model: '<string>',
  temperature: 0.1,
  top_p: 1.0,
  stream: true,
  messages: [
    {
      role: 'system',
      content: '<string>',
    },
    {
      role: 'user',
      content: '<string>',
    },
    {
      role: 'assistant',
      content: '<string>',
      tool_calls: [
        {
          id: '<string>',
          type: 'function',
          function: {
            name: '<string>',
            arguments: '<json-string>',
          },
        },
      ],
      reasoning_content: '<string>',
    },
    {
      role: 'tool',
      content: '<string>',
      tool_call_id: '<string>',
    },
    {
      role: 'tool',
      content: [
        {
          type: 'text',
          text: '<string>',
        },
        {
          type: 'image_url',
          image_url: {
            url: 'data:image/png;base64,<base64>',
          },
        },
      ],
      tool_call_id: '<string>',
    },
  ],
  tools: [
    {
      type: 'function',
      function: {
        name: '<string>',
        description: '<string>',
        parameters: {
          type: 'object',
          description: '<string>',
          properties: '<object>',
          required: ['<string>'],
        },
      },
    },
  ],
  n: 1,
  stream_options: {
    include_usage: true,
  },
  reasoning_effort: 'medium',
}

/** 默认模板的 JSON 文本形式 */
export const DEFAULT_REQUEST_BODY_JSON = JSON.stringify(DEFAULT_REQUEST_BODY, null, 2)

/**
 * 图片工具消息兼容规则集。
 *
 * 目标：将包含图片的 tool 消息的 role 改为 user，并删除 tool_call_id。
 * 不修改 image_url 对象结构。
 */
export const MIMO_EXAMPLE_RULESET: RuleSet = {
  version: 1,
  rules: [
    {
      id: 'mimo-messages',
      order: 0,
      field: 'messages',
      array: true,
      conditional: true,
      conditionMode: 'all',
      conditions: [
        {
          path: './content[*]/image_url',
          operator: 'exists',
          value: null,
        },
        {
          path: './role',
          operator: 'equals',
          value: 'tool',
        },
      ],
      operations: [
        {
          type: 'edit_object',
          rules: [
            {
              id: 'mimo-role',
              order: 0,
              field: 'role',
              array: false,
              conditional: false,
              conditionMode: 'all',
              conditions: [],
              operations: [
                {
                  type: 'set_value',
                  value: 'user',
                },
              ],
            },
            {
              id: 'mimo-tool-call-id',
              order: 1,
              field: 'tool_call_id',
              array: false,
              conditional: false,
              conditionMode: 'all',
              conditions: [],
              operations: [
                {
                  type: 'delete',
                },
              ],
            },
          ],
        },
      ],
    },
  ],
}

/**
 * 移除 Codex 的托管 `web_search` 工具（Responses 线路）。
 *
 * 2026-09-13 实测：Codex 经 COSP 打 MiMo 时上游硬拒绝
 * `{"error":{"code":"responses_feature_not_supported",
 *   "message":"tool type 'web_search' is not supported by this gateway phase"}}`。
 * cc-switch 对此有同源处置 —— 它把 `xiaomimimo.com` 与 `mimo` 列入
 * `CODEX_WEB_SEARCH_REJECT_HOSTS` / `..._MODEL_PREFIXES` 黑名单（注释标注为 hard 400），
 * 命中时往 Codex 的 `config.toml` 写 `web_search = "disabled"`。
 *
 * 但那条路对 COSP 走不通：黑名单按 base_url 主机名或模型名品牌前缀匹配，而经过 COSP 后
 * 主机恒为 localhost、模型名形如 `[mimo-tokenplan] mimo-v2.5`（以 `[` 开头，
 * `starts_with("mimo")` 恒假）。因此只能在 COSP 侧摘掉这个工具。
 *
 * 只删匹配到的元素而非整个 `tools` —— Codex 同时发十来个工具（shell、apply_patch 等），
 * 全删会毁掉编辑能力。条件路径写 `./type` 而非 `./tools[*]/type`：数组模式下
 * 条件以**元素**为作用域求值，写成根路径会恒不命中且不产生任何告警。
 */
/**
 * `web_search` 规则组的调试样本。
 *
 * 取自 Codex 真实请求的最小可复现形态：留一个 `web_search`（要被删掉的）
 * 加一个 `function`（必须保留的）—— 两者并存才能在预览里看出「只删匹配项、
 * 不动其余工具」这个关键行为。只留 web_search 的话，预览结果与
 * 「删掉整个 tools 字段」看起来一模一样，看不出差别。
 */
export const MIMO_RESPONSES_WEB_SEARCH_PREVIEW: Record<string, unknown> = {
  model: '<string>',
  input: '<string>',
  tools: [
    {
      type: 'web_search',
      external_web_access: false,
    },
    {
      type: 'function',
      name: 'shell',
      description: '<string>',
      parameters: '<object>',
    },
  ],
}

export const MIMO_RESPONSES_WEB_SEARCH_RULESET: FieldRule[] = [
  {
    id: 'mimo-drop-web-search',
    order: 0,
    field: 'tools',
    array: true,
    conditional: true,
    conditionMode: 'all',
    conditions: [
      {
        path: './type',
        operator: 'equals',
        value: 'web_search',
      },
    ],
    operations: [
      {
        type: 'delete',
      },
    ],
  },
]

/**
 * 把 `text.format` 从 `json_schema` 降级为 `json_object`（Responses 线路）。
 *
 * 2026-09-13 实测：Codex 的标题生成请求带 Structured Outputs 配置，上游拒绝
 * `{"error":{"code":"responses_feature_not_supported",
 *   "message":"text.format type 'json_schema' is not supported,
 *              only 'text' and 'json_object' are allowed."}}`。
 *
 * **整体替换 `format` 而不是只改 `type`**：官方 `ResponseFormatTextJSONSchemaConfig`
 * 带 `name` / `schema` / `strict` / `description`，而 `ResponseFormatJSONObject`
 * 的对象里**只有 `type`**。只改 type 会留下「json_object 配着 schema」的矛盾形态。
 *
 * 降级到 `json_object` 而非 `text`：前者仍保证输出是合法 JSON，能保住 Codex
 * 解析标题的意图；后者会彻底退化成纯文本，下游按 JSON 解析必然失败。
 * 代价是丢掉 schema 约束（官方注明 `json_object` 需提示词明确要求 JSON，
 * 而 Codex 的标题提示词本就在要求），因此这是能力降级而非等价替换。
 */
/**
 * `text.format` 规则组的调试样本。
 *
 * 照官方 `ResponseFormatTextJSONSchemaConfig` 的完整形态写：`name` 与 `schema`
 * 必填，`strict` 可选。四个字段都摆出来才能在预览里看清「整体替换」的意义 ——
 * 它们会连同 `type` 一起消失，只留下 `{"type":"json_object"}`。
 * 若样本里只有 `type`，预览就退化成「改一个字符串」，看不出为什么不能只改 type。
 *
 * 顺带留一个 `verbosity`：它与 `format` 同属 `text` 对象但与本规则无关，
 * 在预览里保持不变，正好说明规则没有波及同级的其它字段。
 */
export const MIMO_RESPONSES_TEXT_FORMAT_PREVIEW: Record<string, unknown> = {
  model: '<string>',
  input: '<string>',
  text: {
    format: {
      type: 'json_schema',
      name: '<string>',
      strict: true,
      schema: '<object>',
    },
    verbosity: 'medium',
  },
}

export const MIMO_RESPONSES_TEXT_FORMAT_RULESET: FieldRule[] = [
  {
    id: 'mimo-downgrade-json-schema',
    order: 0,
    field: 'text',
    array: false,
    conditional: true,
    conditionMode: 'all',
    conditions: [
      {
        // 标量模式下条件以「字段所在的对象」为作用域，故从根写起 ——
        // 与上面那条数组规则的 `./type` 恰好相反，这个不对称最容易写错。
        path: './text/format/type',
        operator: 'equals',
        value: 'json_schema',
      },
    ],
    operations: [
      {
        type: 'edit_object',
        rules: [
          {
            id: 'mimo-json-object',
            order: 0,
            field: 'format',
            array: false,
            conditional: false,
            conditionMode: 'all',
            conditions: [],
            operations: [
              {
                type: 'set_value',
                value: { type: 'json_object' },
              },
            ],
          },
        ],
      },
    ],
  },
]
