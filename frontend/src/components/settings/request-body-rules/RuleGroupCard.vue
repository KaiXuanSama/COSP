<script setup lang="ts">
/**
 * RuleGroupCard — 单个请求体规则组的编辑卡片。
 *
 * 卡片头部是组的元信息（组名、适用线路、启用开关、排序与删除），
 * 中部是**该组专属**的「请求体编辑预览 / 转换后请求体」双栏，下部是该组的规则列表。
 *
 * <h2>为何每组各带一份预览样本</h2>
 * 不同线路协议的请求体形状不同（Anthropic 的 `system` 在顶层、必带 `max_tokens`），
 * 用同一个样本调试两条线路的规则会让至少一边完全匹配不到。代价是组间预览**不串联** ——
 * 每组的预览是自包含的调试样本，不是上一组的输出；运行时多组会依次作用于同一请求体，
 * 编辑器刻意不模拟这一点。
 *
 * <h2>预览文本与保存值的关系</h2>
 * 左栏文本是纯本地编辑状态，只有解析成功才向上提交为 `previewBody`。
 * 因此 JSON 写坏时卡片内显示错误、保存的仍是上一份可用样本，不会阻断整个编辑器的应用。
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { NButton, NInput, NScrollbar, NSelect, NSwitch, useMessage } from 'naive-ui'
import type { FieldRule, RuleGroup } from '@/features/request-body-rules/types'
import { transformWithRules } from '@/features/request-body-rules/engine'
import { MIMO_EXAMPLE_RULESET } from '@/features/request-body-rules/defaultRequestBody'
import {
  composeRequestBodyTemplate,
  REQUEST_BODY_TEMPLATE_OPTIONS,
} from '@/features/request-body-rules/requestBodyTemplates'
import type { RequestBodyTemplateKey } from '@/features/request-body-rules/requestBodyTemplates'
import { buildDiffTree } from '@/features/request-body-rules/diff'
import { ALL_WIRE_PROTOCOLS, WIRE_PROTOCOL_LABELS, type WireProtocol } from '@/types/protocol'
import RequestBodyRuleList from './RequestBodyRuleList.vue'
import DiffJsonNode from './DiffJsonNode.vue'

const props = defineProps<{
  group: RuleGroup
  /** 组在列表中的序号，用于上移/下移按钮的可用性 */
  index: number
  /** 组总数 */
  total: number
}>()

const emit = defineEmits<{
  (e: 'update:group', value: RuleGroup): void
  (e: 'move', direction: -1 | 1): void
  (e: 'remove'): void
}>()

const message = useMessage()

const PROTOCOL_OPTIONS = ALL_WIRE_PROTOCOLS.map((protocol) => ({
  label: WIRE_PROTOCOL_LABELS[protocol],
  value: protocol,
}))

// ==================== 预览输入 ====================

const inputJsonText = ref(JSON.stringify(props.group.previewBody, null, 2))
const inputError = ref('')

function patch(value: Partial<RuleGroup>) {
  emit('update:group', { ...props.group, ...value })
}

function templateJson(keys: readonly RequestBodyTemplateKey[]): string {
  return JSON.stringify(composeRequestBodyTemplate(keys), null, 2)
}

/** 解析左栏文本；成功时把结果提交为该组的预览样本。 */
function parseInput() {
  const trimmed = inputJsonText.value.trim()
  if (!trimmed) {
    inputError.value = '输入为空'
    return
  }
  try {
    const parsed = JSON.parse(trimmed)
    if (parsed == null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      inputError.value = '请求体必须是 JSON 对象'
      return
    }
    inputError.value = ''
    patch({ previewBody: parsed as Record<string, unknown> })
  } catch (error: any) {
    inputError.value = `JSON 语法错误: ${error.message}`
  }
}

watch(inputJsonText, parseInput)

