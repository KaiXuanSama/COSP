import { onScopeDispose, toValue, watch, type MaybeRefOrGetter } from 'vue'
import type { StackedSide } from './layers'
import { compileLineMap, type LineScrollPort, type TextLineMap } from './lines'

export interface LinkedScrollOptions {
  source: MaybeRefOrGetter<LineScrollPort | null | undefined>
  result: MaybeRefOrGetter<LineScrollPort | null | undefined>
  lineMap: MaybeRefOrGetter<TextLineMap | null | undefined>
  activeSide: MaybeRefOrGetter<StackedSide>
  enabled: MaybeRefOrGetter<boolean>
}

/** 只由当前前景发起同步；目标位置的程序化 scroll 事件不反向驱动。 */
export function useLinkedScroll(options: LinkedScrollOptions) {
  let cleanup = () => {}
  watch(
    () => [toValue(options.source), toValue(options.result), toValue(options.lineMap), toValue(options.enabled)] as const,
    ([source, result, map, enabled]) => {
      cleanup()
      if (!source || !result || !map || !enabled) return
      const translate = compileLineMap(map)
      const ports = { source, result }
      const expected: Partial<Record<StackedSide, number>> = {}
      let queued = false
      let disposed = false
      let pendingSide: StackedSide | null = null

      function sync(side: StackedSide) {
        if (disposed || !toValue(options.enabled) || toValue(options.lineMap) !== map
          || toValue(options.source) !== source || toValue(options.result) !== result) return
        const targetSide = side === 'source' ? 'result' : 'source'
        const anchor = translate(side, ports[side].readAnchor())
        if (!anchor) return
        expected[targetSide] = ports[targetSide].writeAnchor(anchor)
      }

      function scrolled(side: StackedSide) {
        const pending = expected[side]
        if (pending !== undefined) {
          delete expected[side]
          if (Math.abs(ports[side].scrollTop() - pending) < 1) return
        }
        if (toValue(options.activeSide) !== side) return
        pendingSide = side
        if (queued) return
        queued = true
        queueMicrotask(() => {
          queued = false
          if (disposed) return
          if (pendingSide && pendingSide === toValue(options.activeSide)) sync(pendingSide)
          pendingSide = null
        })
      }

      const removeSource = source.onScroll(() => scrolled('source'))
      const removeResult = result.onScroll(() => scrolled('result'))
      source.refresh()
      result.refresh()
      sync(toValue(options.activeSide))
      cleanup = () => {
        removeSource()
        removeResult()
        disposed = true
      }
    },
    { immediate: true, flush: 'post' },
  )
  onScopeDispose(() => cleanup())
}
