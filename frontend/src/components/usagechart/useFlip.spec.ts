import { describe, expect, it, vi } from 'vitest'
import { useFlip } from './useFlip'

/**
 * FLIP 位移动画的验证与锁定。
 *
 * 测试环境是 Node（无 jsdom），因此这里用最小的假元素替代真实 DOM：
 * 只实现 useFlip 实际用到的那几个成员（getAttribute / getBoundingClientRect /
 * style / offsetWidth）。这样测的是**位移计算与留存判断**这些纯逻辑，
 * 而真实的过渡播放属于浏览器行为，交由手动验证。
 */

/** 假元素：位置可变，style 变更被记录下来供断言。 */
function fakeBar(key: string, left: number, top = 0) {
  const style: Record<string, string> = { transition: '', transform: '' }
  return {
    key,
    left,
    top,
    getAttribute: (name: string) => (name === 'data-flip-key' ? key : null),
    getBoundingClientRect() {
      return { left: this.left, top: this.top } as DOMRect
    },
    style,
    offsetWidth: 0,
    /** 移到新位置，模拟 flex 重排。 */
    moveTo(nextLeft: number) {
      this.left = nextLeft
    },
  }
}

/** 假容器：querySelectorAll 直接返回当前柱子列表。 */
function fakeContainer(bars: ReturnType<typeof fakeBar>[]) {
  const container = {
    bars,
    offsetWidth: 0,
    querySelectorAll: () => container.bars,
  }
  return container
}

/** 组装一个 useFlip 实例与它的假 DOM。 */
function setup(bars: ReturnType<typeof fakeBar>[]) {
  const container = fakeContainer(bars)
  // 假元素只实现了 useFlip 用到的成员，故此处断言类型
  const flip = useFlip(() => container as unknown as HTMLElement, '[data-flip-key]')
  return { flip, container }
}

describe('FLIP 位移动画', () => {
  it('首次渲染没有基线，交给入场动画', () => {
    const { flip } = setup([fakeBar('a', 0)])

    // 未调用 snapshot 就 play：无旧位置可比
    expect(flip.play()).toBe(false)
  })

  it('身份留存且位置变化时补偿位移并返回 true', () => {
    const bar = fakeBar('a', 100)
    const { flip } = setup([bar])

    flip.snapshot()
    bar.moveTo(300)

    expect(flip.play()).toBe(true)
    // Invert：先被拉回旧位置（100 - 300 = -200），随后过渡回 0
    // play() 同步执行完时 transform 已清空，故这里断言过渡已挂上
    expect(bar.style.transition).toContain('transform')
  })

  it('全是新柱子时返回 false，让入场动画接管', () => {
    const oldBar = fakeBar('old', 0)
    const { flip, container } = setup([oldBar])

    flip.snapshot()
    // 整批换成不同身份（模拟跨层级：日期 -> 供应商）
    container.bars = [fakeBar('new-1', 0), fakeBar('new-2', 100)]

    expect(flip.play()).toBe(false)
  })

  it('部分留存即视为留存，不重播入场', () => {
    const kept = fakeBar('kept', 0)
    const { flip, container } = setup([kept, fakeBar('gone', 100)])

    flip.snapshot()
    kept.moveTo(50)
    container.bars = [kept, fakeBar('added', 150)]

    expect(flip.play()).toBe(true)
  })

  it('位置未变时不加过渡，但仍算留存', () => {
    const bar = fakeBar('a', 100)
    const { flip } = setup([bar])

    flip.snapshot()
    // 不移动

    // 留存 -> true（不该重播入场），但无需动画
    expect(flip.play()).toBe(true)
    expect(bar.style.transition).toBe('')
  })

  it('亚像素位移被忽略，避免无谓的合成层开销', () => {
    const bar = fakeBar('a', 100)
    const { flip } = setup([bar])

    flip.snapshot()
    bar.moveTo(100.3)

    expect(flip.play()).toBe(true)
    expect(bar.style.transition).toBe('')
  })

  it('snapshot 会覆盖上一次基线，避免用过期位置算位移', () => {
    const bar = fakeBar('a', 0)
    const { flip } = setup([bar])

    flip.snapshot()
    bar.moveTo(100)
    flip.snapshot() // 以 100 为新基线
    bar.moveTo(100) // 相对新基线没动

    expect(flip.play()).toBe(true)
    expect(bar.style.transition).toBe('')
  })

  it('play 后基线被清空，重复 play 不会再次动画', () => {
    const bar = fakeBar('a', 0)
    const { flip } = setup([bar])

    flip.snapshot()
    bar.moveTo(200)
    flip.play()

    // 第二次没有基线：等同首次渲染
    expect(flip.play()).toBe(false)
  })

  it('容器不存在时静默跳过，不抛异常', () => {
    const flip = useFlip(() => null, '[data-flip-key]')

    expect(() => flip.snapshot()).not.toThrow()
    expect(flip.play()).toBe(false)
  })

  it('过渡结束后清理内联样式，不残留影响后续布局', () => {
    vi.useFakeTimers()
    try {
      const bar = fakeBar('a', 0)
      const { flip } = setup([bar])

      flip.snapshot()
      bar.moveTo(200)
      flip.play()

      expect(bar.style.transition).toContain('transform')

      // 快进过渡时长，清理定时器应已执行
      vi.advanceTimersByTime(500)

      expect(bar.style.transition).toBe('')
      expect(bar.style.transform).toBe('')
    } finally {
      vi.useRealTimers()
    }
  })
})
