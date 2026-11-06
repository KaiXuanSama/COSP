import type { DiffDisplayLine, TextLineMap, TextLinePair } from '../lines'

/** 手写的双文本样本与行对应；不模拟业务规则，也不加载 COSP 请求体模块。 */
export function createSample() {
  const source: string[] = []
  const result: string[] = []
  const display: DiffDisplayLine[] = []
  const pairs: TextLinePair[] = []

  function line(id: string, before: string | null, after: string | null) {
    const status = before === null ? 'added' : after === null ? 'deleted' : before === after ? 'same' : 'changed'
    const sourceNumber = before === null ? null : source.push(before)
    const resultNumber = after === null ? null : result.push(after)
    pairs.push({
      id, status,
      source: sourceNumber === null ? null : { start: sourceNumber, end: sourceNumber },
      result: resultNumber === null ? null : { start: resultNumber, end: resultNumber },
    })
    display.push({ id, text: after ?? before ?? '', status, lineNumber: resultNumber, sourceLineNumber: sourceNumber })
  }

  line('open', '{', '{')
  line('title', '  "title": "一个安静的阅读空间",', '  "title": "一个清晰的阅读空间",')
  line('legacy', '  "legacyMode": true,', null)
  line('layout', '  "layout": "stacked",', '  "layout": "stacked",')
  line('theme', '  "theme": {', '  "theme": {')
  line('spacing', '    "spacing": 16', '    "spacing": 20')
  line('theme-close', '  },', '  },')
  line('sections', '  "sections": [', '  "sections": [')
  for (let index = 1; index <= 12; index++) {
    const prefix = `section-${index}`
    line(`${prefix}-open`, '    {', '    {')
    line(`${prefix}-title`, `      "title": "阅读片段 ${index}",`, `      "title": "阅读片段 ${index}",`)
    const description = `      "description": "第 ${index} 段内容用于观察软换行、逻辑行和滚动联动，切换到另一侧后仍能找到相同段落。"`
    line(`${prefix}-description`, description, description)
    if (index === 3) {
      line('deleted-note', '      ,"obsolete": "这行仅存在于原始样本"', null)
    }
    if (index === 8) {
      line('added-note', null, '      ,"note": "这行仅存在于结果样本"')
    }
    const close = index === 12 ? '    }' : '    },'
    line(`${prefix}-close`, close, close)
  }
  line('sections-close', '  ],', '  ],')
  line('enabled', '  "enabled": true', '  "enabled": true')
  line('close', '}', '}')
  const lineMap: TextLineMap = { sourceLineCount: source.length, resultLineCount: result.length, pairs }
  return { sourceText: source.join('\n'), resultText: result.join('\n'), resultLines: display, lineMap }
}
