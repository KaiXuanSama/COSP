import { describe, expect, it } from 'vitest'
import { createVirtualLineComparison } from './comparison'
import { compileLineMap, type DiffDisplayLine } from './lines'

function line(id: string, source: number | null, result: number | null, text = id): DiffDisplayLine {
  return { id, sourceLineNumber: source, lineNumber: result, text, status: source === null ? 'added' : result === null ? 'deleted' : 'same' }
}

describe('共享虚拟对比行', () => {
  it('删除和连续新增都有独立计算行号，真实行号保持各侧含义', () => {
    const view = createVirtualLineComparison('a\r\ndrop\r\nb\r\nc', [
      line('a', 1, 1), line('drop', 2, null), line('b', 3, 2),
      line('new-1', null, 3), line('new-2', null, 4), line('c', 4, 5),
    ])
    expect(view.sourceLines.map(row => row.lineNumber)).toEqual([1, 2, 3, null, null, 4])
    expect(view.resultLines.map(row => row.lineNumber)).toEqual([1, null, 2, 3, 4, 5])
    expect(view.sourceLines.map(row => row.virtualLineNumber)).toEqual([1, 2, 3, 4, 5, 6])
    expect(view.resultLines.map(row => row.virtualLineNumber)).toEqual([1, 2, 3, 4, 5, 6])
    expect(view.sourceLines.filter(row => row.placeholder).map(row => row.id)).toEqual(['new-1', 'new-2'])
    const translate = compileLineMap(view.virtualLineMap)
    for (const number of [2, 4, 5]) {
      expect(translate('source', { line: number, fraction: 0.6 })).toEqual({ line: number, fraction: expect.closeTo(0.6) })
      expect(translate('result', { line: number, fraction: 0.4 })).toEqual({ line: number, fraction: expect.closeTo(0.4) })
    }
  })

  it('首尾新增和原文空行均占独立对比行，不增改任一侧真实文档', () => {
    const source = '\nkeep\n'
    const view = createVirtualLineComparison(source, [line('head', null, 1), line('keep', 2, 2), line('tail', null, 3)])
    expect(view.sourceLines.filter(row => !row.placeholder).map(row => row.text).join('\n')).toBe(source)
    expect(view.resultLines.filter(row => !row.placeholder).map(row => row.text)).toEqual(['head', 'keep', 'tail'])
    expect(view.resultLines.filter(row => row.placeholder)).toHaveLength(2)
    expect(view.sourceLines.filter(row => row.placeholder)).toHaveLength(2)
  })

  it('紧凑行一对多只保留一份原文，其余位置为展示占位', () => {
    const source = '{"a":1,"drop":2}'
    const view = createVirtualLineComparison(source, [line('open', 1, 1), line('drop', 1, null), line('a', 1, 2), line('close', 1, 3)])
    expect(view.sourceLines.map(row => row.lineNumber)).toEqual([1, null, null, null])
    expect(view.sourceLines.filter(row => !row.placeholder).map(row => row.text)).toEqual([source])
    expect(view.resultLines[1]?.virtualLineNumber).toBe(2)
  })

  it('调序的对应不反向滚动，所有真实源行仍按原顺序且只出现一次', () => {
    const view = createVirtualLineComparison('a\nb\nc\nd', [line('c', 3, 1), line('a', 1, 2), line('b', 2, 3), line('d', 4, 4)])
    expect(view.sourceLines.filter(row => row.lineNumber).map(row => row.lineNumber)).toEqual([1, 2, 3, 4])
    expect(view.resultLines.filter(row => !row.placeholder).map(row => row.id)).toEqual(['c', 'a', 'b', 'd'])
    expect(new Set(view.sourceLines.map(row => row.virtualLineNumber)).size).toBe(view.sourceLines.length)
  })
})