import { afterEach, describe, expect, it, vi } from 'vitest'
import { effectScope, nextTick, ref, shallowRef } from 'vue'
import { useLinkedScroll } from './useLinkedScroll'
import type { LineScrollPort, TextLineAnchor, TextLineMap } from './lines'

function fakePort() {
  const listeners = new Set<() => void>()
  let position = 0
  let anchor: TextLineAnchor = { line: 1, fraction: 0 }
  const port: LineScrollPort = {
    readAnchor: () => anchor,
    writeAnchor: vi.fn((value: TextLineAnchor) => { anchor = value; position = value.line * 20; return position }),
    scrollTop: () => position,
    onScroll: listener => { listeners.add(listener); return () => { listeners.delete(listener) } },
    refresh: vi.fn(), dispose: vi.fn(),
  }
  return {
    port,
    count: () => listeners.size,
    scroll(value: TextLineAnchor) { anchor = value; position = value.line * 20 + value.fraction; listeners.forEach(fn => fn()) },
    notify() { listeners.forEach(fn => fn()) },
  }
}

describe('封装组件滚动协调', () => {
  afterEach(() => { vi.useRealTimers(); vi.unstubAllGlobals() })

  it('当前前景发起同步，目标程序滚动即使成为新前景也不反馈', async () => {
    const source = fakePort()
    const result = fakePort()
    const activeSide = ref<'source' | 'result'>('source')
    const map = shallowRef<TextLineMap | null>({ sourceLineCount: 20, resultLineCount: 18, pairs: [
      { id: 'body', source: { start: 3, end: 20 }, result: { start: 1, end: 18 }, status: 'same' },
    ] })
    const scope = effectScope()
    scope.run(() => useLinkedScroll({ source: source.port, result: result.port, lineMap: map, activeSide, enabled: true }))
    await nextTick()
    source.scroll({ line: 8, fraction: 0.5 })
    await nextTick()
    expect(result.port.writeAnchor).toHaveBeenLastCalledWith({ line: 6, fraction: 0.5 })
    activeSide.value = 'result'
    result.notify()
    await nextTick()
    expect(source.port.writeAnchor).not.toHaveBeenCalled()
    result.scroll({ line: 9, fraction: 0.25 })
    await nextTick()
    expect(source.port.writeAnchor).toHaveBeenLastCalledWith({ line: 11, fraction: 0.25 })
    scope.stop()
    expect(source.count()).toBe(0)
    expect(result.count()).toBe(0)
  })

  it('映射变为 null 后解除端口监听，不继续同步旧结果', async () => {
    const source = fakePort()
    const result = fakePort()
    const map = shallowRef<TextLineMap | null>({ sourceLineCount: 2, resultLineCount: 2, pairs: [
      { id: 'all', source: { start: 1, end: 2 }, result: { start: 1, end: 2 }, status: 'same' },
    ] })
    const scope = effectScope()
    scope.run(() => useLinkedScroll({ source: source.port, result: result.port, lineMap: map, activeSide: 'source', enabled: true }))
    await nextTick()
    vi.mocked(result.port.writeAnchor).mockClear()
    map.value = null
    await nextTick()
    source.scroll({ line: 2, fraction: 0 })
    await nextTick()
    expect(result.port.writeAnchor).not.toHaveBeenCalled()
    expect(source.count()).toBe(0)
    scope.stop()
  })

  it('同一事件轮内多个滚动合并到最后锚点，不依赖动画帧', async () => {
    const source = fakePort()
    const result = fakePort()
    const scope = effectScope()
    vi.stubGlobal('requestAnimationFrame', () => { throw new Error('后台标签不可依赖动画帧') })
    scope.run(() => useLinkedScroll({
      source: source.port, result: result.port, activeSide: 'source', enabled: true,
      lineMap: { sourceLineCount: 20, resultLineCount: 20, pairs: [
        { id: 'all', source: { start: 1, end: 20 }, result: { start: 1, end: 20 }, status: 'same' },
      ] },
    }))
    vi.mocked(result.port.writeAnchor).mockClear()
    source.scroll({ line: 4, fraction: 0 })
    source.scroll({ line: 8, fraction: 0.5 })
    await nextTick()
    expect(result.port.writeAnchor).toHaveBeenCalledTimes(1)
    expect(result.port.writeAnchor).toHaveBeenLastCalledWith({ line: 8, fraction: 0.5 })
    scope.stop()
  })

  it('滚动已排入微任务后映射立即失效也不会应用旧锚点', async () => {
    const source = fakePort()
    const result = fakePort()
    const map = shallowRef<TextLineMap | null>({ sourceLineCount: 20, resultLineCount: 20, pairs: [
      { id: 'all', source: { start: 1, end: 20 }, result: { start: 1, end: 20 }, status: 'same' },
    ] })
    const scope = effectScope()
    scope.run(() => useLinkedScroll({ source: source.port, result: result.port, lineMap: map, activeSide: 'source', enabled: true }))
    vi.mocked(result.port.writeAnchor).mockClear()
    source.scroll({ line: 10, fraction: 0 })
    map.value = null
    await nextTick()
    expect(result.port.writeAnchor).not.toHaveBeenCalled()
    expect(source.count()).toBe(0)
    scope.stop()
  })

  it('缺少端口或关闭同步时不绑定，重新开启后恢复', async () => {
    const source = fakePort()
    const result = fakePort()
    const target = shallowRef<LineScrollPort | null>(null)
    const enabled = ref(true)
    const scope = effectScope()
    scope.run(() => useLinkedScroll({
      source: source.port, result: target, activeSide: 'source', enabled,
      lineMap: { sourceLineCount: 2, resultLineCount: 2, pairs: [
        { id: 'all', source: { start: 1, end: 2 }, result: { start: 1, end: 2 }, status: 'same' },
      ] },
    }))
    expect(source.count()).toBe(0)
    target.value = result.port
    await nextTick()
    expect(source.count()).toBe(1)
    enabled.value = false
    await nextTick()
    expect(source.count()).toBe(0)
    enabled.value = true
    await nextTick()
    expect(source.count()).toBe(1)
    scope.stop()
  })

  it('端口替换清除旧监听，停止作用域不销毁宿主拥有的端口', async () => {
    const source = fakePort()
    const first = fakePort()
    const second = fakePort()
    const target = shallowRef(first.port)
    const scope = effectScope()
    scope.run(() => useLinkedScroll({
      source: source.port, result: target, activeSide: 'source', enabled: true,
      lineMap: { sourceLineCount: 2, resultLineCount: 2, pairs: [
        { id: 'all', source: { start: 1, end: 2 }, result: { start: 1, end: 2 }, status: 'same' },
      ] },
    }))
    target.value = second.port
    await nextTick()
    expect(first.count()).toBe(0)
    expect(second.count()).toBe(1)
    scope.stop()
    expect(second.count()).toBe(0)
    expect(source.port.dispose).not.toHaveBeenCalled()
    expect(second.port.dispose).not.toHaveBeenCalled()
  })

  it('合并期间前景已切换时不再应用旧前景的待处理滚动', async () => {
    const source = fakePort()
    const result = fakePort()
    const activeSide = ref<'source' | 'result'>('source')
    const scope = effectScope()
    scope.run(() => useLinkedScroll({
      source: source.port, result: result.port, activeSide, enabled: true,
      lineMap: { sourceLineCount: 20, resultLineCount: 20, pairs: [
        { id: 'all', source: { start: 1, end: 20 }, result: { start: 1, end: 20 }, status: 'same' },
      ] },
    }))
    vi.mocked(result.port.writeAnchor).mockClear()
    source.scroll({ line: 10, fraction: 0 })
    activeSide.value = 'result'
    await nextTick()
    expect(result.port.writeAnchor).not.toHaveBeenCalled()
    scope.stop()
  })
})
