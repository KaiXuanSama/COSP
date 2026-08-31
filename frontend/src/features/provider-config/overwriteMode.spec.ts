import { describe, expect, it } from 'vitest'

import {
  OVERWRITE_MODE_LABELS,
  buildOverwriteModeHints,
  canonicalizeOverwriteMode,
  modeUsesConfiguredValue,
  nextOverwriteMode,
  parseModeScopedValue,
  serializeModeScopedValue,
  type ModeScopedCodec,
  type OverwriteMode,
} from './overwriteMode'

/** 四档的字符串字段，形似思考深度。 */
const FOUR_MODES: readonly OverwriteMode[] = ['override', 'fallback', 'passthrough', 'delete']

const stringCodec: ModeScopedCodec<string, OverwriteMode> = {
  modes: FOUR_MODES,
  defaultMode: 'fallback',
  defaultValue: 'Medium',
  valueKey: 'level',
  canonicalizeValue: value => {
    if (typeof value !== 'string' || !value.trim()) return null
    const matched = ['Low', 'Medium', 'High'].find(
      option => option.toLowerCase() === value.trim().toLowerCase(),
    )
    return matched ?? null
  },
}

/** 两档的数字字段，形似最大输出。 */
type TwoModes = Extract<OverwriteMode, 'override' | 'fallback'>
const TWO_MODES: readonly TwoModes[] = ['override', 'fallback']

const numberCodec: ModeScopedCodec<number, TwoModes> = {
  modes: TWO_MODES,
  defaultMode: 'fallback',
  defaultValue: 4000,
  valueKey: 'budget',
  canonicalizeValue: value => {
    const parsed = typeof value === 'number' ? value : Number.parseInt(String(value ?? '').trim(), 10)
    return Number.isFinite(parsed) && parsed > 0 ? Math.trunc(parsed) : null
  },
}

describe('OVERWRITE_MODE_LABELS', () => {
  // 标签全局统一是这个常量存在的理由：同一个词在不同字段上必须表示同一种干预方式。
  it('四档各有中文标签', () => {
    expect(OVERWRITE_MODE_LABELS).toEqual({
      override: '覆写',
      fallback: '兜底',
      passthrough: '透传',
      delete: '删除',
    })
  })
})

describe('buildOverwriteModeHints', () => {
  it('把值名填进前两档文案', () => {
    const hints = buildOverwriteModeHints('上限')
    expect(hints.override).toContain('上限')
    expect(hints.fallback).toContain('上限')
  })

  /**
   * 后两档不提配置的值 —— 它们压根不使用那个值，提到它反而暗示会用。
   */
  it('后两档文案与值名无关', () => {
    const forLevel = buildOverwriteModeHints('档位')
    const forBudget = buildOverwriteModeHints('预算')
    expect(forLevel.passthrough).toBe(forBudget.passthrough)
    expect(forLevel.delete).toBe(forBudget.delete)
  })

  // override 与 fallback 的差别只在「下游带了值时用谁的」，文案必须能区分这一点。
  it('前两档文案彼此可区分', () => {
    const hints = buildOverwriteModeHints('档位')
    expect(hints.override).not.toBe(hints.fallback)
    expect(hints.override).toContain('无论')
    expect(hints.fallback).toContain('未携带才')
  })
})

describe('modeUsesConfiguredValue', () => {
  it('前两档使用配置的值', () => {
    expect(modeUsesConfiguredValue('override')).toBe(true)
    expect(modeUsesConfiguredValue('fallback')).toBe(true)
  })

  /**
   * 界面据此把那一栏变灰。透传与删除都不使用配置的值，
   * 若判定错了，一个永不生效的值会与已生效的配置长得一模一样。
   */
  it('后两档不使用配置的值', () => {
    expect(modeUsesConfiguredValue('passthrough')).toBe(false)
    expect(modeUsesConfiguredValue('delete')).toBe(false)
  })
})

describe('nextOverwriteMode', () => {
  it('在四档清单里循环', () => {
    expect(nextOverwriteMode('override', FOUR_MODES)).toBe('fallback')
    expect(nextOverwriteMode('fallback', FOUR_MODES)).toBe('passthrough')
    expect(nextOverwriteMode('passthrough', FOUR_MODES)).toBe('delete')
    expect(nextOverwriteMode('delete', FOUR_MODES)).toBe('override')
  })

  it('在两档清单里来回切换', () => {
    expect(nextOverwriteMode('override', TWO_MODES)).toBe('fallback')
    expect(nextOverwriteMode('fallback', TWO_MODES)).toBe('override')
  })

  // 原地不动会让按钮看起来是坏的。
  it('清单外的值从头开始', () => {
    expect(nextOverwriteMode('passthrough' as TwoModes, TWO_MODES)).toBe('override')
  })

  it('轮转若干圈后回到原点', () => {
    let mode: OverwriteMode = 'override'
    for (let i = 0; i < FOUR_MODES.length * 3; i += 1) {
      mode = nextOverwriteMode(mode, FOUR_MODES)
    }
    expect(mode).toBe('override')
  })
})

describe('canonicalizeOverwriteMode', () => {
  it('大小写与两侧空白不敏感', () => {
    expect(canonicalizeOverwriteMode('  OVERRIDE ', FOUR_MODES)).toBe('override')
  })

  /**
   * 只认清单内的值是关键：把 passthrough 解析进只有两档的字段，
   * 会得到一个界面无法显示、轮转也碰不到的状态。
   */
  it('清单外的模式返回 null', () => {
    expect(canonicalizeOverwriteMode('passthrough', TWO_MODES)).toBeNull()
    expect(canonicalizeOverwriteMode('passthrough', FOUR_MODES)).toBe('passthrough')
  })

  it('非字符串返回 null', () => {
    expect(canonicalizeOverwriteMode(7, FOUR_MODES)).toBeNull()
    expect(canonicalizeOverwriteMode(null, FOUR_MODES)).toBeNull()
    expect(canonicalizeOverwriteMode(undefined, FOUR_MODES)).toBeNull()
  })
})

