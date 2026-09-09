import { describe, expect, it } from 'vitest'
import {
  buildEditableModel,
  extractModelNames,
  toEditableModel,
  toModelFormParams,
  type EditableModel,
} from './modelPayload'
import { parseAnthropicThinkingConfig } from './anthropicThinking'
import { parseMaxOutputConfig } from './maxOutput'
import { parseReasoningEffortConfig } from './reasoningEffort'

describe('extractModelNames', () => {
  it('解析 OpenAI 标准 { data: [{ id }] }', () => {
    expect(extractModelNames({ data: [{ id: 'gpt-4' }, { id: 'gpt-5' }] })).toEqual(['gpt-4', 'gpt-5'])
  })

  it('解析 { models: [...] } 形状', () => {
    expect(extractModelNames({ models: [{ name: 'glm-4.5' }] })).toEqual(['glm-4.5'])
  })

  it('同时收集 data 与 models 并去重', () => {
    const payload = { data: [{ id: 'a' }], models: [{ id: 'a' }, { id: 'b' }] }
    expect(extractModelNames(payload)).toEqual(['a', 'b'])
  })

  it('解析裸数组（对象元素与字符串元素混合）', () => {
    expect(extractModelNames([{ id: 'a' }, 'b', { model: 'c' }])).toEqual(['a', 'b', 'c'])
  })

  it('按 id / model / name 优先级取名', () => {
    expect(extractModelNames([{ model: 'm', name: 'n', id: 'i' }])).toEqual(['i'])
    expect(extractModelNames([{ model: 'm', name: 'n' }])).toEqual(['m'])
    expect(extractModelNames([{ name: 'n' }])).toEqual(['n'])
  })

  it('接受 JSON 字符串', () => {
    expect(extractModelNames('{"data":[{"id":"x"}]}')).toEqual(['x'])
  })

  it('非法 JSON 字符串返回空数组而不抛错', () => {
    expect(extractModelNames('not json')).toEqual([])
  })

  it('忽略空白名与非对象元素', () => {
    expect(extractModelNames([{ id: '  ' }, null, 42, '', 'ok'])).toEqual(['ok'])
  })

  it('去除名称首尾空白', () => {
    expect(extractModelNames([{ id: '  spaced  ' }])).toEqual(['spaced'])
  })

  it('无法识别的载荷返回空数组', () => {
    expect(extractModelNames(null)).toEqual([])
    expect(extractModelNames(undefined)).toEqual([])
    expect(extractModelNames({ unexpected: 1 })).toEqual([])
  })
})

describe('buildEditableModel', () => {
  it('空参数给出可用默认值', () => {
    const model = buildEditableModel()
    expect(model).toMatchObject({
      modelName: '',
      enabled: true,
      contextSize: '128000',
      capsTools: true,
      capsVision: false,
    })
    // 表单里这两个字段始终是序列化后的 JSON，而非裸档位 / 裸整数。
    // 新建模型的默认是 medium + 兜底、与 4K + 兜底。
    expect(parseReasoningEffortConfig(model.reasoningEffort))
      .toEqual({ effort: 'Medium', mode: 'fallback' })
    // 4K 而非 128K：V9 迁移把存量 128K 一律下调为 4K，默认值必须跟上——
    // 否则新建模型会拿到一个迁移刚刚判定为「过大」的值。
    expect(parseMaxOutputConfig(model.maxOutputTokens))
      .toEqual({ maxOutputTokens: 4000, mode: 'fallback' })
    // 新建行的思考方式与预算与列默认值逐一对应：adaptive + 兜底 + 哨兵。
    // 预算展示成 -1 而非空串：那就是提交后存进库里的值，两边应当一致。
    expect(parseAnthropicThinkingConfig(model.thinkingMode, model.thinkingBudgetTokens))
      .toEqual({ type: 'adaptive', mode: 'fallback', budgetTokens: '-1' })
  })

  it('保留 source 中的其余字段，便于拉取时带回已有配置', () => {
    const model = buildEditableModel('m1', { capsVision: true, customField: 'keep' })
    expect(model.capsVision).toBe(true)
    expect(model.customField).toBe('keep')
    expect(model.modelName).toBe('m1')
  })

  it('contextSize 统一转成字符串，maxOutputTokens 转成 V9 JSON', () => {
    const model = buildEditableModel('m', { contextSize: 64000, maxOutputTokens: 8192 })
    expect(model.contextSize).toBe('64000')
    // 8192 是二进制值，不在十进制预设里，作为非标值原样保留。
    expect(parseMaxOutputConfig(model.maxOutputTokens))
      .toEqual({ maxOutputTokens: 8192, mode: 'fallback' })
  })

  it('逗号分隔的 reasoningEffort 只取第一项', () => {
    const model = buildEditableModel('m', { reasoningEffort: 'High,Max' })
    expect(parseReasoningEffortConfig(model.reasoningEffort).effort).toBe('High')
  })

  // 旧的 None 表达的是「不向上游发送」，映射到 delete 而非回退成默认（fallback）——
  // 否则一次纯读取会让这些模型突然开始向上游发送 medium。
  it('旧的 None 升级为删除模式', () => {
    const model = buildEditableModel('m', { reasoningEffort: 'None' })
    expect(parseReasoningEffortConfig(model.reasoningEffort).mode).toBe('delete')
  })

  it('已是 V2 JSON 的值原样保留', () => {
    const raw = '{"reasoning_effort":"max","overwrite_mode":"override"}'
    const model = buildEditableModel('m', { reasoningEffort: raw })
    expect(parseReasoningEffortConfig(model.reasoningEffort))
      .toEqual({ effort: 'Max', mode: 'override' })
  })

  it('enabled 为 false 时不被默认值覆盖', () => {
    expect(buildEditableModel('m', { enabled: false }).enabled).toBe(false)
  })
})

