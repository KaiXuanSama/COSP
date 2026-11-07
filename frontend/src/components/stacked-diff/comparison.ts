import type { DiffDisplayLine, TextLineMap } from './lines'

export interface VirtualLineComparison {
  sourceLines: readonly DiffDisplayLine[]
  resultLines: readonly DiffDisplayLine[]
  /** 这里的行号属于共享对比序列，不是真实文本行号。 */
  virtualLineMap: TextLineMap
}

/**
 * 一条真实源行只展示一次。紧凑 JSON 的一对多、格式空行与字段调序均用展示占位补齐，
 * 不重复源文本，也不把交叉的语义对应伪装成可滚动的物理顺序。
 */
export function createVirtualLineComparison(sourceText: string, outputLines: readonly DiffDisplayLine[]): VirtualLineComparison {
  const source = sourceText.split(/\r\n|\r|\n/)
  const candidates = outputLines.flatMap((line, index) => {
    const number = line.sourceLineNumber
    return number != null && Number.isInteger(number) && number >= 1 && number <= source.length
      ? [{ index, number }] : []
  })
  // 最长递增子序列：取不交叉的真实源行锚点。相同行号保留最先出现的一项。
  const tails: number[] = []
  const previous = new Array<number>(candidates.length).fill(-1)
  candidates.forEach((candidate, index) => {
    let low = 0
    let high = tails.length
    while (low < high) {
      const middle = (low + high) >>> 1
      if (candidates[tails[middle]!]!.number < candidate.number) low = middle + 1
      else high = middle
    }
    if (low < tails.length && candidates[tails[low]!]!.number === candidate.number) return
    previous[index] = low ? tails[low - 1]! : -1
    tails[low] = index
  })
  const anchors = new Map<number, number>()
  for (let index = tails.at(-1) ?? -1; index >= 0; index = previous[index]!) {
    const candidate = candidates[index]!
    anchors.set(candidate.index, candidate.number)
  }

  const sourceLines: DiffDisplayLine[] = []
  const resultLines: DiffDisplayLine[] = []
  let nextSource = 1
  function append(left: DiffDisplayLine, right: DiffDisplayLine) {
    const virtualLineNumber = sourceLines.length + 1
    sourceLines.push({ ...left, virtualLineNumber })
    resultLines.push({ ...right, virtualLineNumber })
  }
  function appendSourceGap(number: number) {
    const id = `source-gap:${number}`
    append(
      { id, text: source[number - 1]!, lineNumber: number, status: 'same' },
      { id, text: '', lineNumber: null, status: 'same', placeholder: true },
    )
  }
  outputLines.forEach((right, index) => {
    const sourceNumber = anchors.get(index)
    if (sourceNumber !== undefined) {
      while (nextSource < sourceNumber) appendSourceGap(nextSource++)
      append({
        id: right.id, text: source[sourceNumber - 1]!, lineNumber: sourceNumber, status: right.status,
      }, right)
      nextSource = sourceNumber + 1
    } else {
      append({ id: right.id, text: '', lineNumber: null, status: right.status, placeholder: true }, right)
    }
  })
  while (nextSource <= source.length) appendSourceGap(nextSource++)

  return {
    sourceLines, resultLines,
    virtualLineMap: {
      sourceLineCount: sourceLines.length, resultLineCount: resultLines.length,
      pairs: sourceLines.length ? [{
        id: 'shared-comparison-rows', status: 'same',
        source: { start: 1, end: sourceLines.length }, result: { start: 1, end: resultLines.length },
      }] : [],
    },
  }
}