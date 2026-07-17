<script setup lang="ts">
/**
 * RequestBodyRuleEditor — 请求体规则配置二级模态框。
 *
 * 顶部：左右双栏 JSON 预览（左=原始可编辑，右=转换后只读），中间箭头。
 * 下方：递归规则列表。
 *
 * 草稿语义：打开时复制父级规则，"应用"才提交，"取消"丢弃。
 * 第一版不落库，仅前端内存态。
 */
import { ref, computed, watch, onBeforeUnmount, nextTick } from 'vue'
import { NModal, NButton, NInput, NScrollbar, NSelect, useMessage } from 'naive-ui'
import type { RuleSet, FieldRule } from '@/features/request-body-rules/types'
import { createEmptyRuleSet } from '@/features/request-body-rules/types'
import { transform } from '@/features/request-body-rules/engine'
import {
  MIMO_EXAMPLE_RULESET,
} from '@/features/request-body-rules/defaultRequestBody'
import {
  composeRequestBodyTemplate,
  DEFAULT_TEMPLATE_KEYS,
  REQUEST_BODY_TEMPLATE_OPTIONS,
} from '@/features/request-body-rules/requestBodyTemplates'
import type { RequestBodyTemplateKey } from '@/features/request-body-rules/requestBodyTemplates'
import { buildDiffTree } from '@/features/request-body-rules/diff'
import RequestBodyRuleList from './RequestBodyRuleList.vue'
import DiffJsonNode from './DiffJsonNode.vue'
import RequestBodyRuleHelp from './RequestBodyRuleHelp.vue'

const props = defineProps<{
  show: boolean
  /** 父级传入的当前规则集（草稿来源） */
  modelRules: RuleSet
}>()

const emit = defineEmits<{
  (e: 'update:show', val: boolean): void
  (e: 'apply', rules: RuleSet): void
}>()

const message = useMessage()

// ==================== 草稿状态 ====================

const draftRules = ref<RuleSet>(createEmptyRuleSet())
const inputJsonText = ref('')
const inputError = ref('')
const lastValidInput = ref<unknown>(null)
const showRuleHelp = ref(false)
const selectedTemplateKeys = ref<RequestBodyTemplateKey[]>([...DEFAULT_TEMPLATE_KEYS])

function templateJson(keys: readonly RequestBodyTemplateKey[]): string {
  return JSON.stringify(composeRequestBodyTemplate(keys), null, 2)
}

// 打开模态框时初始化草稿
watch(
  () => props.show,
  (visible) => {
    if (visible) {
      draftRules.value = JSON.parse(JSON.stringify(props.modelRules || createEmptyRuleSet()))
      selectedTemplateKeys.value = [...DEFAULT_TEMPLATE_KEYS]
      inputJsonText.value = templateJson(selectedTemplateKeys.value)
      parseInput()
    }
  },
)

// ==================== JSON 输入解析 ====================

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
    lastValidInput.value = parsed
    inputError.value = ''
  } catch (e: any) {
    inputError.value = `JSON 语法错误: ${e.message}`
  }
}

// ==================== 高度同步：左栏 resize → 右栏跟随 ====================

const leftInputRef = ref<HTMLElement | null>(null)
const rightOutputRef = ref<HTMLElement | null>(null)
const rightOutputHeight = ref<string>('140px')
let resizeObserver: ResizeObserver | null = null

function syncRightHeight(height: number) {
  rightOutputHeight.value = `${height}px`
}

function resolveLeftDom(): HTMLElement | null {
  const ref = leftInputRef.value as any
  if (!ref) return null
  return ref.$el || ref
}

// ==================== 滚动同步：左右两栏垂直滚动比例同步 ====================

let isSyncingLeftToRight = false
let isSyncingRightToLeft = false
let leftTextarea: HTMLTextAreaElement | null = null
let rightScrollEl: HTMLElement | null = null

function findLeftTextarea(root: HTMLElement | null): HTMLTextAreaElement | null {
  if (!root) return null
  return root.querySelector('textarea')
}

function findRightScrollEl(root: HTMLElement | null): HTMLElement | null {
  // NScrollbar 内部用 .n-scrollbar-container 承载滚动
  if (!root) return null
  return root.querySelector('.n-scrollbar-container') || root.querySelector('.n-scrollbar')
}

function getScrollMetrics(el: HTMLElement): { ratio: number; max: number } {
  const max = el.scrollHeight - el.clientHeight
  const ratio = max > 0 ? el.scrollTop / max : 0
  return { ratio, max }
}

function onLeftScroll() {
  if (isSyncingRightToLeft) return
  if (!leftTextarea || !rightScrollEl) return
  const { ratio, max } = getScrollMetrics(leftTextarea)
  isSyncingLeftToRight = true
  rightScrollEl.scrollTop = ratio * max
  requestAnimationFrame(() => {
    isSyncingLeftToRight = false
  })
}

