export { default as LayeredPanel } from './LayeredPanel.vue'
export { default as StackedDiff } from './StackedDiff.vue'
export { useStackedLayers } from './useStackedLayers'
export type { UseStackedLayersOptions } from './useStackedLayers'
export { createStackedLayers, lockedLayer, reduceStackedLayers } from './layers'
export type {
  LayerActivationReason,
  LayerChange,
  StackedLayersEvent,
  StackedLayersState,
  StackedSide,
} from './layers'
