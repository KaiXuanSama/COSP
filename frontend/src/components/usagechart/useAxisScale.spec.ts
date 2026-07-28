import { describe, expect, it } from 'vitest'
import { cubicBezier, scaleOf } from './useAxisScale'

/**
 * 纵轴标尺动画的纯数学验证。
 *
 * 浏览器里的 requestAnimationFrame 只负责按时间取样；真正决定「压缩 / 解压」方向的，
 * 是标尺采用 {@code 1 / ceiling} 以及刻度位置采用 {@code value * scale}。
 * 这里锁定这两个不变量，避免未来重构后方向反转或产生非单调跳动。
 */
describe('纵轴标尺动画', () => {
  it('轴上限增大时同一刻度向基线收拢', () => {
    const tickValue = 100
    const before = tickValue * scaleOf(150)
    const after = tickValue * scaleOf(200)

    expect(before).toBeCloseTo(2 / 3, 10)
    expect(after).toBeCloseTo(1 / 2, 10)
    expect(after).toBeLessThan(before)
  })

  it('轴上限减小时同一刻度向上散开', () => {
    const tickValue = 100
    const before = tickValue * scaleOf(200)
    const after = tickValue * scaleOf(150)

    expect(before).toBeCloseTo(1 / 2, 10)
    expect(after).toBeCloseTo(2 / 3, 10)
    expect(after).toBeGreaterThan(before)
  })

  it('无数据时标尺为零，正上限使用倒数换算', () => {
    expect(scaleOf(0)).toBe(0)
    expect(scaleOf(-1)).toBe(0)
    expect(scaleOf(200)).toBeCloseTo(0.005, 10)
  })

  it('与柱体一致的贝塞尔缓动保持端点与单调性', () => {
    const easing = cubicBezier(0.4, 0, 0.2, 1)
    const samples = [0, 0.1, 0.25, 0.5, 0.75, 0.9, 1].map(easing)

    expect(samples[0]).toBe(0)
    expect(samples[samples.length - 1]).toBe(1)
    for (let index = 1; index < samples.length; index += 1) {
      expect(samples[index]).toBeGreaterThan(samples[index - 1])
    }
  })
})
