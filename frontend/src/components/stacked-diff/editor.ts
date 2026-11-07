import { StateEffect, StateField, type EditorState, type Range } from '@codemirror/state'
import { Decoration, EditorView, WidgetType, type BlockInfo, type DecorationSet } from '@codemirror/view'
import type { DiffDisplayLine, LineScrollPort, MeasuredTextLine } from './lines'
import { anchorAtScroll, scrollAtAnchor } from './lines'

export const setComparisonLines = StateEffect.define<readonly DiffDisplayLine[] | null>()

/** 不属于文档的块占位；只接受不可编辑、不可复制的视觉展示。 */
export class ComparisonPlaceholder extends WidgetType {
  constructor(readonly rows: readonly DiffDisplayLine[]) { super() }
  /** 离屏时先预留接近一行的高度，进入视口后由真实字体布局替代。 */
  get estimatedHeight() { return this.rows.length * 20 }
  eq(other: ComparisonPlaceholder) {
    return this.rows.length === other.rows.length
      && this.rows.every((row, index) => row.id === other.rows[index]!.id
        && row.virtualLineNumber === other.rows[index]!.virtualLineNumber)
  }
  toDOM(view: EditorView) {
    const block = view.dom.ownerDocument.createElement('div')
    block.className = 'diff-editor-placeholder-block'
    block.setAttribute('aria-hidden', 'true')
    for (const row of this.rows) {
      const line = block.ownerDocument.createElement('div')
      line.className = 'diff-editor-placeholder'
      line.dataset.virtualLine = String(row.virtualLineNumber)
      block.append(line)
    }
    return block
  }
  ignoreEvent() { return true }
}

interface ComparisonState {
  rows: readonly DiffDisplayLine[] | null
  decorations: DecorationSet
}

function comparisonState(state: EditorState, rows: readonly DiffDisplayLine[] | null): ComparisonState {
  if (!rows) return { rows, decorations: Decoration.none }
  const decorations: Range<Decoration>[] = []
  let placeholders: DiffDisplayLine[] = []
  function flush(position: number, side: number) {
    if (!placeholders.length) return
    decorations.push(Decoration.widget({ widget: new ComparisonPlaceholder(placeholders), block: true, side }).range(position))
    placeholders = []
  }
  for (const row of rows) {
    if (row.placeholder) { placeholders.push(row); continue }
    if (row.lineNumber === null || row.lineNumber > state.doc.lines) continue
    const line = state.doc.line(row.lineNumber)
    flush(line.from, -1)
    decorations.push(Decoration.line({ attributes: {
      'data-virtual-line': String(row.virtualLineNumber),
      'data-line-number': String(row.lineNumber),
    } }).range(line.from))
  }
  flush(state.doc.length, 1)
  return { rows, decorations: Decoration.set(decorations, true) }
}

export const comparisonField = StateField.define<ComparisonState>({
  create: state => comparisonState(state, null),
  update(value, transaction) {
    // 新草稿一开始就移除旧映射，占位绝不能跟着旧位置落入下一份草稿。
    let rows = transaction.docChanged ? null : value.rows
    for (const effect of transaction.effects) if (effect.is(setComparisonLines)) rows = effect.value
    return rows === value.rows && !transaction.docChanged ? value : comparisonState(transaction.state, rows)
  },
  provide: field => EditorView.decorations.from(field, value => value.decorations),
})

function textBlock(block: BlockInfo): BlockInfo {
  return Array.isArray(block.type) ? block.type.find(part => !part.widget) ?? block : block
}

/** 使用 CodeMirror 的布局索引；离屏内容无需完整渲染或建立第二份文本镜像。 */
export function createEditorScrollPort(view: EditorView): LineScrollPort & { layoutChanged(): void } {
  const listeners = new Set<() => void>()
  let disposed = false
  let measured: MeasuredTextLine[] | null = null
  const notify = () => listeners.forEach(listener => listener())
  function measure(): MeasuredTextLine[] {
    if (measured) return measured
    const rows = view.state.field(comparisonField).rows
    const padding = view.documentPadding.top
    const scale = view.scaleY || 1
    const lines: MeasuredTextLine[] = []
    function add(line: number, block: BlockInfo) {
      lines.push({ line, top: padding + block.top / scale, height: block.height / scale })
    }
    if (!rows) {
      for (let number = 1; number <= view.state.doc.lines; number++) {
        add(number, textBlock(view.lineBlockAt(view.state.doc.line(number).from)))
      }
    } else {
      for (let index = 0; index < rows.length;) {
        const row = rows[index]!
        if (!row.placeholder && row.lineNumber !== null) {
          add(row.virtualLineNumber!, textBlock(view.lineBlockAt(view.state.doc.line(row.lineNumber).from)))
          index++
          continue
        }
        const start = index
        while (index < rows.length && rows[index]!.placeholder) index++
        const count = index - start
        if (!count) { index++; continue }
        const next = rows[index]
        const reference = next?.lineNumber != null
          ? view.lineBlockAt(view.state.doc.line(next.lineNumber).from) : view.lineBlockAt(view.state.doc.length)
        const parts = Array.isArray(reference.type) ? reference.type : [reference]
        const widget = parts.find(part => part.widget instanceof ComparisonPlaceholder
          && part.widget.rows[0]?.virtualLineNumber === row.virtualLineNumber)
        const height = widget ? widget.height / scale / count : view.defaultLineHeight
        const top = widget ? widget.top / scale
          : next ? textBlock(reference).top / scale - height * count : textBlock(reference).bottom / scale
        for (let offset = 0; offset < count; offset++) {
          lines.push({ line: rows[start + offset]!.virtualLineNumber!, top: padding + top + offset * height, height })
        }
      }
    }
    measured = lines
    return lines
  }
  const port = {
    readAnchor: () => anchorAtScroll(measure(), view.scrollDOM.scrollTop),
    writeAnchor(anchor: Parameters<LineScrollPort['writeAnchor']>[0]) {
      const maximum = Math.max(0, view.scrollDOM.scrollHeight - view.scrollDOM.clientHeight)
      const target = Math.max(0, Math.min(maximum, scrollAtAnchor(measure(), anchor)))
      if (Math.abs(target - view.scrollDOM.scrollTop) >= 0.5) view.scrollDOM.scrollTop = target
      return view.scrollDOM.scrollTop
    },
    scrollTop: () => view.scrollDOM.scrollTop,
    onScroll(listener: () => void) { listeners.add(listener); return () => { listeners.delete(listener) } },
    refresh() { measured = null },
    layoutChanged() { measured = null; if (!disposed) notify() },
    dispose() {
      disposed = true
      view.scrollDOM.removeEventListener('scroll', notify)
      listeners.clear()
    },
  }
  view.scrollDOM.addEventListener('scroll', notify, { passive: true })
  return port
}