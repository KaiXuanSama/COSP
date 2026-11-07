/** 两个内容面的固定位置；前后景切换不改变它们的左右位置。 */
export type StackedSide = 'source' | 'result'

export type LayerActivationReason = 'hover' | 'focus' | 'explicit'

export interface LayerChange {
  side: StackedSide
  previousSide: StackedSide
  reason: LayerActivationReason
}

export interface StackedLayersState {
  readonly activeSide: StackedSide
  readonly focusedSide: StackedSide | null
  readonly composingSide: StackedSide | null
}

export type StackedLayersEvent =
  | { type: 'activate'; side: StackedSide; reason: 'hover' | 'explicit' }
  | { type: 'focus' | 'blur' | 'compositionstart' | 'compositionend'; side: StackedSide }

export function createStackedLayers(initialSide: StackedSide = 'source'): StackedLayersState {
  return { activeSide: initialSide, focusedSide: null, composingSide: null }
}

export function lockedLayer(
  state: StackedLayersState,
  editableSide: StackedSide | null = 'source',
): StackedSide | null {
  if (editableSide === null) return null
  return state.focusedSide === editableSide || state.composingSide === editableSide ? editableSide : null
}

/**
 * 不缓存被焦点挡住的 hover，也不在失焦时重放它。
 * blur / compositionend 都按所属侧清理，晚到的旧事件不能解除另一侧的新锁。
 */
export function reduceStackedLayers(
  state: StackedLayersState,
  event: StackedLayersEvent,
  editableSide: StackedSide | null = 'source',
): StackedLayersState {
  switch (event.type) {
    case 'activate':
      if (event.reason === 'hover' && lockedLayer(state, editableSide) !== null) return state
      if (event.side === state.activeSide) return state
      return { ...state, activeSide: event.side }
    case 'focus':
      return {
        activeSide: event.side,
        focusedSide: event.side,
        composingSide: state.composingSide === event.side ? event.side : null,
      }
    case 'blur':
      return {
        ...state,
        focusedSide: state.focusedSide === event.side ? null : state.focusedSide,
        composingSide: state.composingSide === event.side ? null : state.composingSide,
      }
    case 'compositionstart':
      return { ...state, activeSide: event.side, composingSide: event.side }
    case 'compositionend':
      return {
        ...state,
        composingSide: state.composingSide === event.side ? null : state.composingSide,
      }
  }
}
