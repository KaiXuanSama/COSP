import { describe, expect, it } from 'vitest'

import {
  directionFromWheel,
  stepInSequence,
  stepNumericPreset,
} from './wheelStep'

/**
 * 方向的语义是**下标增量**而非「调大 / 调小」。
 *
 * 这个约定是被模式段逼出来的：`override` 与 `delete` 谁「更大」无从定义，
 * 唯一稳定的语义是清单里的前一项 / 后一项。数值段通过「预设降序声明」
 * 让下标增大恰好等于数值减小，两段因此自动一致。
 */
const UP = -1
const DOWN = 1

describe('directionFromWheel', () => {
  it('向上滚动取前一项', () => {
    expect(directionFromWheel(-100)).toBe(UP)
    expect(directionFromWheel(-1)).toBe(UP)
  })

  it('向下滚动取后一项', () => {
    expect(directionFromWheel(100)).toBe(DOWN)
    expect(directionFromWheel(1)).toBe(DOWN)
  })

  /**
   * 只取符号是刻意的：鼠标滚轮一格 100+，触控板惯性滚动可能是 1，
   * 按量级换算步数会让触控板要么毫无反应、要么一次跳到底。
   */
  it('量级不影响步数', () => {
    expect(directionFromWheel(-3)).toBe(directionFromWheel(-1200))
  })

  it('横向滚动不产生方向', () => {
    expect(directionFromWheel(0)).toBeNull()
  })
})

describe('stepInSequence', () => {
  // 按「代理干预程度」递减排列，这是模式清单的既有顺序。
  const modes = ['override', 'fallback', 'passthrough', 'delete'] as const

  it('按下标走一格', () => {
    expect(stepInSequence('fallback', modes, UP)).toBe('override')
    expect(stepInSequence('fallback', modes, DOWN)).toBe('passthrough')
  })

  /**
   * 不循环是与点击轮转的关键差异：点击是「换一个」，从末尾回到开头很自然；
   * 滚轮是「往这个方向推」，从 delete 往下跳回 override 会让人以为滚过头了。
   */
  it('端点处停住而不循环', () => {
    expect(stepInSequence('override', modes, UP)).toBe('override')
    expect(stepInSequence('delete', modes, DOWN)).toBe('delete')
  })

  it('认不出的值回到首项', () => {
    expect(stepInSequence('bogus' as never, modes, UP)).toBe('override')
    expect(stepInSequence('bogus' as never, modes, DOWN)).toBe('override')
  })

  it('空清单返回原值', () => {
    expect(stepInSequence('fallback', [], UP)).toBe('fallback')
  })

  it('单项清单原地不动', () => {
    expect(stepInSequence('only', ['only'], UP)).toBe('only')
    expect(stepInSequence('only', ['only'], DOWN)).toBe('only')
  })

  it('两档清单来回走并在端点停住', () => {
    const two = ['override', 'fallback'] as const
    expect(stepInSequence('override', two, DOWN)).toBe('fallback')
    expect(stepInSequence('fallback', two, UP)).toBe('override')
    expect(stepInSequence('override', two, UP)).toBe('override')
    expect(stepInSequence('fallback', two, DOWN)).toBe('fallback')
  })

  /** 连续同向滚动应走到端点后停住，不能来回抖动。 */
  it('连续向下滚到末档为止', () => {
    let mode: string = 'override'
    const seen: string[] = []
    for (let i = 0; i < 5; i += 1) {
      mode = stepInSequence(mode, modes as readonly string[], DOWN)
      seen.push(mode)
    }
    expect(seen).toEqual(['fallback', 'passthrough', 'delete', 'delete', 'delete'])
  })
})

describe('stepNumericPreset', () => {
  // 界面上按降序声明，这里刻意乱序传入以证明函数自己排序。
  const presets = [16000, 4000, 128000, 32000, 8000, 64000]

  // 降序清单里向上滚（下标减小）= 数值变大。
  it('正好落在预设上时走到相邻预设', () => {
    expect(stepNumericPreset(8000, presets, UP)).toBe(16000)
    expect(stepNumericPreset(8000, presets, DOWN)).toBe(4000)
  })

  /**
   * 这是整个函数存在的理由：用户填的 6000 在 4000 与 8000 之间，
   * 向下滚该到 4000、向上滚该到 8000。
   *
   * <p>若先把非标值规整到最近预设再步进一格，从 6000 向下会走到 4000
   * （先规整到 8000 再降一档），一次滚动改了两格 —— 手感是「打滑」。
   */
  it('非标值吸附到该方向最近的预设', () => {
    expect(stepNumericPreset(6000, presets, DOWN)).toBe(4000)
    expect(stepNumericPreset(6000, presets, UP)).toBe(8000)
  })

  it('二进制值也按同一规则吸附', () => {
    // 4096 在 4000 与 8000 之间
    expect(stepNumericPreset(4096, presets, DOWN)).toBe(4000)
    expect(stepNumericPreset(4096, presets, UP)).toBe(8000)
    // 8192 在 8000 与 16000 之间
    expect(stepNumericPreset(8192, presets, DOWN)).toBe(8000)
    expect(stepNumericPreset(8192, presets, UP)).toBe(16000)
  })

  it('端点处该方向无预设时停住', () => {
    expect(stepNumericPreset(128000, presets, UP)).toBe(128000)
    expect(stepNumericPreset(4000, presets, DOWN)).toBe(4000)
  })

  /**
   * 超出清单范围的值（用户手填 200000）往回走时进入清单，
   * 继续往外走则保持不变 —— 不该因为滚了一下就把值改小。
   */
  it('超出上界的值向下走进清单、向上不变', () => {
    expect(stepNumericPreset(200000, presets, DOWN)).toBe(128000)
    expect(stepNumericPreset(200000, presets, UP)).toBe(200000)
  })

  it('低于下界的值向上走进清单、向下不变', () => {
    expect(stepNumericPreset(1000, presets, UP)).toBe(4000)
    expect(stepNumericPreset(1000, presets, DOWN)).toBe(1000)
  })

  it('空预设返回原值', () => {
    expect(stepNumericPreset(6000, [], UP)).toBe(6000)
  })

  it('连续向下滚逐档到底', () => {
    let value = 6000
    const seen: number[] = []
    for (let i = 0; i < 3; i += 1) {
      value = stepNumericPreset(value, presets, DOWN)
      seen.push(value)
    }
    expect(seen).toEqual([4000, 4000, 4000])
  })

  it('连续向上滚逐档到顶', () => {
    let value = 6000
    const seen: number[] = []
    for (let i = 0; i < 6; i += 1) {
      value = stepNumericPreset(value, presets, UP)
      seen.push(value)
    }
    expect(seen).toEqual([8000, 16000, 32000, 64000, 128000, 128000])
  })
})
