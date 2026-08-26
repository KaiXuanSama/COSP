import { describe, expect, it } from 'vitest'

import {
  DEFAULT_REASONING_EFFORT,
  DEFAULT_REASONING_OVERWRITE_MODE,
  REASONING_EFFORT_OPTIONS,
  REASONING_OVERWRITE_MODES,
  nextReasoningOverwriteMode,
  parseReasoningEffortConfig,
  serializeReasoningEffortConfig,
} from './reasoningEffort'

describe('parseReasoningEffortConfig', () => {
  it('解析 V2 JSON 形态', () => {
    expect(parseReasoningEffortConfig('{"reasoning_effort":"high","overwrite_mode":"override"}'))
      .toEqual({ effort: 'High', mode: 'override' })
  })

  it('档位大小写归一化为选项形态', () => {
    expect(parseReasoningEffortConfig('{"reasoning_effort":"XHIGH","overwrite_mode":"delete"}').effort)
      .toBe('Xhigh')
  })

  it('旧的纯档位字符串按透传模式解析', () => {
    expect(parseReasoningEffortConfig('Medium'))
      .toEqual({ effort: 'Medium', mode: 'passthrough' })
  })

  it('更旧的逗号分隔多值只取第一项', () => {
    expect(parseReasoningEffortConfig('High,Max').effort).toBe('High')
  })

  /**
   * 这是兼容逻辑的主要理由：`None` 表达的是「不发送」。
   * 若只按认不出的档位回退成 Medium，那些模型会突然开始向上游发送 medium ——
   * 一次纯粹的读取行为改变了运行时行为，且用户无从察觉。
   */
  it('旧的 None 映射为删除模式而非回退档位', () => {
    expect(parseReasoningEffortConfig('None'))
      .toEqual({ effort: DEFAULT_REASONING_EFFORT, mode: 'delete' })
    expect(parseReasoningEffortConfig('none').mode).toBe('delete')
  })

  it('空值与非字符串回退默认', () => {
    const fallback = { effort: DEFAULT_REASONING_EFFORT, mode: DEFAULT_REASONING_OVERWRITE_MODE }
    expect(parseReasoningEffortConfig('')).toEqual(fallback)
    expect(parseReasoningEffortConfig('   ')).toEqual(fallback)
    expect(parseReasoningEffortConfig(undefined)).toEqual(fallback)
    expect(parseReasoningEffortConfig(42)).toEqual(fallback)
  })

  // 字段来自数据库，一行脏数据不该让整个模型列表无法编辑。
  it('残缺 JSON 回退默认而不抛错', () => {
    expect(parseReasoningEffortConfig('{"reasoning_effort":'))
      .toEqual({ effort: DEFAULT_REASONING_EFFORT, mode: DEFAULT_REASONING_OVERWRITE_MODE })
  })

  it('JSON 里未知模式回退默认模式，但档位仍生效', () => {
    expect(parseReasoningEffortConfig('{"reasoning_effort":"low","overwrite_mode":"bogus"}'))
      .toEqual({ effort: 'Low', mode: DEFAULT_REASONING_OVERWRITE_MODE })
  })

  // 下拉框只认选项里的值，塞一个陌生值进去会显示成空白。
  it('未知档位回退默认档位', () => {
    expect(parseReasoningEffortConfig('{"reasoning_effort":"turbo","overwrite_mode":"delete"}'))
      .toEqual({ effort: DEFAULT_REASONING_EFFORT, mode: 'delete' })
  })
})

describe('serializeReasoningEffortConfig', () => {
  // 上游协议里该字段取值是小写的，存小写让后端直接取用，少一个转换点。
  it('档位以小写写入', () => {
    expect(serializeReasoningEffortConfig({ effort: 'High', mode: 'override' }))
      .toBe('{"reasoning_effort":"high","overwrite_mode":"override"}')
  })

  it('往返一致', () => {
    for (const effort of REASONING_EFFORT_OPTIONS) {
      for (const mode of REASONING_OVERWRITE_MODES) {
        expect(parseReasoningEffortConfig(serializeReasoningEffortConfig({ effort, mode })))
          .toEqual({ effort, mode })
      }
    }
  })
})

describe('nextReasoningOverwriteMode', () => {
  it('按覆写 → 透传 → 删除轮转并回到开头', () => {
    expect(nextReasoningOverwriteMode('override')).toBe('passthrough')
    expect(nextReasoningOverwriteMode('passthrough')).toBe('delete')
    expect(nextReasoningOverwriteMode('delete')).toBe('override')
  })

  // 原地不动会让按钮看起来是坏的。
  it('认不出的值从头开始而非原地不动', () => {
    expect(nextReasoningOverwriteMode('bogus' as never)).toBe(REASONING_OVERWRITE_MODES[0])
  })

  it('连续轮转三次回到原点', () => {
    let mode = REASONING_OVERWRITE_MODES[0]
    for (let i = 0; i < REASONING_OVERWRITE_MODES.length; i++) {
      mode = nextReasoningOverwriteMode(mode)
    }
    expect(mode).toBe(REASONING_OVERWRITE_MODES[0])
  })
})

describe('REASONING_EFFORT_OPTIONS', () => {
  // None 的语义已由 delete 模式承担，留在档位里会让「不发送」有两种表达方式。
  it('不含 None', () => {
    expect(REASONING_EFFORT_OPTIONS.map(o => o.toLowerCase())).not.toContain('none')
  })
})
