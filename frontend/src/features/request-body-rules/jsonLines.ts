/** JSON 展示适配：只定位和比较文本，不执行任何请求体规则。 */
import { getNodeValue, parseTree, type Node, type ParseError } from 'jsonc-parser'
import type { DiffDisplayLine, TextDiffStatus, TextLineMap, TextLinePair, TextLineRange } from '../../components/stacked-diff/lines'
import { compileLineMap } from '../../components/stacked-diff/lines'
import { createVirtualLineComparison, type VirtualLineComparison } from '../../components/stacked-diff/comparison'
import { alignArrayElements, jsonEquals } from './diff'

interface JsonDocument {
  text: string
  root: Node
  starts: number[]
  lines: string[]
}

export interface JsonLineComparison extends VirtualLineComparison {
  sourceText: string
  resultText: string
  lineMap: TextLineMap
}

function documentFor(text: string): JsonDocument {
  // JSON.parse 保持严格 JSON；位置解析器不能悄悄放行注释、尾逗号或不完整输入。
  JSON.parse(text)
  const errors: ParseError[] = []
  const root = parseTree(text, errors, { disallowComments: true, allowTrailingComma: false })
  if (!root || errors.length) throw new Error('无法定位 JSON 文本行')
  const pending = [{ node: root, depth: 0 }]
  while (pending.length) {
    const { node, depth } = pending.pop()!
    if (depth > 256) throw new Error('JSON 嵌套过深，已暂停行对齐')
    if (node.type === 'object') {
      for (const child of fields(node).values()) pending.push({ node: child, depth: depth + 1 })
    } else if (node.type === 'array') {
      for (const child of node.children ?? []) pending.push({ node: child, depth: depth + 1 })
    }
  }
  const starts = [0]
  for (let index = 0; index < text.length; index++) if (text[index] === '\n') starts.push(index + 1)
  return { text, root, starts, lines: text.split(/\r?\n/) }
}

function lineAt(doc: JsonDocument, offset: number): number {
  let low = 0
  let high = doc.starts.length - 1
  while (low < high) {
    const middle = Math.ceil((low + high) / 2)
    if (doc.starts[middle]! <= offset) low = middle
    else high = middle - 1
  }
  return low + 1
}

function firstOffset(node: Node): number {
  return node.parent?.type === 'property' ? node.parent.offset : node.offset
}

function range(doc: JsonDocument, node: Node): TextLineRange {
  return { start: lineAt(doc, firstOffset(node)), end: lineAt(doc, node.offset + node.length - 1) }
}

function fields(node: Node): Map<string, Node> {
  const result = new Map<string, Node>()
  for (const property of node.children ?? []) {
    const key = String(property.children?.[0]?.value)
    const value = property.children?.[1]
    if (result.has(key)) throw new Error(`JSON 存在重复字段「${key}」，已暂停行对齐`)
    if (value) result.set(key, value)
  }
  return result
}

/** 删除痕迹只取该节点的词法范围，不把同一紧凑行上的其他字段一起划掉。 */
function deletedText(doc: JsonDocument, node: Node): string[] {
  const start = firstOffset(node)
  const end = node.offset + node.length
  const firstLine = doc.lines[lineAt(doc, start) - 1]!
  const prefix = doc.text.slice(doc.starts[lineAt(doc, start) - 1]!, start)
  const indent = /^[\t ]*,?[\t ]*$/.test(prefix) ? prefix : firstLine.match(/^[\t ]*/)?.[0] ?? ''
  const suffix = doc.text.slice(end, doc.text.indexOf('\n', end) < 0 ? doc.text.length : doc.text.indexOf('\n', end))
    .match(/^[\t ]*,/)?.[0] ?? ''
  return (indent + doc.text.slice(start, end) + suffix).split(/\r?\n/)
}

