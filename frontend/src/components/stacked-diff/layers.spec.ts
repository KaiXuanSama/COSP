import { describe, expect, it, vi } from 'vitest'
import { createStackedLayers, lockedLayer, reduceStackedLayers } from './layers'
import { useStackedLayers } from './useStackedLayers'

describe('叠放层级与焦点', () => {
  it('默认源侧在前，两份状态互不共享', () => {
    const first = createStackedLayers()
    const second = createStackedLayers('result')
    expect(first).toEqual({ activeSide: 'source', focusedSide: null, composingSide: null })
    expect(second.activeSide).toBe('result')
    expect(first).not.toBe(second)
  })

  it('未锁定时 hover 换层，重复进入同侧不改状态', () => {
    const original = createStackedLayers()
    const next = reduceStackedLayers(original, { type: 'activate', side: 'result', reason: 'hover' })
    expect(next.activeSide).toBe('result')
    expect(original.activeSide).toBe('source')
    expect(reduceStackedLayers(next, { type: 'activate', side: 'result', reason: 'hover' })).toBe(next)
  })

  it('编辑获得焦点后挡住另一侧 hover', () => {
    const state = reduceStackedLayers(createStackedLayers(), { type: 'focus', side: 'source' })
    expect(lockedLayer(state)).toBe('source')
    expect(reduceStackedLayers(state, { type: 'activate', side: 'result', reason: 'hover' })).toBe(state)
  })

  it('失焦不重放被忽略的 hover，下次新进入才切换', () => {
    let state = reduceStackedLayers(createStackedLayers(), { type: 'focus', side: 'source' })
    state = reduceStackedLayers(state, { type: 'activate', side: 'result', reason: 'hover' })
    state = reduceStackedLayers(state, { type: 'blur', side: 'source' })
    expect(state.activeSide).toBe('source')
    expect(lockedLayer(state)).toBeNull()
    expect(reduceStackedLayers(state, { type: 'activate', side: 'result', reason: 'hover' }).activeSide).toBe('result')
  })

  it('键盘进入背景侧时激活该侧并转移焦点锁', () => {
    const state = reduceStackedLayers(
      reduceStackedLayers(createStackedLayers(), { type: 'focus', side: 'source' }),
      { type: 'focus', side: 'result' },
    )
    expect(state.activeSide).toBe('result')
    expect(lockedLayer(state)).toBe('result')
  })

  it('晚到的上一侧 blur 不解除新侧焦点锁', () => {
    let state = reduceStackedLayers(createStackedLayers(), { type: 'focus', side: 'result' })
    state = reduceStackedLayers(state, { type: 'blur', side: 'source' })
    expect(lockedLayer(state)).toBe('result')
  })

  it('组合输入阻止 hover，组合结束后仍保留当前焦点锁', () => {
    let state = reduceStackedLayers(createStackedLayers(), { type: 'focus', side: 'source' })
    state = reduceStackedLayers(state, { type: 'compositionstart', side: 'source' })
    expect(reduceStackedLayers(state, { type: 'activate', side: 'result', reason: 'hover' })).toBe(state)
    state = reduceStackedLayers(state, { type: 'compositionend', side: 'source' })
    expect(state.composingSide).toBeNull()
    expect(lockedLayer(state)).toBe('source')
  })

  it('组合输入即使尚无 focus 事件也能锁定，真实失焦清理该侧组合状态', () => {
    let state = reduceStackedLayers(createStackedLayers(), { type: 'compositionstart', side: 'source' })
    expect(lockedLayer(state)).toBe('source')
    state = reduceStackedLayers(state, { type: 'blur', side: 'source' })
    expect(lockedLayer(state)).toBeNull()
  })

  it('主动切换不受 hover 锁限制，随后真实焦点接管锁', () => {
    let state = reduceStackedLayers(createStackedLayers(), { type: 'focus', side: 'source' })
    state = reduceStackedLayers(state, { type: 'activate', side: 'result', reason: 'explicit' })
    expect(state.activeSide).toBe('result')
    state = reduceStackedLayers(state, { type: 'focus', side: 'result' })
    expect(lockedLayer(state)).toBe('result')
  })

  it('焦点转移清理旧组合输入，晚到的 compositionend 不影响新侧', () => {
    let state = reduceStackedLayers(createStackedLayers(), { type: 'compositionstart', side: 'source' })
    state = reduceStackedLayers(state, { type: 'focus', side: 'result' })
    state = reduceStackedLayers(state, { type: 'compositionstart', side: 'result' })
    state = reduceStackedLayers(state, { type: 'compositionend', side: 'source' })
    expect(state.composingSide).toBe('result')
    expect(lockedLayer(state)).toBe('result')
  })

  it('只读结果侧获得焦点同样保护选区', () => {
    const state = reduceStackedLayers(createStackedLayers(), { type: 'focus', side: 'result' })
    expect(reduceStackedLayers(state, { type: 'activate', side: 'source', reason: 'hover' })).toBe(state)
  })
})

describe('useStackedLayers', () => {
  it('只在前景实际变化时报告原因', () => {
    const onChange = vi.fn()
    const layers = useStackedLayers({ onChange })
    layers.hover('result')
    layers.hover('result')
    layers.focus('result')
    layers.hover('source')
    expect(onChange.mock.calls).toEqual([[{ side: 'result', previousSide: 'source', reason: 'hover' }]])
    layers.activate('source')
    layers.focus('source')
    expect(onChange).toHaveBeenLastCalledWith({ side: 'source', previousSide: 'result', reason: 'explicit' })
  })

  it('拒绝 hover 后的 blur 只解除锁，不发送切换事件', () => {
    const onChange = vi.fn()
    const layers = useStackedLayers({ onChange })
    layers.focus('source')
    layers.hover('result')
    layers.blur('source')
    expect(layers.activeSide.value).toBe('source')
    expect(layers.lockedSide.value).toBeNull()
    expect(onChange).not.toHaveBeenCalled()
  })

  it('不同实例独立，组合输入开始按焦点激活回报', () => {
    const onChange = vi.fn()
    const first = useStackedLayers({ onChange })
    const second = useStackedLayers()
    first.compositionStart('result')
    expect(first.activeSide.value).toBe('result')
    expect(first.lockedSide.value).toBe('result')
    expect(second.activeSide.value).toBe('source')
    expect(second.lockedSide.value).toBeNull()
    expect(onChange).toHaveBeenCalledWith({ side: 'result', previousSide: 'source', reason: 'focus' })
  })
})