function updateTemplateSelection(keys: RequestBodyTemplateKey[]) {
  if (keys.includes('custom')) {
    const structuredKeys = keys.filter((key) => key !== 'custom')
    const onlyCustom = props.group.templateKeys.length === 1 && props.group.templateKeys[0] === 'custom'
    if (onlyCustom) {
      patch({ templateKeys: structuredKeys })
      inputJsonText.value = templateJson(structuredKeys)
      return
    }
    patch({ templateKeys: ['custom'] })
    return
  }
  patch({ templateKeys: keys })
  inputJsonText.value = templateJson(keys)
}

/** 手工编辑左栏即视为自定义样本，模板下拉切到「自定义」。 */
function updateInputJsonText(value: string) {
  inputJsonText.value = value
  patch({ templateKeys: ['custom'] })
}

// ==================== 转换预览 ====================

const transformResult = computed(() => {
  if (inputError.value) return null
  return transformWithRules(props.group.previewBody, props.group.rules)
})

const outputJsonText = computed(() =>
  transformResult.value ? JSON.stringify(transformResult.value.output, null, 2) : '',
)

const diffTree = computed(() => {
  if (!transformResult.value) return null
  return buildDiffTree(props.group.previewBody, transformResult.value.output)
})

const warnings = computed(() => transformResult.value?.warnings ?? [])

const scopeObject = computed<Record<string, unknown> | null>(() => props.group.previewBody ?? null)

/** protocols 为空数组是显式的「哪条线路都不要」，与「没勾」在语义上不同，值得提示。 */
const hasNoProtocol = computed(() => props.group.protocols.length === 0)

// ==================== 高度与滚动同步 ====================

const leftInputRef = ref<HTMLElement | null>(null)
const rightOutputRef = ref<HTMLElement | null>(null)
const rightOutputHeight = ref('140px')
let resizeObserver: ResizeObserver | null = null
let leftTextarea: HTMLTextAreaElement | null = null
let rightScrollEl: HTMLElement | null = null
let syncingLeftToRight = false
let syncingRightToLeft = false

function resolveLeftDom(): HTMLElement | null {
  const instance = leftInputRef.value as any
  if (!instance) return null
  return instance.$el || instance
}

function getScrollMetrics(el: HTMLElement): { ratio: number; max: number } {
  const max = el.scrollHeight - el.clientHeight
  return { ratio: max > 0 ? el.scrollTop / max : 0, max }
}

function onLeftScroll() {
  if (syncingRightToLeft || !leftTextarea || !rightScrollEl) return
  const { ratio } = getScrollMetrics(leftTextarea)
  const { max } = getScrollMetrics(rightScrollEl)
  syncingLeftToRight = true
  rightScrollEl.scrollTop = ratio * max
  requestAnimationFrame(() => {
    syncingLeftToRight = false
  })
}

function onRightScroll() {
  if (syncingLeftToRight || !leftTextarea || !rightScrollEl) return
  const { ratio } = getScrollMetrics(rightScrollEl)
  syncingRightToLeft = true
  leftTextarea.scrollTop = ratio * (leftTextarea.scrollHeight - leftTextarea.clientHeight)
  requestAnimationFrame(() => {
    syncingRightToLeft = false
  })
}

function teardownSync() {
  resizeObserver?.disconnect()
  resizeObserver = null
  leftTextarea?.removeEventListener('scroll', onLeftScroll)
  rightScrollEl?.removeEventListener('scroll', onRightScroll)
  leftTextarea = null
  rightScrollEl = null
}

/**
 * 绑定「左栏 resize → 右栏跟随」与两栏滚动比例同步。
 *
 * 卡片是 v-for 出来的，每个实例各自绑定自己的 DOM —— 这段逻辑只有一份代码、N 份状态。
 */
async function setupSync() {
  await nextTick()
  const el = resolveLeftDom()
  if (el && typeof ResizeObserver !== 'undefined') {
    rightOutputHeight.value = `${el.getBoundingClientRect().height}px`
    resizeObserver = new ResizeObserver((entries) => {
      for (const entry of entries) {
        if (entry.contentRect.height > 0) {
          rightOutputHeight.value = `${entry.contentRect.height}px`
        }
      }
    })
    resizeObserver.observe(el)
  }
  leftTextarea = el?.querySelector('textarea') ?? null
  const rightRoot = rightOutputRef.value
  rightScrollEl = rightRoot?.querySelector('.n-scrollbar-container')
    ?? rightRoot?.querySelector('.n-scrollbar')
    ?? null
  leftTextarea?.addEventListener('scroll', onLeftScroll)
  rightScrollEl?.addEventListener('scroll', onRightScroll)
}