/** sourceText 原样保留；output 是成功预览的真实输出，复制使用 resultText。 */
export function createJsonLineComparison(sourceText: string, output: unknown): JsonLineComparison {
  const resultText = JSON.stringify(output, null, 2)
  if (resultText === undefined) throw new Error('预览结果不是有效 JSON')
  const source = documentFor(sourceText)
  const result = documentFor(resultText)
  const pairs: TextLinePair[] = []
  const values = new WeakMap<Node, unknown>()
  const value = (node: Node) => {
    if (!values.has(node)) values.set(node, getNodeValue(node))
    return values.get(node)
  }
  const statuses = new Map<number, TextDiffStatus>()
  const deleted = new Map<number, DiffDisplayLine[]>()
  const deletedNodes = new Map<string, Node>()

  function add(id: string, left: TextLineRange | null, right: TextLineRange | null, status: TextDiffStatus) {
    pairs.push({ id, source: left, result: right, status })
    if (right && status !== 'same') {
      for (let line = right.start; line <= right.end; line++) statuses.set(line, status)
    }
  }

  function visit(left: Node | null, right: Node | null, path: string, depth: number) {
    if (depth > 256) throw new Error('JSON 嵌套过深，已暂停行对齐')
    if (!left && right) { add(path, null, range(result, right), 'added'); return }
    if (left && !right) {
      add(path, range(source, left), null, 'deleted')
      deletedNodes.set(path, left)
      return
    }
    if (!left || !right) return
    const status = jsonEquals(value(left), value(right)) ? 'same' : 'changed'
    if (left.type !== right.type || (left.type !== 'object' && left.type !== 'array')) {
      add(path, range(source, left), range(result, right), status)
      return
    }
    const leftRange = range(source, left)
    const rightRange = range(result, right)
    add(`${path}:open`, { start: leftRange.start, end: leftRange.start }, { start: rightRange.start, end: rightRange.start }, status)
    if (left.type === 'object') {
      const a = fields(left)
      const b = fields(right)
      for (const key of new Set([...a.keys(), ...b.keys()])) {
        visit(a.get(key) ?? null, b.get(key) ?? null, `${path}/${JSON.stringify(key)}`, depth + 1)
      }
    } else {
      const a = left.children ?? []
      const b = right.children ?? []
      for (const pair of alignArrayElements(a.map(value), b.map(value))) {
        visit(pair.original === null ? null : a[pair.original]!, pair.current === null ? null : b[pair.current]!,
          `${path}/[${pair.original ?? '+'}:${pair.current ?? '-'}]`, depth + 1)
      }
    }
    add(`${path}:close`, { start: leftRange.end, end: leftRange.end }, { start: rightRange.end, end: rightRange.end }, status)
  }

  visit(source.root, result.root, '$', 0)
  const nextResultLines: number[] = []
  let nextResultLine = result.lines.length + 1
  for (let index = pairs.length - 1; index >= 0; index--) {
    nextResultLines[index] = nextResultLine
    if (pairs[index]!.result) nextResultLine = pairs[index]!.result!.start
  }
  const owners = new Array<TextLinePair | null>(result.lines.length + 1).fill(null)
  let previousResultLine = 0
  for (let index = 0; index < pairs.length; index++) {
    const pair = pairs[index]!
    if (pair.result) {
      for (let line = pair.result.start; line <= pair.result.end; line++) {
        owners[line] ??= pair
      }
      previousResultLine = Math.max(previousResultLine, pair.result.end)
    }
    if (pair.status !== 'deleted' || !pair.source) continue
    const sourceLines = deletedText(source, deletedNodes.get(pair.id)!)
    // 空容器首尾共用同一结果行时，删除痕迹放在已展示的容器行后，避免倒置源行顺序。
    const before = Math.min(result.lines.length + 1, Math.max(previousResultLine + 1, nextResultLines[index]!))
    const block = deleted.get(before) ?? []
    block.push(...sourceLines.map((text, offset): DiffDisplayLine => ({
      id: `${pair.id}:deleted:${offset}`, text, lineNumber: null,
      sourceLineNumber: pair.source!.start + offset, status: 'deleted',
    })))
    deleted.set(before, block)
  }
  const resultLines: DiffDisplayLine[] = []
  const lineMap: TextLineMap = { sourceLineCount: source.lines.length, resultLineCount: result.lines.length, pairs }
  const translate = compileLineMap(lineMap)
  for (let line = 1; line <= result.lines.length + 1; line++) {
    const block = deleted.get(line)
    if (block) resultLines.push(...block)
    if (line > result.lines.length) continue
    const corresponding = owners[line]
    resultLines.push({
      id: corresponding ? `${corresponding.id}:result:${line - corresponding.result!.start}` : `result:${line}`,
      text: result.lines[line - 1]!, lineNumber: line,
      sourceLineNumber: corresponding?.source ? translate('result', { line, fraction: 0 })?.line ?? null : null,
      status: statuses.get(line) ?? 'same',
    })
  }
  return {
    sourceText, resultText, lineMap, ...createVirtualLineComparison(sourceText, resultLines),
  }
}

export interface PreviewLineSnapshot {
  input: { previewBody: Record<string, unknown>; sourceText?: string }
  result: { output: unknown }
}

/** 旧结果始终来自成功快照；仅当当前草稿可靠地对应这份快照时提供联动映射。 */
export function resolvePreviewLines(draft: string, snapshot: PreviewLineSnapshot | null, stale = false) {
  if (!snapshot) return { comparison: null, lineMap: null, notice: '正在等待预览结果' }
  let current: unknown
  try { current = JSON.parse(draft) }
  catch { return { comparison: comparisonFromSnapshot(snapshot), lineMap: null, notice: 'JSON 语法错误，已暂停同步滚动' } }
  const matching = jsonEquals(current, snapshot.input.previewBody)
  if (stale || !matching) {
    return { comparison: comparisonFromSnapshot(snapshot), lineMap: null, notice: '结果已过期，已暂停同步滚动' }
  }
  try {
    const comparison = createJsonLineComparison(draft, snapshot.result.output)
    return { comparison, lineMap: comparison.lineMap, notice: '' }
  } catch (cause) {
    return { comparison: comparisonFromSnapshot(snapshot), lineMap: null, notice: cause instanceof Error ? cause.message : '无法对齐文本行' }
  }
}

function comparisonFromSnapshot(snapshot: PreviewLineSnapshot): JsonLineComparison | null {
  const canonical = JSON.stringify(snapshot.input.previewBody, null, 2)
  try {
    return createJsonLineComparison(snapshot.input.sourceText ?? canonical, snapshot.result.output)
  } catch {
    // 源快照也可能包含无法定位的重复字段；规范副本只用于保留结果展示，
    // resolvePreviewLines 仍返回 null 映射，绝不声称它对应真实编辑行。
    try { return createJsonLineComparison(canonical, snapshot.result.output) }
    catch { return null }
  }
}
