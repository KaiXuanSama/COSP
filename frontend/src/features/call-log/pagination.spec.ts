import { describe, it, expect } from 'vitest'
import { prependWithCursorShift } from './pagination'

interface Row {
  id: number
}

const rows = (...ids: number[]): Row[] => ids.map((id) => ({ id }))

describe('prependWithCursorShift', () => {
  it('无新行时原样返回，不动游标', () => {
    const current = rows(90, 89, 88)
    const result = prependWithCursorShift(current, [], 87, true)

    expect(result.rows).toBe(current)
    expect(result.nextCursor).toBe(87)
    expect(result.hasMore).toBe(true)
  })

  it('列表为空时直接铺上，游标保持调用方传入的值', () => {
    const result = prependWithCursorShift<Row>([], rows(95, 94), null, false)

    expect(result.rows.map((r) => r.id)).toEqual([95, 94])
    expect(result.nextCursor).toBeNull()
    expect(result.hasMore).toBe(false)
  })

  it('砍尾后游标前移到新尾行，被砍的行正好是下一页开头', () => {
    // 已渲染 90..86（5 行），游标 85；新到 3 行。
    const result = prependWithCursorShift(rows(90, 89, 88, 87, 86), rows(93, 92, 91), 85, true)

    expect(result.rows.map((r) => r.id)).toEqual([93, 92, 91, 90, 89])
    // 砍掉 88/87/86，新尾行是 89 —— 下一页从 id < 89 起，正好接回 88。
    expect(result.nextCursor).toBe(89)
  })

  it('表长恒定，不随实时流增长', () => {
    const result = prependWithCursorShift(rows(50, 49, 48), rows(53, 52, 51), 47, true)

    expect(result.rows).toHaveLength(3)
    expect(result.rows.map((r) => r.id)).toEqual([53, 52, 51])
  })

  it('新行数超过表长时整表换新，游标仍落在新尾行', () => {
    const result = prependWithCursorShift(rows(20, 19), rows(25, 24, 23), 18, true)

    expect(result.rows.map((r) => r.id)).toEqual([25, 24])
    expect(result.nextCursor).toBe(24)
  })

  it('原本已到底的列表砍尾后重新有更多', () => {
    // hasMore = false 表示「已翻到库底」；尾行被移出视图后它们仍在库里。
    const result = prependWithCursorShift(rows(10, 9, 8), rows(11), null, false)

    expect(result.rows.map((r) => r.id)).toEqual([11, 10, 9])
    expect(result.hasMore).toBe(true)
    expect(result.nextCursor).toBe(9)
  })

  it('逐条插入与整批插入得到相同的最终游标', () => {
    const batch = prependWithCursorShift(rows(70, 69, 68, 67), rows(73, 72, 71), 66, true)

    let acc = { rows: rows(70, 69, 68, 67), nextCursor: 66 as number | null, hasMore: true }
    // 逐条播放动画的视角按 id 升序入场，最新的最后插到顶部。
    for (const id of [71, 72, 73]) {
      acc = prependWithCursorShift(acc.rows, rows(id), acc.nextCursor, acc.hasMore)
    }

    expect(acc.rows.map((r) => r.id)).toEqual(batch.rows.map((r) => r.id))
    expect(acc.nextCursor).toBe(batch.nextCursor)
  })
})
