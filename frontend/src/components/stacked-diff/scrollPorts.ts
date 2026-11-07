import { anchorAtScroll, scrollAtAnchor, type LineScrollPort, type MeasuredTextLine } from './lines'

export interface TextareaScrollPort extends LineScrollPort {
  /** 与同步滚动共用缓存，行号无需再创建一份镜像。 */
  measureLines(): readonly MeasuredTextLine[]
}

function createPort(element: HTMLElement, measure: () => MeasuredTextLine[], destroy: () => void): TextareaScrollPort {
  let lines: MeasuredTextLine[] = []
  let dirty = true
  let disposed = false
  const invalidate = () => { dirty = true }
  const listeners = new Set<() => void>()
  const notify = () => listeners.forEach((listener) => listener())
  const layoutChanged = () => {
    invalidate()
    if (!disposed) notify()
  }
  const observer = typeof ResizeObserver === 'undefined' ? null : new ResizeObserver(layoutChanged)
  observer?.observe(element)
  element.addEventListener('input', invalidate)
  const fonts = element.ownerDocument.fonts
  fonts?.addEventListener('loadingdone', layoutChanged)
  element.addEventListener('scroll', notify, { passive: true })

  function measured() {
    if (dirty && !disposed) {
      lines = measure()
      dirty = false
    }
    return lines
  }

  return {
    measureLines: measured,
    readAnchor: () => anchorAtScroll(measured(), element.scrollTop),
    writeAnchor(anchor) {
      const target = Math.max(0, Math.min(element.scrollHeight - element.clientHeight, scrollAtAnchor(measured(), anchor)))
      if (Math.abs(target - element.scrollTop) >= 0.5) element.scrollTop = target
      return element.scrollTop
    },
    scrollTop: () => element.scrollTop,
    onScroll(listener) {
      listeners.add(listener)
      return () => { listeners.delete(listener) }
    },
    refresh: invalidate,
    dispose() {
      if (disposed) return
      disposed = true
      observer?.disconnect()
      element.removeEventListener('input', invalidate)
      element.removeEventListener('scroll', notify)
      fonts?.removeEventListener('loadingdone', layoutChanged)
      listeners.clear()
      destroy()
    },
  }
}

/** 保留真实 textarea，不向 value 插入对齐占位。镜像只用于测量软换行后的逻辑行高度。 */
export function createTextareaScrollPort(textarea: HTMLTextAreaElement): TextareaScrollPort {
  const doc = textarea.ownerDocument
  const mirror = doc.createElement('div')
  mirror.setAttribute('aria-hidden', 'true')
  Object.assign(mirror.style, {
    position: 'fixed', left: '-100000px', top: '0', visibility: 'hidden', pointerEvents: 'none',
    boxSizing: 'border-box', margin: '0', border: '0', height: 'auto', contain: 'layout style paint',
  })
  doc.body.append(mirror)
  return createPort(textarea, () => {
    const style = getComputedStyle(textarea)
    const properties = [
      'font', 'fontFamily', 'fontSize', 'fontWeight', 'fontStyle', 'fontVariant', 'lineHeight',
      'letterSpacing', 'wordSpacing', 'textIndent', 'textTransform', 'tabSize', 'paddingTop',
      'paddingRight', 'paddingBottom', 'paddingLeft', 'wordBreak', 'overflowWrap', 'direction',
    ] as const
    for (const property of properties) mirror.style[property] = style[property]
    mirror.style.width = `${textarea.clientWidth}px`
    mirror.style.whiteSpace = textarea.wrap === 'off' ? 'pre' : 'pre-wrap'
    const lineHeight = Number.parseFloat(style.lineHeight) || Number.parseFloat(style.fontSize) * 1.2
    const fragment = doc.createDocumentFragment()
    for (const text of textarea.value.split('\n')) {
      const line = doc.createElement('div')
      line.style.minHeight = `${lineHeight}px`
      line.textContent = text.replace(/\r$/, '') || '\u200b'
      fragment.append(line)
    }
    mirror.replaceChildren(fragment)
    const origin = mirror.getBoundingClientRect().top
    return [...mirror.children].map((child, index) => {
      const rect = child.getBoundingClientRect()
      return { line: index + 1, top: rect.top - origin, height: rect.height }
    })
  }, () => mirror.remove())
}

/** 存在共享对比行号时按其定位，包含删除痕迹与占位；旧调用仍按真实行号。 */
export function createDisplayScrollPort(element: HTMLElement): LineScrollPort {
  return createPort(element, () => {
    const rootRect = element.getBoundingClientRect()
    const scale = rootRect.height / element.offsetHeight || 1
    const virtual = element.querySelector('[data-virtual-line]') !== null
    return [...element.querySelectorAll<HTMLElement>(virtual ? '[data-virtual-line]' : '[data-line-number]')].map((line) => {
      const rect = line.getBoundingClientRect()
      return {
        line: Number(virtual ? line.dataset.virtualLine : line.dataset.lineNumber),
        top: (rect.top - rootRect.top) / scale + element.scrollTop - element.clientTop,
        height: rect.height / scale,
      }
    })
  }, () => {})
}