describe('parseModeScopedValue', () => {
  it('解析 JSON 形态', () => {
    expect(parseModeScopedValue('{"level":"high","overwrite_mode":"override"}', stringCodec))
      .toEqual({ value: 'High', mode: 'override' })
  })

  it('按 valueKey 取值', () => {
    expect(parseModeScopedValue('{"budget":8000,"overwrite_mode":"override"}', numberCodec))
      .toEqual({ value: 8000, mode: 'override' })
    // 键名不对就取不到，回退默认值而不是抛错。
    expect(parseModeScopedValue('{"level":8000,"overwrite_mode":"override"}', numberCodec).value)
      .toBe(4000)
  })

  it('JSON 里缺字段时逐项回退', () => {
    expect(parseModeScopedValue('{"level":"low"}', stringCodec))
      .toEqual({ value: 'Low', mode: 'fallback' })
    expect(parseModeScopedValue('{"overwrite_mode":"delete"}', stringCodec))
      .toEqual({ value: 'Medium', mode: 'delete' })
  })

  /**
   * 数据库里一行脏数据不该让整个模型列表无法编辑。
   */
  it('畸形 JSON 回退默认值而不抛错', () => {
    expect(parseModeScopedValue('{not json', stringCodec))
      .toEqual({ value: 'Medium', mode: 'fallback' })
  })

  it('空值与非字符串回退默认值', () => {
    expect(parseModeScopedValue('', stringCodec)).toEqual({ value: 'Medium', mode: 'fallback' })
    expect(parseModeScopedValue('   ', stringCodec)).toEqual({ value: 'Medium', mode: 'fallback' })
    expect(parseModeScopedValue(null, stringCodec)).toEqual({ value: 'Medium', mode: 'fallback' })
    expect(parseModeScopedValue(undefined, stringCodec)).toEqual({ value: 'Medium', mode: 'fallback' })
  })

  /**
   * 数字入参走值归一化：最大输出那一列在 V9 之前是 INTEGER，
   * 后端有可能把它当数字发过来而非字符串。
   */
  it('数字入参当作值本身', () => {
    expect(parseModeScopedValue(16000, numberCodec)).toEqual({ value: 16000, mode: 'fallback' })
    expect(parseModeScopedValue(-5, numberCodec)).toEqual({ value: 4000, mode: 'fallback' })
  })

  it('非 JSON 形态交给 parseLegacy', () => {
    const codec: ModeScopedCodec<string, OverwriteMode> = {
      ...stringCodec,
      parseLegacy: raw => (raw === 'none' ? { value: 'Medium', mode: 'delete' } : null),
    }
    expect(parseModeScopedValue('none', codec)).toEqual({ value: 'Medium', mode: 'delete' })
    // parseLegacy 返回 null 表示交给默认值。
    expect(parseModeScopedValue('whatever', codec)).toEqual({ value: 'Medium', mode: 'fallback' })
  })

  // 没有 parseLegacy 的字段遇到非 JSON 直接回退，而不是崩在 undefined 调用上。
  it('未提供 parseLegacy 时非 JSON 回退默认值', () => {
    expect(parseModeScopedValue('Medium', stringCodec))
      .toEqual({ value: 'Medium', mode: 'fallback' })
  })

  /**
   * JSON 分支不走 parseLegacy —— 否则一个合法 JSON 会被历史规则二次解释。
   */
  it('JSON 形态不经过 parseLegacy', () => {
    const codec: ModeScopedCodec<string, OverwriteMode> = {
      ...stringCodec,
      parseLegacy: () => ({ value: 'Low', mode: 'delete' }),
    }
    expect(parseModeScopedValue('{"level":"high","overwrite_mode":"override"}', codec))
      .toEqual({ value: 'High', mode: 'override' })
  })
})

describe('serializeModeScopedValue', () => {
  it('按 valueKey 写值', () => {
    expect(JSON.parse(serializeModeScopedValue({ value: 8000, mode: 'override' }, numberCodec)))
      .toEqual({ budget: 8000, overwrite_mode: 'override' })
  })

  it('出站转换作用于值', () => {
    expect(JSON.parse(serializeModeScopedValue(
      { value: 'High', mode: 'fallback' },
      stringCodec,
      level => level.toLowerCase(),
    ))).toEqual({ level: 'high', overwrite_mode: 'fallback' })
  })

  /**
   * 界面可能把一个空输入框的内容传进来，而列上带着取值约束，
   * 不该写进一个数据库不接受的形态。
   */
  it('非法值先归一化再写出', () => {
    expect(JSON.parse(serializeModeScopedValue({ value: 0, mode: 'override' }, numberCodec)).budget)
      .toBe(4000)
    expect(JSON.parse(serializeModeScopedValue({ value: 'Bogus', mode: 'override' }, stringCodec)).level)
      .toBe('Medium')
  })

  it('序列化后能被解析回原值', () => {
    const original = { value: 32000, mode: 'override' as TwoModes }
    expect(parseModeScopedValue(serializeModeScopedValue(original, numberCodec), numberCodec))
      .toEqual(original)
  })
})
