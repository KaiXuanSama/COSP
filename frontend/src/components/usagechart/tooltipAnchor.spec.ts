import { describe, expect, it } from 'vitest'
import { anchorFromCursor, EDGE_MARGIN, TOP_MARGIN } from './tooltipAnchor'

/**
 * 跟随光标的浮框定位验证。
 *
 * 两张图表共用这套换算，因此这里锁的是「坐标取相对值」与「贴边即翻转」两条规则 ——
 * 它们一旦漂移，浮框会在滚动后错位或在边缘处溢出卡片，而这两种情况在开发时
 * 都要把窗口调到特定尺寸才能复现。
 */

/** 造一个容器：给定它在视口中的位置与尺寸。 */
function container(left: number, top: number, width: number, height: number): HTMLElement {
  return {
    getBoundingClientRect: () => ({ left, top, width, height, right: left + width, bottom: top + height }),
  } as unknown as HTMLElement
}

/** 造一个鼠标事件：只关心视口坐标。 */
function cursor(clientX: number, clientY: number): MouseEvent {
  return { clientX, clientY } as MouseEvent
}

describe('坐标换算', () => {
  it('取容器内相对坐标而非视口坐标', () => {
    // 浮框是容器的绝对定位子元素，用视口坐标会让它在页面滚动后漂走
    const anchor = anchorFromCursor(cursor(300, 250), container(100, 200, 800, 300))

    expect(anchor.left).toBe(200)
    expect(anchor.top).toBe(50)
  })

  it('容器位于原点时相对坐标等于视口坐标', () => {
    const anchor = anchorFromCursor(cursor(300, 250), container(0, 0, 800, 300))

    expect(anchor.left).toBe(300)
    expect(anchor.top).toBe(250)
  })
})

describe('水平翻转', () => {
  it('光标远离右缘时向右展开', () => {
    const anchor = anchorFromCursor(cursor(100, 200), container(0, 0, 800, 300))

    expect(anchor.alignEnd).toBe(false)
  })

  it('光标进入右缘阈值内时改为向左展开', () => {
    // 阈值按浮框宽度设定：进入该区间后向右展开必然溢出容器
    const anchor = anchorFromCursor(cursor(800 - EDGE_MARGIN + 10, 200), container(0, 0, 800, 300))

    expect(anchor.alignEnd).toBe(true)
  })

  it('恰好在阈值边界上不翻转', () => {
    const anchor = anchorFromCursor(cursor(800 - EDGE_MARGIN, 200), container(0, 0, 800, 300))

    expect(anchor.alignEnd).toBe(false)
  })

  it('阈值可覆盖以适配不同尺寸的浮框', () => {
    const narrow = anchorFromCursor(cursor(700, 200), container(0, 0, 800, 300), { edgeMargin: 50 })
    const wide = anchorFromCursor(cursor(700, 200), container(0, 0, 800, 300), { edgeMargin: 300 })

    expect(narrow.alignEnd).toBe(false)
    expect(wide.alignEnd).toBe(true)
  })
})

describe('垂直翻转', () => {
  it('默认向上展开，不遮挡光标下方的图形', () => {
    const anchor = anchorFromCursor(cursor(400, 250), container(0, 0, 800, 300))

    expect(anchor.below).toBe(false)
  })

  it('按视口上缘而非容器上缘判断', () => {
    // 图表容器没有裁剪，浮框向上盖住卡片标题是可以接受的；
    // 若按容器算，绘图区只有两百来像素高，光标落在上半部分就会翻转 ——
    // 那等于「优先向下」，与设计意图相反。
    const nearContainerTop = anchorFromCursor(cursor(400, 500), container(0, 480, 800, 300))

    expect(nearContainerTop.top).toBe(20)
    expect(nearContainerTop.below).toBe(false)
  })

  it('光标接近屏幕顶部时才翻到下方', () => {
    const anchor = anchorFromCursor(cursor(400, TOP_MARGIN - 10), container(0, 0, 800, 300))

    expect(anchor.below).toBe(true)
  })

  it('恰好在阈值边界上不翻转', () => {
    const anchor = anchorFromCursor(cursor(400, TOP_MARGIN), container(0, 0, 800, 300))

    expect(anchor.below).toBe(false)
  })

  it('阈值可覆盖', () => {
    const anchor = anchorFromCursor(cursor(400, 80), container(0, 0, 800, 300), { topMargin: 40 })

    expect(anchor.below).toBe(false)
  })
})

describe('两个方向互不干扰', () => {
  it('屏幕右上角同时触发两种翻转', () => {
    const anchor = anchorFromCursor(cursor(790, 10), container(0, 0, 800, 300))

    expect(anchor.alignEnd).toBe(true)
    expect(anchor.below).toBe(true)
  })

  it('左下角都不翻转', () => {
    const anchor = anchorFromCursor(cursor(10, 290), container(0, 0, 800, 300))

    expect(anchor.alignEnd).toBe(false)
    expect(anchor.below).toBe(false)
  })

  it('页面下方的卡片一律向上展开', () => {
    // 实际布局里图表卡片远离屏幕顶部，因此正常使用中浮框应始终在上方
    const anchor = anchorFromCursor(cursor(400, 720), container(100, 600, 800, 300))

    expect(anchor.below).toBe(false)
  })
})