setupSync()
onBeforeUnmount(teardownSync)

// ==================== 规则操作 ====================

function updateRules(rules: FieldRule[]) {
  patch({ rules })
}

function loadImageToolCompatibilityRules() {
  patch({ rules: JSON.parse(JSON.stringify(MIMO_EXAMPLE_RULESET.rules)) })
  message.success('已加载图片工具消息兼容规则')
}

function clearRules() {
  patch({ rules: [] })
  message.info('已清空该组规则')
}

function formatJson() {
  try {
    inputJsonText.value = JSON.stringify(JSON.parse(inputJsonText.value), null, 2)
    message.success('已格式化')
  } catch {
    message.error('JSON 语法错误，无法格式化')
  }
}

function copyOutput() {
  if (!outputJsonText.value) return
  navigator.clipboard.writeText(outputJsonText.value).then(() => {
    message.success('已复制转换结果')
  })
}
</script>

<template>
  <section class="group-card" :class="{ 'group-card--disabled': !group.enabled }">
    <!-- 卡片头部：组元信息 -->
    <header class="group-header">
      <div class="group-identity">
        <span class="group-order">{{ index + 1 }}</span>
        <label class="group-field">
          <span class="group-field-label">规则组名</span>
          <NInput
            :value="group.name"
            @update:value="patch({ name: $event })"
            size="small"
            placeholder="规则组名称"
            class="group-name-input"
          />
        </label>
        <label class="group-field">
          <span class="group-field-label">适用协议</span>
          <NSelect
            :value="group.protocols"
            @update:value="patch({ protocols: $event as WireProtocol[] })"
            :options="PROTOCOL_OPTIONS"
            multiple
            size="small"
            class="group-protocol-select"
            placeholder="适用协议"
          />
        </label>
      </div>
      <div class="group-controls">
        <NSwitch
          :value="group.enabled"
          @update:value="patch({ enabled: $event })"
          size="small"
        >
          <template #checked>启用</template>
          <template #unchecked>停用</template>
        </NSwitch>
        <div class="group-order-actions">
          <NButton
            size="tiny"
            class="group-icon-button"
            title="上移"
            :disabled="index === 0"
            @click="emit('move', -1)"
          >
            ↑
          </NButton>
          <NButton
            size="tiny"
            class="group-icon-button"
            title="下移"
            :disabled="index === total - 1"
            @click="emit('move', 1)"
          >
            ↓
          </NButton>
          <NButton size="tiny" class="group-icon-button group-icon-button--danger" title="删除该组" @click="emit('remove')">
            ×
          </NButton>
        </div>
      </div>
    </header>

    <div v-if="hasNoProtocol" class="group-hint group-hint--warning">
      未选择任何适用线路，该组的规则不会在任何请求上执行。
    </div>

    <!-- 该组专属的双栏预览 -->
    <div class="editor-preview">
      <div class="preview-panel">
        <div class="preview-header">
          <div class="preview-heading">
            <span class="preview-title">请求体编辑预览</span>
            <NSelect
              :value="group.templateKeys"
              @update:value="updateTemplateSelection"
              :options="REQUEST_BODY_TEMPLATE_OPTIONS"
              multiple
              clearable
              max-tag-count="responsive"
              size="small"
              class="preview-template-select"
              placeholder="选择显示内容"
            />
          </div>
          <div class="preview-actions">
            <NButton text size="tiny" @click="formatJson">格式化</NButton>
          </div>
        </div>
        <NInput
          ref="leftInputRef"
          :value="inputJsonText"
          @update:value="updateInputJsonText"
          type="textarea"
          :autosize="{ minRows: 3, maxRows: 30 }"
          :resizable="true"
          class="preview-input"
          placeholder="在此编辑或粘贴请求体 JSON"
        />
        <div v-if="inputError" class="preview-error">{{ inputError }}</div>
      </div>

      <div class="preview-arrow">→</div>

      <div class="preview-panel">
        <div class="preview-header">
          <span class="preview-title">转换后请求体</span>
          <div class="preview-actions">
            <NButton text size="tiny" :disabled="!outputJsonText" @click="copyOutput">复制</NButton>
          </div>
        </div>
        <div ref="rightOutputRef" class="preview-output-wrapper" :style="{ height: rightOutputHeight }">
          <NScrollbar class="preview-output-scroll">
            <div v-if="diffTree" class="preview-output-tree">
              <DiffJsonNode :node="diffTree" :is-last="true" />
            </div>
            <pre v-else class="preview-output">（请先修正左侧 JSON）</pre>
          </NScrollbar>
        </div>
      </div>
    </div>

    <div v-if="warnings.length > 0" class="editor-warnings">
      <div class="warnings-header">执行警告（{{ warnings.length }}）</div>
      <div v-for="(warning, i) in warnings" :key="i" class="warning-item">
        <span class="warning-path">{{ warning.fieldPath || '(根)' }}</span>
        <span class="warning-msg">{{ warning.message }}</span>
      </div>
    </div>

    <!-- 该组的规则列表 -->
    <div class="group-rules">
      <div class="rules-section-header">
        <span class="rules-section-title">该组规则（{{ group.rules.length }} 条）</span>
        <div class="rules-section-actions">
          <NButton size="tiny" class="rules-action-button" @click="loadImageToolCompatibilityRules">
            加载图片工具兼容规则
          </NButton>
          <NButton size="tiny" class="rules-action-button" @click="clearRules">清空</NButton>
        </div>
      </div>
      <RequestBodyRuleList
        :rules="group.rules"
        :scope-object="scopeObject"
        :depth="0"
        @update:rules="updateRules"
      />
    </div>
  </section>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.group-card {
  border: 1px solid $border;
  border-radius: $radius;
  padding: $space-sm;
  margin-bottom: $space-sm;
  background: rgba($accent, 0.02);
}