describe('toEditableModel', () => {
  // 已保存模型的 contextSize 缺省为 '0'，与新建行的 128K 不同：
  // 已存数据应原样呈现，不该被悄悄改写。
  it('contextSize 缺省为 0 而非 128K', () => {
    const model = toEditableModel({
      modelName: 'm',
      enabled: true,
      contextSize: undefined as unknown as string,
      maxOutputTokens: undefined as unknown as string,
      capsTools: true,
      capsVision: false,
      reasoningEffort: '',
      thinkingMode: undefined as unknown as string,
      thinkingBudgetTokens: undefined as unknown as number,
    })
    expect(model.contextSize).toBe('0')
    // 最大输出与 contextSize 不同，缺省取 4K 而非 0：
    // 它的列约束要求 json_valid，“未配置”无法用 0 表达——那个语义已由模式承担。
    expect(parseMaxOutputConfig(model.maxOutputTokens))
      .toEqual({ maxOutputTokens: 4000, mode: 'fallback' })
    expect(parseReasoningEffortConfig(model.reasoningEffort))
      .toEqual({ effort: 'Medium', mode: 'fallback' })
  })
})

describe('toModelFormParams', () => {
  const model = (over: Partial<EditableModel> = {}): EditableModel => ({
    modelName: 'm1',
    enabled: true,
    contextSize: '128000',
    maxOutputTokens: '{"max_output_tokens":4000,"overwrite_mode":"fallback"}',
    capsTools: true,
    capsVision: false,
    reasoningEffort: 'Medium',
    thinkingMode: '{"thinking_type":"adaptive","overwrite_mode":"fallback"}',
    thinkingBudgetTokens: '',
    ...over,
  })

  it('生成索引式扁平键', () => {
    const params = toModelFormParams([model(), model({ modelName: 'm2' })])
    expect(params['models[0].name']).toBe('m1')
    expect(params['models[1].name']).toBe('m2')
  })

  it('布尔值序列化为 on / 空串，而非 true / false', () => {
    const params = toModelFormParams([model({ enabled: false, capsTools: true, capsVision: false })])
    expect(params['models[0].enabled']).toBe('')
    expect(params['models[0].capsTools']).toBe('on')
    expect(params['models[0].capsVision']).toBe('')
  })

  it('空 contextSize 回退 0，空 maxOutputTokens 回退 4K 兜底 JSON', () => {
    const params = toModelFormParams([model({ contextSize: '', maxOutputTokens: '' })])
    expect(params['models[0].contextSize']).toBe('0')
    // 下发的必须是 JSON 而非裸整数：列上有 json_valid 约束。
    expect(params['models[0].maxOutputTokens'])
      .toBe('{"max_output_tokens":4000,"overwrite_mode":"fallback"}')
  })

  it('reasoningEffort 为空时不下发该键，交由后端取默认值', () => {
    const params = toModelFormParams([model({ reasoningEffort: '' })])
    expect('models[0].reasoningEffort' in params).toBe(false)
  })

  it('空列表产出空参数', () => {
    expect(toModelFormParams([])).toEqual({})
  })
})
