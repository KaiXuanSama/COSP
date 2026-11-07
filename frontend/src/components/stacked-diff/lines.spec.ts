import { describe, expect, it } from 'vitest'
import { anchorAtScroll, compileLineMap, scrollAtAnchor, type TextLineMap } from './lines'

const map: TextLineMap = {
  sourceLineCount: 12, resultLineCount: 10,
  pairs: [
    { id: 'head', source: { start: 1, end: 2 }, result: { start: 1, end: 2 }, status: 'same' },
    { id: 'drop', source: { start: 3, end: 5 }, result: null, status: 'deleted' },
    { id: 'body', source: { start: 6, end: 10 }, result: { start: 3, end: 7 }, status: 'same' },
    { id: 'new', source: null, result: { start: 8, end: 9 }, status: 'added' },
    { id: 'tail', source: { start: 11, end: 12 }, result: { start: 10, end: 10 }, status: 'changed' },
  ],
}

describe('逻辑行锚点', () => {
  it('增删之后通过对应行同步，不能使用总高度比例', () => {
    const translate = compileLineMap(map)
    expect(translate('source', { line: 8, fraction: 0.5 })).toEqual({ line: 5, fraction: 0.5 })
    expect(translate('result', { line: 5, fraction: 0.5 })).toEqual({ line: 8, fraction: 0.5 })
  })

  it('连续删除和新增行固定到邻近有效边界', () => {
    const translate = compileLineMap(map)
    expect(translate('source', { line: 3, fraction: 0.5 })?.line).toBe(2)
    expect(translate('source', { line: 5, fraction: 0.5 })).toEqual({ line: 3, fraction: 0 })
    expect(translate('result', { line: 9, fraction: 0.5 })).toEqual({ line: 11, fraction: 0 })
  })

  it('结构替换支持多行到单行及反向对应', () => {
    const translate = compileLineMap(map)
    expect(translate('source', { line: 12, fraction: 0 })).toEqual({ line: 10, fraction: 0.5 })
    expect(translate('result', { line: 10, fraction: 0.5 })).toEqual({ line: 12, fraction: 0 })
  })

  it('无共同锚点时拒绝比例猜测', () => {
    const translate = compileLineMap({ sourceLineCount: 1, resultLineCount: 1, pairs: [] })
    expect(translate('source', { line: 1, fraction: 0 })).toBeNull()
  })

  it('软换行高度只影响行内偏移，逻辑行号不变', () => {
    const measured = [
      { line: 1, top: 16, height: 20 },
      { line: 2, top: 36, height: 80 },
      { line: 3, top: 116, height: 20 },
    ]
    expect(anchorAtScroll(measured, 76)).toEqual({ line: 2, fraction: 0.5 })
    expect(scrollAtAnchor(measured, { line: 2, fraction: 0.5 })).toBe(76)
    expect(anchorAtScroll(measured, 0)).toEqual({ line: 1, fraction: 0 })
    expect(scrollAtAnchor(measured, anchorAtScroll(measured, 0))).toBe(0)
  })

  it('多个字段共享同一源行时，整行对应完整结果范围而非永远落到首行', () => {
    const translate = compileLineMap({ sourceLineCount: 1, resultLineCount: 6, pairs: [
      { id: 'open', source: { start: 1, end: 1 }, result: { start: 1, end: 1 }, status: 'changed' },
      { id: 'field', source: { start: 1, end: 1 }, result: { start: 2, end: 5 }, status: 'changed' },
      { id: 'close', source: { start: 1, end: 1 }, result: { start: 6, end: 6 }, status: 'changed' },
    ] })
    expect(translate('source', { line: 1, fraction: 0.75 })).toEqual({ line: 5, fraction: 0.5 })
    expect(translate('result', { line: 5, fraction: 0.5 })).toEqual({ line: 1, fraction: 0.75 })
  })

  it('空容器结果在单行时也将原始多行首尾组成反向区间', () => {
    const translate = compileLineMap({ sourceLineCount: 4, resultLineCount: 1, pairs: [
      { id: 'open', source: { start: 1, end: 1 }, result: { start: 1, end: 1 }, status: 'changed' },
      { id: 'deleted', source: { start: 2, end: 3 }, result: null, status: 'deleted' },
      { id: 'close', source: { start: 4, end: 4 }, result: { start: 1, end: 1 }, status: 'changed' },
    ] })
    expect(translate('source', { line: 3, fraction: 0 })).toEqual({ line: 1, fraction: 0.5 })
    expect(translate('result', { line: 1, fraction: 0.5 })).toEqual({ line: 3, fraction: 0 })
  })

  it('未排序的对应仍可查询首尾和间隙，等距时固定使用前一个锚点', () => {
    const translate = compileLineMap({ ...map, pairs: [...map.pairs].reverse() })
    expect(translate('source', { line: 1, fraction: 0 })).toEqual({ line: 1, fraction: 0 })
    expect(translate('source', { line: 4, fraction: 0 })?.line).toBe(2)
    expect(translate('source', { line: 1000, fraction: 0 })).toEqual({ line: 10, fraction: 0.5 })
  })

  it('重叠组的包络再次覆盖中间组时继续归并，二分查找不跳到错误末端', () => {
    const translate = compileLineMap({ sourceLineCount: 3, resultLineCount: 5, pairs: [
      { id: 'open', source: { start: 1, end: 1 }, result: { start: 1, end: 1 }, status: 'changed' },
      { id: 'close', source: { start: 3, end: 3 }, result: { start: 1, end: 1 }, status: 'changed' },
      { id: 'middle', source: { start: 2, end: 2 }, result: { start: 5, end: 5 }, status: 'changed' },
    ] })
    expect(translate('source', { line: 3, fraction: 0 })?.line).toBe(4)
    expect(translate('result', { line: 5, fraction: 0.5 })?.line).toBe(3)
  })
})