.group-card--disabled {
  opacity: 0.62;
}

.group-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $space-sm;
  flex-wrap: wrap;
  margin-bottom: $space-xs;
}

.group-identity {
  display: flex;
  align-items: center;
  gap: $space-sm;
  min-width: 0;
  flex: 1;
  flex-wrap: wrap;
}

.group-order {
  display: inline-flex;
  width: 20px;
  height: 20px;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: $accent-light;
  border: 1px solid rgba($accent, 0.42);
  color: $accent;
  font-size: 11px;
  font-weight: 700;
  flex-shrink: 0;
}

/**
 * 「标签 + 控件」一组。
 *
 * 用 label 包裹而非并列的 span，是为了点标签能聚焦对应控件；
 * 标签 nowrap 且不收缩，避免窄容器下把「适用协议」折成两行。
 */
.group-field {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.group-field-label {
  font-size: 12px;
  color: $text-muted;
  white-space: nowrap;
  flex-shrink: 0;
}

.group-name-input {
  max-width: 220px;
}

.group-controls {
  display: flex;
  align-items: center;
  gap: $space-sm;
}

.group-protocol-select {
  width: 230px;
}

.group-order-actions {
  display: flex;
  gap: 4px;
}

.group-icon-button {
  --n-height: 22px !important;
  --n-padding: 0 7px !important;
  font-size: 12px;
}

/**
 * 删除按钮用实心红底。
 *
 * 与上移/下移的弱化描边按钮拉开对比 —— 删除是唯一会丢内容的操作，
 * 视觉重量应该和它的后果匹配，而不是混在三个同形按钮里。
 *
 * 深浅变体写死十六进制而非用 `darken()` / `lighten()`：那两个函数在当前 sass 版本
 * 已标记废弃（构建期会刷警告），而这里只需要两个固定色值。
 */
.group-icon-button--danger {
  --n-color: #{$danger} !important;
  --n-color-hover: #c85a5a !important;
  --n-color-pressed: #a03f3f !important;
  --n-color-focus: #c85a5a !important;
  --n-text-color: #{$text-light} !important;
  --n-text-color-hover: #{$text-light} !important;
  --n-text-color-pressed: #{$text-light} !important;
  --n-text-color-focus: #{$text-light} !important;
  --n-border: 1px solid #{$danger} !important;
  --n-border-hover: 1px solid #c85a5a !important;
  --n-border-pressed: 1px solid #a03f3f !important;
  --n-border-focus: 1px solid #c85a5a !important;
  font-weight: 700;
}

.group-hint {
  font-size: 11px;
  padding: 4px $space-xs;
  border-radius: $radius;
  margin-bottom: $space-xs;
}

.group-hint--warning {
  color: $warning;
  background: rgba($warning, 0.08);
  border: 1px solid rgba($warning, 0.2);
}

.editor-preview {
  display: flex;
  align-items: stretch;
  gap: $space-sm;
  margin-bottom: $space-sm;
}

.preview-panel {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.preview-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.preview-heading {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: $space-sm;
}

.preview-template-select {
  width: 220px;
  min-width: 140px;
}

.preview-title {
  font-size: 13px;
  font-weight: 600;
  color: $text-primary;
}

.preview-actions {
  display: flex;
  gap: $space-xs;
}

.preview-input {
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  height: 140px;
  min-height: 100px;
  max-height: 600px;
  border: 1px solid $border;
  border-radius: $radius;
  resize: vertical;
  overflow: auto;
}

.preview-input :deep(.n-input),
.preview-input :deep(.n-input-wrapper),
.preview-input :deep(.n-input__textarea),
.preview-input :deep(.n-input__textarea-el) {
  height: 100%;
}

.preview-output-wrapper {
  border: 1px solid $border;
  border-radius: $radius;
  background: $bg;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}

.preview-output-scroll {
  flex: 1;
  height: 100%;
}

.preview-output {
  margin: 0;
  padding: $space-sm;
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.6;
  color: $text-muted;
  white-space: pre-wrap;
  word-break: break-all;
}

.preview-output-tree {
  padding: $space-sm;
  color: $text-muted;
}

.preview-error {
  color: $danger;
  font-size: 12px;
  margin-top: 4px;
}

.preview-arrow {
  display: flex;
  align-items: center;
  font-size: 24px;
  color: $accent;
  flex-shrink: 0;
}

.editor-warnings {
  margin-bottom: $space-sm;
  padding: $space-xs $space-sm;
  background: rgba($warning, 0.08);
  border: 1px solid rgba($warning, 0.2);
  border-radius: $radius;
}

.warnings-header {
  font-size: 12px;
  font-weight: 600;
  color: $warning;
  margin-bottom: 4px;
}

.warning-item {
  display: flex;
  gap: $space-xs;
  font-size: 11px;
  color: $text-muted;
}

.warning-path {
  font-family: monospace;
  color: $accent;
  flex-shrink: 0;
}

.rules-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: $space-xs;
}

.rules-section-title {
  font-size: 13px;
  font-weight: 600;
  color: $text-primary;
}

.rules-section-actions {
  display: flex;
  gap: $space-xs;
}

.rules-action-button {
  --n-height: 24px !important;
  --n-padding: 0 9px !important;
  --n-border: 1px solid rgba(194, 122, 62, 0.3) !important;
  --n-border-hover: 1px solid rgba(194, 122, 62, 0.58) !important;
  --n-border-pressed: 1px solid #{$accent} !important;
  --n-border-focus: 1px solid rgba(194, 122, 62, 0.58) !important;
  --n-color: rgba(194, 122, 62, 0.07) !important;
  --n-color-hover: rgba(194, 122, 62, 0.13) !important;
  --n-color-pressed: rgba(194, 122, 62, 0.18) !important;
  --n-color-focus: rgba(194, 122, 62, 0.13) !important;
  --n-text-color: #{$text-body} !important;
  --n-text-color-hover: #{$accent} !important;
  --n-text-color-pressed: #{$accent} !important;
  --n-text-color-focus: #{$accent} !important;
  --n-border-radius: 6px !important;
  font-size: 11px;
}
</style>
