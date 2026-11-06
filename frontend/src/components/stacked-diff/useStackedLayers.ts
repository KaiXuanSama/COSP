import { computed, shallowRef, toValue, type MaybeRefOrGetter } from 'vue'
import {
  createStackedLayers,
  lockedLayer,
  reduceStackedLayers,
  type LayerChange,
  type StackedLayersEvent,
  type StackedSide,
} from './layers'

export interface UseStackedLayersOptions {
  initialSide?: StackedSide
  editableSide?: MaybeRefOrGetter<StackedSide | null>
  onChange?: (change: LayerChange) => void
}

/** 一组容器的一份状态；不访问 DOM、不管理内容或文本值。 */
export function useStackedLayers(options: UseStackedLayersOptions = {}) {
  const state = shallowRef(createStackedLayers(options.initialSide))
  const editableSide = () => options.editableSide === undefined ? 'source' : toValue(options.editableSide)
  const activeSide = computed(() => state.value.activeSide)
  const lockedSide = computed(() => lockedLayer(state.value, editableSide()))

  function dispatch(event: StackedLayersEvent) {
    const previousSide = state.value.activeSide
    state.value = reduceStackedLayers(state.value, event, editableSide())
    if (state.value.activeSide === previousSide) return
    const reason = event.type === 'activate'
      ? event.reason
      : event.type === 'compositionstart' ? 'focus' : event.type
    // 只有 activate、focus、compositionstart 能改变前景。
    if (reason === 'hover' || reason === 'explicit' || reason === 'focus') {
      options.onChange?.({ side: state.value.activeSide, previousSide, reason })
    }
  }

  return {
    activeSide,
    lockedSide,
    hover: (side: StackedSide) => dispatch({ type: 'activate', side, reason: 'hover' }),
    activate: (side: StackedSide) => dispatch({ type: 'activate', side, reason: 'explicit' }),
    focus: (side: StackedSide) => dispatch({ type: 'focus', side }),
    blur: (side: StackedSide) => dispatch({ type: 'blur', side }),
    compositionStart: (side: StackedSide) => dispatch({ type: 'compositionstart', side }),
    compositionEnd: (side: StackedSide) => dispatch({ type: 'compositionend', side }),
  }
}
