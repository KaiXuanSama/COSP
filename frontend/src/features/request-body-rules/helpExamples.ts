import type { RuleSet } from './types'
import { MIMO_EXAMPLE_RULESET } from './defaultRequestBody'

/** 帮助模态框中的可执行示例。 */
export interface RuleHelpExample {
  key: string
  title: string
  summary: string
  ruleSummary: string
  steps: string[]
  tips: string[]
  input: Record<string, unknown>
  rules: RuleSet
}

const setValueRules: RuleSet = {
  version: 1,
  rules: [
    {
      id: 'help-set-temperature',
      order: 0,
      field: 'temperature',
      array: false,
      conditional: false,
      conditionMode: 'all',
      conditions: [],
      operations: [{ type: 'set_value', value: 0.7 }],
    },
  ],
}

const editObjectRules: RuleSet = {
  version: 1,
  rules: [
    {
      id: 'help-edit-stream-options',
      order: 0,
      field: 'stream_options',
      array: false,
      conditional: false,
      conditionMode: 'all',
      conditions: [],
      operations: [
        {
          type: 'edit_object',
          rules: [
            {
              id: 'help-set-include-usage',
              order: 0,
              field: 'include_usage',
              array: false,
              conditional: false,
              conditionMode: 'all',
              conditions: [],
              operations: [{ type: 'set_value', value: true }],
            },
          ],
        },
      ],
    },
  ],
}

const deleteRules: RuleSet = {
  version: 1,
  rules: [
    {
      id: 'help-delete-reasoning-effort',
      order: 0,
      field: 'reasoning_effort',
      array: false,
      conditional: false,
      conditionMode: 'all',
      conditions: [],
      operations: [{ type: 'delete' }],
    },
  ],
}

const conditionalRules: RuleSet = {
  version: 1,
  rules: [
    {
      id: 'help-conditional-messages',
      order: 0,
      field: 'messages',
      array: true,
      conditional: true,
      conditionMode: 'all',
      conditions: [
        { path: './role', operator: 'equals', value: 'tool' },
        { path: './tool_call_id', operator: 'exists', value: null },
      ],
      operations: [
        {
          type: 'edit_object',
          rules: [
            {
              id: 'help-delete-tool-call-id',
              order: 0,
              field: 'tool_call_id',
              array: false,
              conditional: false,
              conditionMode: 'all',
              conditions: [],
              operations: [{ type: 'delete' }],
            },
          ],
        },
      ],
    },
  ],
}

/** 帮助模态框的全部实时示例，直接由正式转换引擎执行。 */
export const RULE_HELP_EXAMPLES: RuleHelpExample[] = [
  {
    key: 'set-value',
    title: '设置字段值',
    summary: '把目标字段替换为指定的 JSON 值，字符串、数字、布尔值、对象和数组都会保留原始类型。',
    ruleSummary: 'temperature → 设置字段值 → 0.7',
    steps: ['目标字段选择 temperature', '操作选择“设置字段值”', '值类型选择数字并填写 0.7'],
    tips: ['填写 0.7 与填写字符串 "0.7" 的类型不同。'],
    input: { model: 'demo-model', temperature: 0.1, stream: true },
    rules: setValueRules,
  },
  {
    key: 'edit-object',
    title: '调整对象内容',
    summary: '进入一个对象字段，再对其内部字段应用嵌套规则。适合修改多层 JSON。',
    ruleSummary: 'stream_options → 调整对象内容 → include_usage 设置为 true',
    steps: ['目标字段选择 stream_options', '操作选择“调整对象内容”', '在内部规则中选择 include_usage 并设置为 true'],
    tips: ['目标值必须是对象；不是对象时会显示执行警告。', '嵌套层级可以继续向下添加。'],
    input: { stream: true, stream_options: { include_usage: false, debug: true } },
    rules: editObjectRules,
  },
  {
    key: 'delete',
    title: '删除字段',
    summary: '从请求体中完整移除目标字段，而不是把它设置为 null。',
    ruleSummary: 'reasoning_effort → 删除字段',
    steps: ['目标字段选择 reasoning_effort', '操作选择“删除字段”'],
    tips: ['右侧预览会保留红色删除线，方便确认；真正的输出中该字段已不存在。'],
    input: { model: 'demo-model', reasoning_effort: 'medium', stream: true },
    rules: deleteRules,
  },
  {
    key: 'conditions',
    title: '条件与数组',
    summary: '遍历 messages 数组，只对同时满足全部条件的元素执行内部规则。',
    ruleSummary: 'messages[]：当 ./role 等于 tool 且 ./tool_call_id 存在时，删除 tool_call_id',
    steps: ['目标字段选择 messages，并开启“遍历数组”', '开启条件执行，添加 equals 与 exists 条件', '选择“调整对象内容”，在内部删除 tool_call_id'],
    tips: ['相对路径以 ./ 开头，表示从当前数组元素查找。', 'V1 中多个条件固定使用 AND：必须全部满足。', './content[*]/image_url 可在数组内容中查找图片字段。'],
    input: {
      messages: [
        { role: 'user', content: 'hello' },
        { role: 'tool', content: 'result', tool_call_id: 'call_01' },
        { role: 'tool', content: 'already compatible' },
      ],
    },
    rules: conditionalRules,
  },
  {
    key: 'image-tool-compatibility',
    title: '图片工具消息兼容示例',
    summary: '组合数组遍历、两个条件、设置值和删除字段，只转换包含图片的 tool 消息。',
    ruleSummary: '图片 tool 消息：role 改为 user，并删除 tool_call_id；image_url 保持不变',
    steps: ['遍历 messages 数组', '要求 ./role 等于 tool', '同时要求 ./content[*]/image_url 存在', '进入消息对象：设置 role，并删除 tool_call_id'],
    tips: ['普通 tool 消息不会变化。', '右侧橙色表示修改，红色删除线表示移除，灰色表示未变化。'],
    input: {
      messages: [
        { role: 'tool', content: 'plain tool result', tool_call_id: 'call_text' },
        {
          role: 'tool',
          content: [
            { type: 'text', text: 'image result' },
            { type: 'image_url', image_url: { url: 'data:image/png;base64,...' } },
          ],
          tool_call_id: 'call_image',
        },
      ],
    },
    rules: MIMO_EXAMPLE_RULESET,
  },
]
