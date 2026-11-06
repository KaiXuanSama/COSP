export { default as LayeredPanel } from './LayeredPanel.vue'
export { default as StackedDiff } from './StackedDiff.vue'
export { default as DiffTextView } from './DiffTextView.vue'
export { createTextareaScrollPort, createDisplayScrollPort } from './scrollPorts'
export { compileLineMap, anchorAtScroll, scrollAtAnchor } from './lines'
export type {
  TextDiffStatus, TextLineRange, TextLinePair, TextLineMap, TextLineAnchor,
  DiffDisplayLine, LineScrollPort, MeasuredTextLine,
} from './lines'
export { useLinkedScroll } from './useLinkedScroll'
export type { LinkedScrollOptions } from './useLinkedScroll'
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
