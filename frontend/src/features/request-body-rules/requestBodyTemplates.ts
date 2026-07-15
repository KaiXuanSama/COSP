/** 请求体预览模板片段及其组合逻辑。 */

/** 可选择的结构化模板片段。custom 仅代表用户自行编辑，不参与对象合并。 */
export type RequestBodyTemplateKey =
  | 'base'
  | 'message-start'
  | 'message-assistant'
  | 'message-tool-basic'
  | 'message-tool-image'
  | 'tools'
  | 'custom'

/** 会实际生成请求体字段的模板键。 */
type StructuredTemplateKey = Exclude<RequestBodyTemplateKey, 'custom'>

/** 默认勾选全部结构化片段，得到常用的完整消息场景。 */
export const DEFAULT_TEMPLATE_KEYS: StructuredTemplateKey[] = [
  'base',
  'message-start',
  'message-assistant',
  'message-tool-basic',
  'message-tool-image',
  'tools',
]

/** 多选下拉框显示选项。 */
export const REQUEST_BODY_TEMPLATE_OPTIONS = [
  { label: '基础参数', value: 'base' },
  { label: 'Message 起始数据', value: 'message-start' },
  { label: 'Message Assistant', value: 'message-assistant' },
  { label: 'Message Tool（基础）', value: 'message-tool-basic' },
  { label: 'Message Tool（图片）', value: 'message-tool-image' },
  { label: 'Tools 工具定义', value: 'tools' },
  { label: '自定义', value: 'custom' },
] satisfies Array<{ label: string; value: RequestBodyTemplateKey }>

const TEMPLATE_FRAGMENTS: Record<StructuredTemplateKey, Record<string, unknown>> = {
  base: {
    model: '<string>',
    temperature: 0.1,
    top_p: 1.0,
    stream: true,
    n: 1,
    stream_options: {
      include_usage: true,
    },
    reasoning_effort: 'medium',
  },
  'message-start': {
    messages: [
      {
        role: 'system',
        content: '<string>',
      },
      {
        role: 'user',
        content: '<string>',
      },
    ],
  },
  'message-assistant': {
    messages: [
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
    ],
  },
  'message-tool-basic': {
    messages: [
      {
        role: 'tool',
        content: '<string>',
        tool_call_id: '<string>',
      },
    ],
  },
  'message-tool-image': {
    messages: [
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
  },
  tools: {
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
  },
}

/**
 * 按选项固定顺序组合请求体。
 *
 * 普通对象字段在根对象中叠加，所有 messages 片段追加到同一个数组。
 * custom 不产生内容；调用方应保留用户当前文本。
 */
export function composeRequestBodyTemplate(
  selectedKeys: readonly RequestBodyTemplateKey[],
): Record<string, unknown> {
  const selected = new Set(selectedKeys)
  const result: Record<string, unknown> = {}
  const messages: unknown[] = []

  for (const key of DEFAULT_TEMPLATE_KEYS) {
    if (!selected.has(key)) continue
    const fragment = TEMPLATE_FRAGMENTS[key]
    for (const [field, value] of Object.entries(fragment)) {
      if (field === 'messages' && Array.isArray(value)) {
        messages.push(...deepClone(value))
      } else {
        result[field] = deepClone(value)
      }
    }
  }

  if (messages.length > 0) {
    result.messages = messages
  }
  return result
}

function deepClone<T>(value: T): T {
  return JSON.parse(JSON.stringify(value))
}
