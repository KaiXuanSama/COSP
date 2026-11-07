<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Compartment, EditorSelection, EditorState, Transaction, type StateEffect } from '@codemirror/state'
import { EditorView, keymap, lineNumbers, placeholder } from '@codemirror/view'
import { defaultKeymap, history, historyKeymap, isolateHistory } from '@codemirror/commands'
import type { DiffDisplayLine, LineScrollPort } from './lines'
import { comparisonField, createEditorScrollPort, setComparisonLines } from './editor'

const props = withDefaults(defineProps<{
  value: string
  lines?: readonly DiffDisplayLine[] | null
  ariaLabel?: string
  placeholder?: string
  invalid?: boolean
}>(), { lines: null, ariaLabel: '编辑文本', placeholder: '', invalid: false })
const emit = defineEmits<{
  (event: 'update:value', value: string): void
  (event: 'port', port: LineScrollPort | null): void
}>()
const element = ref<HTMLElement | null>(null)
const attributes = new Compartment()
let view: EditorView | null = null
let port: ReturnType<typeof createEditorScrollPort> | null = null
let applyingExternal = false
let lineBreak = '\n'
const detectBreak = (text: string) => text.match(/\r\n|\r|\n/)?.[0] ?? '\n'
const documentText = () => view?.state.doc.sliceString(0, undefined, lineBreak) ?? ''
const contentAttributes = () => EditorView.contentAttributes.of({
  'aria-label': props.ariaLabel, 'aria-invalid': String(props.invalid), spellcheck: 'false',
})

onMounted(() => {
  if (!element.value) return
  lineBreak = detectBreak(props.value)
  view = new EditorView({
    parent: element.value,
    state: EditorState.create({ doc: props.value, extensions: [
      attributes.of(contentAttributes()),
      comparisonField, history(), lineNumbers(), EditorView.lineWrapping,
      keymap.of([...historyKeymap, ...defaultKeymap]),
      placeholder(props.placeholder),
      EditorView.updateListener.of(update => {
        if (update.docChanged && !applyingExternal) emit('update:value', documentText())
        if (update.docChanged || update.geometryChanged || update.viewportChanged) port?.layoutChanged()
      }),
    ] }),
  })
  view.dispatch({ effects: setComparisonLines.of(props.lines) })
  port = createEditorScrollPort(view)
  emit('port', port)
})

watch(() => [props.value, props.lines] as const, ([value, lines]) => {
  if (!view) return
  const current = documentText()
  const effects: StateEffect<unknown>[] = [setComparisonLines.of(lines)]
  if (current !== value) {
    lineBreak = detectBreak(value)
    // 保留未改变的前后缀，使格式化/外部样本替换以最小事务进入同一份撤销历史。
    const before = view.state.doc.toString()
    const after = value.replace(/\r\n|\r/g, '\n')
    let from = 0
    while (from < before.length && from < after.length && before[from] === after[from]) from++
    let oldEnd = before.length
    let newEnd = after.length
    while (oldEnd > from && newEnd > from && before[oldEnd - 1] === after[newEnd - 1]) { oldEnd--; newEnd-- }
    applyingExternal = true
    try {
      view.dispatch({
        changes: { from, to: oldEnd, insert: EditorState.create({ doc: after.slice(from, newEnd) }).doc },
        effects, annotations: [Transaction.userEvent.of('input'), isolateHistory.of('full')],
      })
    } finally { applyingExternal = false }
  } else view.dispatch({ effects, annotations: Transaction.addToHistory.of(false) })
  port?.layoutChanged()
}, { flush: 'post' })
watch(() => [props.ariaLabel, props.invalid], () => {
  view?.dispatch({ effects: attributes.reconfigure(contentAttributes()) })
})

function selectAll() {
  if (!view) return
  view.focus()
  view.dispatch({ selection: EditorSelection.single(0, view.state.doc.length) })
}
defineExpose({ selectAll })
onBeforeUnmount(() => {
  port?.dispose()
  emit('port', null)
  view?.destroy()
})
</script>

<template>
  <div ref="element" class="diff-text-editor" />
</template>

<style scoped lang="scss">
.diff-text-editor {
  display: flex;
  flex: 1 1 0;
  min-width: 0;
  min-height: 0;
  height: 100%;
  width: 100%;
  color: inherit;
}
.diff-text-editor :deep(.cm-editor) {
  flex: 1 1 0;
  min-width: 0;
  height: 100%;
  background: transparent;
  color: inherit;
  font-family: var(--stacked-diff-text-font, 'Cascadia Code', Consolas, monospace);
  font-size: var(--stacked-diff-text-size, 13px);
  line-height: var(--stacked-diff-text-line-height, 1.75);
}
.diff-text-editor :deep(.cm-editor.cm-focused) { outline: none; }
.diff-text-editor :deep(.cm-scroller) {
  overflow: auto;
  font: inherit;
  line-height: inherit;
  scrollbar-width: thin;
}
.diff-text-editor :deep(.cm-content) {
  padding: var(--stacked-diff-text-padding, 16px) var(--stacked-diff-text-padding, 16px) var(--stacked-diff-text-padding, 16px) 0;
  caret-color: currentColor;
  overflow-wrap: anywhere;
  tab-size: 2;
}
.diff-text-editor :deep(.cm-line) { padding: 0; }
.diff-text-editor :deep(.cm-gutters) {
  background: transparent;
  color: var(--stacked-diff-muted, #71717a);
  border: 0;
  padding-left: var(--stacked-diff-text-padding, 16px);
  font-size: 11px;
}
.diff-text-editor :deep(.cm-lineNumbers .cm-gutterElement) {
  box-sizing: content-box;
  min-width: 2ch;
  padding: 0 10px 0 0;
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}
.diff-text-editor :deep(.diff-editor-placeholder-block) {
  pointer-events: none;
  user-select: none;
}
.diff-text-editor :deep(.diff-editor-placeholder) {
  height: 1lh;
  min-height: 1lh;
  background: repeating-linear-gradient(135deg,
    transparent 0 4px, color-mix(in srgb, var(--stacked-diff-muted, #71717a) 13%, transparent) 4px 5px);
}
</style>