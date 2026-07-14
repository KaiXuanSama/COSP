/**
 * 默认完整请求体模板和 MiMo 示例规则。
 *
 * 模板覆盖 Copilot 可能发送的所有字段结构，不做场景切换。
 * 用户可在工作台中自由编辑或粘贴自定义 JSON。
 */

import type { RuleSet } from './types'

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
 * MiMo 示例规则集 — 用于第一版验收。
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
