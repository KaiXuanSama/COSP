import type { StackedSide } from './layers'

export type TextDiffStatus = 'same' | 'changed' | 'added' | 'deleted'

/** 真实文本行范围，1 起始，包含两端。 */
export interface TextLineRange {
  start: number
  end: number
}

export interface TextLinePair {
  id: string
  source: TextLineRange | null
  result: TextLineRange | null
  status: TextDiffStatus
}

export interface TextLineMap {
  sourceLineCount: number
  resultLineCount: number
  pairs: readonly TextLinePair[]
}

/** fraction 是当前逻辑行内的相对位置，包含软换行后的整段高度。 */
export interface TextLineAnchor {
  line: number
  fraction: number
}

export interface DiffDisplayLine {
  id: string
  text: string
  lineNumber: number | null
  sourceLineNumber?: number | null
  status: TextDiffStatus
}

/** 内容由宿主提供，组合组件只经端口协调滚动，不认识具体编辑器。 */
export interface LineScrollPort {
  readAnchor(): TextLineAnchor
  writeAnchor(anchor: TextLineAnchor): number
  scrollTop(): number
  onScroll(listener: () => void): () => void
  refresh(): void
  dispose(): void
}

function unit(value: number): number {
  return Number.isFinite(value) ? Math.max(0, Math.min(0.999999, value)) : 0
}

/** 预编译双向对应索引，滚动时不重算 Diff；未匹配行取最近的有对应行的锚点。 */
export function compileLineMap(map: TextLineMap) {
  const valid = map.pairs.filter((pair) => pair.source !== null && pair.result !== null)
  const parents = valid.map((_, index) => index)

  function root(index: number): number {
    while (parents[index] !== index) {
      parents[index] = parents[parents[index]!]!
      index = parents[index]!
    }
    return index
  }

  function collectGroups() {
    const groups = new Map<number, { source: TextLineRange; result: TextLineRange }>()
    valid.forEach((pair, index) => {
      const id = root(index)
      const group = groups.get(id)
      if (!group) {
        groups.set(id, { source: { ...pair.source! }, result: { ...pair.result! } })
        return
      }
      for (const side of ['source', 'result'] as const) {
        group[side].start = Math.min(group[side].start, pair[side]!.start)
        group[side].end = Math.max(group[side].end, pair[side]!.end)
      }
    })
    return groups
  }

  // 同一真实行上的多个字段只能按整体行范围定位，不能总是命中首字段。
  // 合并后的包络区间也可能覆盖其他组，因此收敛到两侧均不重叠，再建立二分索引。
  let groups = collectGroups()
  while (true) {
    let merged = false
    for (const side of ['source', 'result'] as const) {
      const ordered = [...groups.entries()].sort((a, b) => a[1][side].start - b[1][side].start)
      let previous = -1
      let end = -1
      for (const [id, group] of ordered) {
        const range = group[side]
        if (range.start <= end) {
          if (root(id) !== root(previous)) {
            parents[root(id)] = root(previous)
            merged = true
          }
          end = Math.max(end, range.end)
        } else {
          previous = id
          end = range.end
        }
      }
    }
    if (!merged) break
    groups = collectGroups()
  }
  const indexes = {
    source: [...groups.values()].sort((a, b) => a.source.start - b.source.start),
    result: [...groups.values()].sort((a, b) => a.result.start - b.result.start),
  }

  return (side: StackedSide, anchor: TextLineAnchor): TextLineAnchor | null => {
    const count = side === 'source' ? map.sourceLineCount : map.resultLineCount
    const line = Math.max(1, Math.min(count, Number.isFinite(anchor.line) ? anchor.line : 1))
    const index = indexes[side]
    if (!index.length) return null
    let low = 0
    let high = index.length
    while (low < high) {
      const middle = Math.floor((low + high) / 2)
      if (index[middle]![side].end < line) low = middle + 1
      else high = middle
    }
    const next = index[low]
    const previous = index[low - 1]
    const best = !next ? previous! : previous && line < next[side].start
      && line - previous[side].end <= next[side].start - line ? previous : next
    const from = best[side]
    const to = best[side === 'source' ? 'result' : 'source']
    // 不存在对应行时固定到邻近边界，不按整份文本的总高度比例猜测。
    if (line < from.start) return { line: to.start, fraction: 0 }
    if (line > from.end) return { line: to.end, fraction: 0.999999 }
    const progress = (line - from.start + unit(anchor.fraction)) / (from.end - from.start + 1)
    const offset = progress * (to.end - to.start + 1)
    return { line: to.start + Math.floor(offset), fraction: unit(offset % 1) }
  }
}

export interface MeasuredTextLine {
  line: number
  top: number
  height: number
}

/** 逻辑行测量以布局坐标记录，不包含浮沉 transform。 */
export function anchorAtScroll(lines: readonly MeasuredTextLine[], scrollTop: number): TextLineAnchor {
  if (!lines.length) return { line: 1, fraction: 0 }
  let low = 0
  let high = lines.length - 1
  while (low < high) {
    const mid = Math.ceil((low + high) / 2)
    if (lines[mid]!.top <= scrollTop) low = mid
    else high = mid - 1
  }
  const current = lines[low]!
  return { line: current.line, fraction: unit((scrollTop - current.top) / Math.max(1, current.height)) }
}

export function scrollAtAnchor(lines: readonly MeasuredTextLine[], anchor: TextLineAnchor): number {
  if (!lines.length) return 0
  // 首行开始同时代表 scrollTop=0 的留白区，避免绑定端口时凭空向下跳一个 padding。
  if (anchor.line <= lines[0]!.line && unit(anchor.fraction) === 0) return 0
  let low = 0
  let high = lines.length - 1
  while (low < high) {
    const mid = Math.floor((low + high) / 2)
    if (lines[mid]!.line < anchor.line) low = mid + 1
    else high = mid
  }
  const current = lines[low]!
  return current.top + unit(anchor.fraction) * current.height
}