function onRightScroll() {
  if (isSyncingLeftToRight) return
  if (!leftTextarea || !rightScrollEl) return
  const { ratio, max } = getScrollMetrics(rightScrollEl)
  isSyncingRightToLeft = true
  leftTextarea.scrollTop = ratio * (leftTextarea.scrollHeight - leftTextarea.clientHeight)
  requestAnimationFrame(() => {
    isSyncingRightToLeft = false
  })
}

watch(
  () => props.show,
  async (visible) => {
    if (visible) {
      await nextTick()
      const el = resolveLeftDom()
      if (el && typeof ResizeObserver !== 'undefined') {
        syncRightHeight(el.getBoundingClientRect().height)
        resizeObserver = new ResizeObserver((entries) => {
          for (const entry of entries) {
            const h = entry.contentRect.height
            if (h > 0) syncRightHeight(h)
          }
        })
        resizeObserver.observe(el)
      }

      // 绑定滚动同步
      await nextTick()
      leftTextarea = findLeftTextarea(el)
      const rightRoot = rightOutputRef.value
      rightScrollEl = findRightScrollEl(rightRoot)
      if (leftTextarea) leftTextarea.addEventListener('scroll', onLeftScroll)
      if (rightScrollEl) rightScrollEl.addEventListener('scroll', onRightScroll)
    } else {
      if (resizeObserver) {
        resizeObserver.disconnect()
        resizeObserver = null
      }
      if (leftTextarea) {
        leftTextarea.removeEventListener('scroll', onLeftScroll)
        leftTextarea = null
      }
      if (rightScrollEl) {
        rightScrollEl.removeEventListener('scroll', onRightScroll)
        rightScrollEl = null
      }
    }
  },
)

onBeforeUnmount(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
  if (leftTextarea) {
    leftTextarea.removeEventListener('scroll', onLeftScroll)
  }
  if (rightScrollEl) {
    rightScrollEl.removeEventListener('scroll', onRightScroll)
  }
})

watch(inputJsonText, parseInput)

// ==================== 请求体显示内容组合 ====================

function updateTemplateSelection(keys: RequestBodyTemplateKey[]) {
  if (keys.includes('custom')) {
    const structuredKeys = keys.filter((key) => key !== 'custom')
    if (selectedTemplateKeys.value.length === 1 && selectedTemplateKeys.value[0] === 'custom') {
      selectedTemplateKeys.value = structuredKeys
      inputJsonText.value = templateJson(structuredKeys)
      return
    }
    selectedTemplateKeys.value = ['custom']
    return
  }
  selectedTemplateKeys.value = keys
  inputJsonText.value = templateJson(keys)
}

function updateInputJsonText(value: string) {
  inputJsonText.value = value
  selectedTemplateKeys.value = ['custom']
}

// ==================== 转换预览 ====================

const transformResult = computed(() => {
  if (inputError.value || lastValidInput.value == null) {
    return null
  }
  return transform(lastValidInput.value, draftRules.value)
})

const outputJsonText = computed(() => {
  if (!transformResult.value) return ''
  return JSON.stringify(transformResult.value.output, null, 2)
})

const diffTree = computed(() => {
  if (inputError.value || lastValidInput.value == null || !transformResult.value) {
    return null
  }
  return buildDiffTree(lastValidInput.value, transformResult.value.output)
})

const warnings = computed(() => transformResult.value?.warnings || [])

// ==================== 规则编辑 ====================

const scopeObject = computed<Record<string, unknown> | null>(() => {
  if (lastValidInput.value == null || typeof lastValidInput.value !== 'object') return null
  return lastValidInput.value as Record<string, unknown>
})

function updateRules(rules: FieldRule[]) {
  draftRules.value = { ...draftRules.value, rules }
}

// ==================== 工具操作 ====================

