import { describe, expect, it } from 'vitest'

import {
  DEFAULT_REASONING_EFFORT,
  DEFAULT_REASONING_OVERWRITE_MODE,
  EFFORT_OFF,
  REASONING_EFFORT_OPTIONS,
  REASONING_OVERWRITE_MODES,
  nextReasoningOverwriteMode,
  parseReasoningEffortConfig,
  serializeReasoningEffortConfig,
} from './reasoningEffort'

describe('REASONING_EFFORT_OPTIONS', () => {
  /**
   * 顺序即滚轮步进顺序，必须按强度升序。
   *
   * `Off` 排首位而非末尾：它在强度上就是最低的一档，放末尾会让向下滚动
   * 从 `Low` 跳到序列之外。
   */
  it('按强度升序，Off 在首位', () => {
    expect(REASONING_EFFORT_OPTIONS).toEqual([
      'Off', 'Minimal', 'Low', 'Medium', 'High', 'Xhigh', 'Max',
    ])
  })
})

describe('EFFORT_OFF', () => {
  /**
   * 持久化标识是 `off`，不是 `none`。
   *
   * `reasoning_effort` 在 OpenAI Chat Completions 协议里没有 `none` 这一档（DeepSeek
   * 只认 low/high/max），那个值属于 Responses 协议的 `reasoning.effort`。
   * 这个档位的出站形态由后端翻译成 `thinking: {"type": "disabled"}`。
   */
  it('与后端的 EFFORT_OFF 同一口径', () => {
    expect(EFFORT_OFF).toBe('off')
  })
})

describe('parseReasoningEffortConfig', () => {
  it('解析 V2 JSON 形态', () => {
    expect(parseReasoningEffortConfig('{"reasoning_effort":"high","overwrite_mode":"override"}'))
      .toEqual({ effort: 'High', mode: 'override' })
  })

  /**
   * 库里存的 `off` 要显示成 `Off`，而且**不能**被当成 delete 模式。
   *
   * 两者语义相反：旧的裸 `"None"` 是「两个字段都不发」（delete），
   * `Off` 档是「发送 thinking:disabled 明确要求不思考」。
   * 混淆会让一个明确的要求退化成沉默 —— 对默认开启思考的模型，结果完全相反。
   */
  it('JSON 里的 off 解析为 Off 档并保留其模式', () => {
    expect(parseReasoningEffortConfig('{"reasoning_effort":"off","overwrite_mode":"override"}'))
      .toEqual({ effort: 'Off', mode: 'override' })
  })

  it('新增的 Minimal 档位可被解析', () => {
    expect(parseReasoningEffortConfig('{"reasoning_effort":"minimal","overwrite_mode":"fallback"}'))
      .toEqual({ effort: 'Minimal', mode: 'fallback' })
  })

  it('档位大小写归一化为选项形态', () => {
    expect(parseReasoningEffortConfig('{"reasoning_effort":"XHIGH","overwrite_mode":"delete"}').effort)
      .toBe('Xhigh')
  })

  // 兜底是 V2 之前的唯一行为（只在下游未携带时注入），所以旧的裸档位升级后行为不变。
  it('旧的纯档位字符串按兜底模式解析', () => {
    expect(parseReasoningEffortConfig('Medium'))
      .toEqual({ effort: 'Medium', mode: 'fallback' })
  })

  it('更旧的逗号分隔多值只取第一项', () => {
    expect(parseReasoningEffortConfig('High,Max').effort).toBe('High')
  })

  /**
   * 这是兼容逻辑的主要理由：`None` 表达的是「不向上游发送」，对应 `delete`。
   * 若把它当作认不出的档位回退成默认（`fallback`），那些模型会突然开始向上游发送
   * medium —— 一次纯粹的读取行为改变了运行时行为，且用户无从察觉。
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

  /**
   * Off 档存为 `off`。
   *
   * 不存 `none`：那不是 `reasoning_effort` 的合法取值（见 {@link EFFORT_OFF}）。
   * 出站形态的翻译在后端，前端只负责把档位名持久化。
   */
  it('Off 档存为 off', () => {
    expect(serializeReasoningEffortConfig({ effort: 'Off', mode: 'override' }))
      .toBe('{"reasoning_effort":"off","overwrite_mode":"override"}')
  })

  /** 遍历全部档位 × 全部模式，含新增的 Off / Minimal。 */
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
  // 顺序按「代理干预程度」递减：覆写接管 → 兜底补缺 → 透传不管；
  // delete 排末尾是因为它不是「更不干预」而是另一种干预（强制剥离）。
  it('按覆写 → 兜底 → 透传 → 删除轮转并回到开头', () => {
    expect(nextReasoningOverwriteMode('override')).toBe('fallback')
    expect(nextReasoningOverwriteMode('fallback')).toBe('passthrough')
    expect(nextReasoningOverwriteMode('passthrough')).toBe('delete')
    expect(nextReasoningOverwriteMode('delete')).toBe('override')
  })

  // 原地不动会让按钮看起来是坏的。
  it('认不出的值从头开始而非原地不动', () => {
    expect(nextReasoningOverwriteMode('bogus' as never)).toBe(REASONING_OVERWRITE_MODES[0])
  })

  it('轮转一整圈回到原点', () => {
    let mode = REASONING_OVERWRITE_MODES[0]
    for (let i = 0; i < REASONING_OVERWRITE_MODES.length; i++) {
      mode = nextReasoningOverwriteMode(mode)
    }
    expect(mode).toBe(REASONING_OVERWRITE_MODES[0])
  })

  /**
   * 四档必须各不相同且覆盖全部模式。
   *
   * 轮转是用户切换模式的唯一入口，少一档就意味着那个模式在界面上无法被选到 ——
   * 而这类缺失不会让任何其它用例失败。
   */
  it('轮转一圈恰好经过全部四种模式', () => {
    const visited: string[] = []
    let mode = REASONING_OVERWRITE_MODES[0]
    for (let i = 0; i < REASONING_OVERWRITE_MODES.length; i++) {
      visited.push(mode)
      mode = nextReasoningOverwriteMode(mode)
    }
    expect(visited).toEqual([...REASONING_OVERWRITE_MODES])
    expect(new Set(visited).size).toBe(4)
  })
})

describe('REASONING_EFFORT_OPTIONS', () => {
  // None 的语义已由 delete 模式承担，留在档位里会让「不发送」有两种表达方式。
  it('不含 None', () => {
    expect(REASONING_EFFORT_OPTIONS.map(o => o.toLowerCase())).not.toContain('none')
  })
})
