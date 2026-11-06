import { describe, expect, it } from 'vitest'
import { createJsonLineComparison, resolvePreviewLines } from './jsonLines'
import { compileLineMap } from '../../components/stacked-diff/lines'

describe('JSON 真实草稿与差异行', () => {
  it('保留空行、缩进及 CRLF，源行号按真实草稿定位', () => {
    const source = '{\r\n\r\n\t"keep": 1,\r\n\t"remove": 2\r\n}\r\n'
    const comparison = createJsonLineComparison(source, { keep: 1 })
    expect(comparison.sourceText).toBe(source)
    expect(comparison.lineMap.sourceLineCount).toBe(6)
    const keep = comparison.lineMap.pairs.find(pair => pair.id === '$/"keep"')!
    expect(keep.source).toEqual({ start: 3, end: 3 })
    expect(keep.result).toEqual({ start: 2, end: 2 })
    expect(comparison.resultLines.find(line => line.status === 'deleted')?.lineNumber).toBeNull()
    expect(comparison.resultText).toBe('{\n  "keep": 1\n}')
  })

  it('数组头部和中部删除后仍将相同元素映射到真实新位置', () => {
    const source = JSON.stringify({ list: ['drop-head', { id: 1, text: 'a' }, 'drop-middle', { id: 2, text: 'b' }] }, null, 2)
    const comparison = createJsonLineComparison(source, { list: [{ id: 1, text: 'a' }, { id: 2, text: 'b' }] })
    const first = comparison.lineMap.pairs.find(pair => pair.id.includes('[1:0]') && pair.id.endsWith('/"text"'))!
    const second = comparison.lineMap.pairs.find(pair => pair.id.includes('[3:1]') && pair.id.endsWith('/"text"'))!
    expect(first.status).toBe('same')
    expect(second.status).toBe('same')
    const translate = compileLineMap(comparison.lineMap)
    expect(translate('source', { line: second.source!.start, fraction: 0 })?.line).toBe(second.result!.start)
    expect(comparison.resultLines.filter(line => line.status === 'deleted')).toHaveLength(2)
  })

  it('新增、改写、删除各自归类，复制文本仅为真实结果', () => {
    const source = '{"mode":"a","remove":true,"keep":0}'
    const comparison = createJsonLineComparison(source, { mode: 'b', keep: 0, added: null })
    expect(comparison.lineMap.pairs.find(pair => pair.id.endsWith('/"mode"'))?.status).toBe('changed')
    expect(comparison.lineMap.pairs.find(pair => pair.id.endsWith('/"remove"'))?.result).toBeNull()
    expect(comparison.lineMap.pairs.find(pair => pair.id.endsWith('/"added"'))?.source).toBeNull()
    expect(JSON.parse(comparison.resultText)).toEqual({ mode: 'b', keep: 0, added: null })
    expect(comparison.sourceText).toBe(source)
  })

  it('紧凑 JSON 和整段结构替换支持一对多行范围', () => {
    const source = '{"item":{"nested":1}}'
    const comparison = createJsonLineComparison(source, { item: ['one', 'two'] })
    const item = comparison.lineMap.pairs.find(pair => pair.id.endsWith('/"item"'))!
    expect(item.source).toEqual({ start: 1, end: 1 })
    expect(item.result).toEqual({ start: 2, end: 5 })
    expect(item.status).toBe('changed')
  })

  it('拒绝非 JSON、注释、尾逗号与重复字段的虚假行对应', () => {
    for (const text of ['{', '{"a":1,}', '{/*comment*/"a":1}', '{"a":1,"a":2}']) {
      expect(() => createJsonLineComparison(text, { a: 1 })).toThrow()
    }
  })

  it('格式变化可以重新定位，语义变化或无效输入暂停联动并保留旧结果', () => {
    const snapshot = { input: { previewBody: { a: 1 }, sourceText: '{"a":1}' }, result: { output: { a: 2 } } }
    const formatted = resolvePreviewLines('{\n\n  "a": 1\n}', snapshot)
    expect(formatted.notice).toBe('')
    expect(formatted.lineMap?.pairs.find(pair => pair.id.endsWith('/"a"'))?.source?.start).toBe(3)
    for (const draft of ['{', '{"a":3}']) {
      const view = resolvePreviewLines(draft, snapshot)
      expect(view.lineMap).toBeNull()
      expect(view.notice).not.toBe('')
      expect(view.comparison?.resultText).toBe('{\n  "a": 2\n}')
    }
    expect(resolvePreviewLines('{"a":1}', snapshot, true).lineMap).toBeNull()
    expect(resolvePreviewLines('{"a":1}', null).comparison).toBeNull()
  })

  it('紧凑 JSON 删除痕迹只包含被删字段，同一源行能映射完整输出', () => {
    const source = '{"a":1,"removed":true,"item":{"x":2}}'
    const comparison = createJsonLineComparison(source, { a: 1, item: { x: 3 } })
    expect(comparison.resultLines.filter(line => line.status === 'deleted').map(line => line.text))
      .toEqual(['"removed":true,'])
    const translate = compileLineMap(comparison.lineMap)
    expect(translate('source', { line: 1, fraction: 0.75 })).toEqual({ line: 5, fraction: 0.5 })
    expect(comparison.resultLines.every(line => line.status === 'deleted' || line.sourceLineNumber === 1)).toBe(true)
  })

  it('删除多行对象保留其自身缩进和括号，不带上紧凑行中的相邻字段', () => {
    const source = '{\n  "keep":1,"drop": {\n    "value": 2\n  },"tail":3\n}'
    const comparison = createJsonLineComparison(source, { keep: 1, tail: 3 })
    expect(comparison.resultLines.filter(line => line.status === 'deleted').map(line => line.text))
      .toEqual(['  "drop": {', '    "value": 2', '  },'])
    expect(JSON.parse(comparison.resultText)).toEqual({ keep: 1, tail: 3 })
  })

  it('深层重复字段即使整个容器被新增或删除也拒绝可靠对齐', () => {
    expect(() => createJsonLineComparison('{"drop":{"a":1,"a":2}}', {})).toThrow('重复字段')
    const deep = `${'{"value":'.repeat(258)}0${'}'.repeat(258)}`
    expect(() => createJsonLineComparison(deep, {})).toThrow('嵌套过深')
  })

  it('结果字段的显示标识按字段路径稳定，前部新增不改变原字段标识', () => {
    const source = '{\n  "keep": 1\n}'
    const first = createJsonLineComparison(source, { keep: 1 })
    const second = createJsonLineComparison(source, { added: 2, keep: 1 })
    const idForKeep = (lines: typeof first.resultLines) => lines.find(line => line.text.includes('"keep"'))!.id
    expect(idForKeep(second.resultLines)).toBe(idForKeep(first.resultLines))
    expect(new Set(second.resultLines.map(line => line.id)).size).toBe(second.resultLines.length)
  })

  it('成功输入快照含重复字段时仍保留结果展示，但不映射到规范副本行', () => {
    const draft = '{"a":1,"a":2}'
    const snapshot = { input: { previewBody: { a: 2 }, sourceText: draft }, result: { output: { a: 3 } } }
    const view = resolvePreviewLines(draft, snapshot)
    expect(view.lineMap).toBeNull()
    expect(view.notice).toContain('重复字段')
    expect(view.comparison?.resultText).toBe('{\n  "a": 3\n}')
  })
})