function formatJson() {
  try {
    const parsed = JSON.parse(inputJsonText.value)
    inputJsonText.value = JSON.stringify(parsed, null, 2)
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

function loadMimoExample() {
  draftRules.value = JSON.parse(JSON.stringify(MIMO_EXAMPLE_RULESET))
  message.success('已加载 MiMo 示例规则')
}

function clearRules() {
  draftRules.value = createEmptyRuleSet()
  message.info('已清空规则')
}

// ==================== 应用/取消 ====================

function handleApply() {
  emit('apply', JSON.parse(JSON.stringify(draftRules.value)))
  emit('update:show', false)
  message.success(`已应用 ${draftRules.value.rules.length} 条规则（仅前端预览，不会随供应商保存）`)
}

function handleCancel() {
  emit('update:show', false)
}
</script>

<template>
  <NModal
    :show="show"
    @update:show="emit('update:show', $event)"
    preset="card"
    title="请求体映射规则配置（预览）"
    :style="{ width: '90vw', maxWidth: '1200px', maxHeight: '90vh', display: 'flex', 'flex-direction': 'column' }"
    content-style="overflow: auto; flex: 1; min-height: 0"
    closable
    :mask-closable="true"
  >
    <!-- 提示栏 -->
    <div class="editor-notice">
      <span class="editor-notice-icon">⚠</span>
      <span>第一版仅用于前端预览，规则不会随供应商保存。V2 将支持落库，V3 将接入后端转换。</span>
    </div>

    <!-- 顶部：双栏 JSON 预览 -->
    <div class="editor-preview">
      <!-- 左栏：原始 JSON（可编辑） -->
      <div class="preview-panel">
        <div class="preview-header">
          <div class="preview-heading">
            <span class="preview-title">Copilot 原始请求体</span>
            <NSelect
              :value="selectedTemplateKeys"
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

      <!-- 中间箭头 -->
      <div class="preview-arrow">→</div>

      <!-- 右栏：转换后 JSON（只读） -->
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

    <!-- 警告列表 -->
    <div v-if="warnings.length > 0" class="editor-warnings">
      <div class="warnings-header">执行警告（{{ warnings.length }}）</div>
      <div v-for="(w, i) in warnings" :key="i" class="warning-item">
        <span class="warning-path">{{ w.fieldPath || '(根)' }}</span>
        <span class="warning-msg">{{ w.message }}</span>
      </div>
    </div>

    <!-- 下方：规则列表 -->
    <div class="editor-rules-section">
      <div class="rules-section-header">
        <div class="rules-section-heading">
          <span class="rules-section-title">请求体调整规则列表</span>
          <button
            type="button"
            class="rules-help-button"
            aria-label="查看请求体规则帮助"
            title="查看规则帮助与实时示例"
            @click="showRuleHelp = true"
          >
            ?
          </button>
        </div>
        <div class="rules-section-actions">
          <NButton text size="tiny" @click="loadMimoExample">加载 MiMo 示例</NButton>
          <NButton text size="tiny" @click="clearRules">清空</NButton>
        </div>
      </div>
      <RequestBodyRuleList
        :rules="draftRules.rules"
        :scope-object="scopeObject"
        :depth="0"
        @update:rules="updateRules"
      />
    </div>

    <!-- 底部操作 -->
    <template #footer>
      <div class="editor-footer">
        <span class="editor-footer-count">当前规则：{{ draftRules.rules.length }} 条</span>
        <div class="editor-footer-actions">
          <NButton @click="handleCancel">取消</NButton>
          <NButton type="primary" @click="handleApply">应用</NButton>
        </div>
      </div>
    </template>
  </NModal>

  <RequestBodyRuleHelp v-model:show="showRuleHelp" />
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.editor-notice {
  display: flex;
  align-items: center;
  gap: $space-xs;
  padding: $space-xs $space-sm;
  background: rgba($accent, 0.08);
  border: 1px solid rgba($accent, 0.2);
  border-radius: $radius;
  font-size: 12px;
  color: $text-muted;
  margin-bottom: $space-sm;
}

.editor-notice-icon {
  color: $accent;
  font-size: 14px;
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
  width: 260px;
  min-width: 160px;
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

.preview-input :deep(.n-input) {
  height: 100%;
}

.preview-input :deep(.n-input-wrapper),
.preview-input :deep(.n-input__textarea) {
  height: 100%;
}

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

.editor-rules-section {
  margin-bottom: $space-sm;
}

.rules-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: $space-xs;
}

.rules-section-heading {
  display: flex;
  align-items: center;
  gap: 6px;
}

.rules-section-title {
  font-size: 14px;
  font-weight: 600;
  color: $text-primary;
}

.rules-help-button {
  display: inline-flex;
  width: 19px;
  height: 19px;
  align-items: center;
  justify-content: center;
  padding: 0;
  border: 1px solid rgba($accent, 0.42);
  border-radius: 50%;
  background: $accent-light;
  color: $accent;
  font-family: inherit;
  font-size: 12px;
  font-weight: 700;
  line-height: 1;
  cursor: pointer;
  transition: background 0.16s ease, border-color 0.16s ease, transform 0.16s ease;
}

.rules-help-button:hover {
  border-color: $accent;
  background: $accent-mid;
  transform: translateY(-1px);
}

.rules-help-button:focus-visible {
  outline: 2px solid $accent-glow;
  outline-offset: 2px;
}

.rules-section-actions {
  display: flex;
  gap: $space-xs;
}

.editor-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.editor-footer-count {
  font-size: 12px;
  color: $text-muted;
}

.editor-footer-actions {
  display: flex;
  gap: $space-xs;
}

/* 窄屏：纵向排列 */
@media (max-width: 768px) {
  .editor-preview {
    flex-direction: column;
  }

  .preview-arrow {
    transform: rotate(90deg);
    justify-content: center;
    padding: $space-xs 0;
  }
}
</style>
