import { describe, expect, it } from 'vitest'
import { EditorState, Transaction } from '@codemirror/state'
import { history, redo, undo } from '@codemirror/commands'
import { comparisonField, setComparisonLines } from './editor'
import { createVirtualLineComparison } from './comparison'

const comparison = createVirtualLineComparison('first\nlast', [
  { id: 'first', text: 'first', sourceLineNumber: 1, lineNumber: 1, status: 'same' },
  { id: 'added', text: 'inserted', sourceLineNumber: null, lineNumber: 2, status: 'added' },
  { id: 'last', text: 'last', sourceLineNumber: 2, lineNumber: 3, status: 'same' },
])

describe('编辑器展示占位与文档隔离', () => {
  it('插入和移除条纹装饰不改文本、选区，也不产生撤销步骤', () => {
    let state = EditorState.create({ doc: 'first\nlast', selection: { anchor: 3, head: 8 }, extensions: [comparisonField, history()] })
    const selection = state.selection.toJSON()
    state = state.update({ effects: setComparisonLines.of(comparison.sourceLines), annotations: Transaction.addToHistory.of(false) }).state
    expect(state.sliceDoc()).toBe('first\nlast')
    expect(state.selection.toJSON()).toEqual(selection)
    expect(state.field(comparisonField).decorations.size).toBe(3)
    expect(undo({ state, dispatch() { throw new Error('装饰不应产生撤销步骤') } })).toBe(false)
    state = state.update({ effects: setComparisonLines.of(null) }).state
    expect(state.field(comparisonField).decorations.size).toBe(0)
    expect(state.sliceDoc()).toBe('first\nlast')
  })

  it('输入立即移除旧占位，跨占位删除和撤销重做只操作真实字符', () => {
    let state = EditorState.create({ doc: 'first\nlast', extensions: [comparisonField, history()] })
    state = state.update({ effects: setComparisonLines.of(comparison.sourceLines) }).state
    state = state.update({ changes: { from: 2, to: 8, insert: 'NEW' }, annotations: Transaction.userEvent.of('input') }).state
    expect(state.sliceDoc()).toBe('fiNEWst')
    expect(state.field(comparisonField).rows).toBeNull()
    const dispatch = (transaction: Transaction) => { state = transaction.state }
    expect(undo({ state, dispatch })).toBe(true)
    expect(state.sliceDoc()).toBe('first\nlast')
    expect(redo({ state, dispatch })).toBe(true)
    expect(state.sliceDoc()).toBe('fiNEWst')
  })

  it('CRLF 草稿和末尾空行原样保留，展示占位不会进入复制内容', () => {
    const draft = 'first\r\nlast\r\n'
    let state = EditorState.create({ doc: draft, extensions: [EditorState.lineSeparator.of('\r\n'), comparisonField] })
    state = state.update({ effects: setComparisonLines.of(comparison.sourceLines) }).state
    expect(state.sliceDoc()).toBe(draft)
    expect(state.doc.lines).toBe(3)
  })
})