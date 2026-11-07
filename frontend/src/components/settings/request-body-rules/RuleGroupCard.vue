<script setup lang="ts">
/**
 * RuleGroupCard — 单个请求体规则组的编辑卡片。
 *
 * 卡片头部是组元信息与样本预设；下方约 2:3 排列叠放预览与该组规则。
 * 右侧规则自然撑高整组，左侧文本在分配到的高度内滚动，不反向撑高规则区。
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
import { computed, ref, shallowRef } from 'vue'
import { NButton, NInput, NSelect, NSwitch, useMessage } from 'naive-ui'
import type { FieldRule, RuleGroup } from '@/features/request-body-rules/types'
import { useRuleGroupPreview } from '@/features/request-body-rules/useRuleGroupPreview'
import { previewRequestBodyRules } from '@/api'
import { MIMO_EXAMPLE_RULESET } from '@/features/request-body-rules/defaultRequestBody'
import { REQUEST_BODY_TEMPLATE_OPTIONS } from '@/features/request-body-rules/requestBodyTemplates'
import { StackedDiff, DiffTextEditor, DiffTextView, type LineScrollPort } from '@/components/stacked-diff'
import {
  RULE_ENGINE_WIRE_PROTOCOLS,
  type WireProtocol,
} from '@/types/protocol'
import RequestBodyRuleList from './RequestBodyRuleList.vue'
import { provideRuleSelectMenuTarget } from './useRuleSelectMenuTarget'

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
const rulesArea = ref<HTMLElement | null>(null)
provideRuleSelectMenuTarget(rulesArea)

/**
 * 「适用协议」的可选项。
 *
 * <p>取 `RULE_ENGINE_WIRE_PROTOCOLS` 而非 `ALL_WIRE_PROTOCOLS`：这个下拉的取值必须与
 * **后端白名单**一致，而那是一个独立于「系统认识哪些协议」的约束。两者当前数值相同，
 * 但曾短暂分叉（Responses 加入系统后、后端放开白名单之前），那段时间里用错常量会让
 * 界面主动提供一个保存时被拒的选择。
 *
 * <p>三条协议的规则组现在都会被真正执行 —— `POST /v1/responses` 已接入直连。
 * 放开白名单时它还没接入，那段「选项可选但规则不会执行」是有意的先行：请求体改写
 * 是接一个新中转站时最先需要配置的能力，等端点落地才放开会让用户在上线当天才能开始配。
 */
const PROTOCOL_SHORT_LABELS: Record<WireProtocol, string> = {
  CHAT: 'Chat',
  MESSAGES: 'Mess',
  RESPONSES: 'Res',
}
const PROTOCOL_OPTIONS = RULE_ENGINE_WIRE_PROTOCOLS.map((protocol) => ({
  label: PROTOCOL_SHORT_LABELS[protocol],
  value: protocol,
}))

function patch(value: Partial<RuleGroup>) {
  emit('update:group', { ...props.group, ...value })
}

const {
  inputJsonText, inputError, scheduler, lineView, outputJsonText, warnings, previewNotice,
  updateInputJsonText, updateTemplateSelection, formatInput,
} = useRuleGroupPreview({
  group: () => props.group,
  update: value => emit('update:group', value),
  requester: payload => previewRequestBodyRules(payload.previewBody, payload.rules).then(response => response.data),
})
const transformResult = scheduler.result
const resultLines = computed(() => lineView.value.comparison?.resultLines ?? [])
const sourceLines = computed(() => lineView.value.lineMap ? lineView.value.comparison?.sourceLines ?? null : null)
const virtualLineMap = computed(() => lineView.value.lineMap ? lineView.value.comparison?.virtualLineMap ?? null : null)

const scopeObject = computed<Record<string, unknown> | null>(() => props.group.previewBody ?? null)

/** protocols 为空数组是显式的「哪条线路都不要」，与「没勾」在语义上不同，值得提示。 */
const hasNoProtocol = computed(() => props.group.protocols.length === 0)

const sourcePort = shallowRef<LineScrollPort | null>(null)
const resultPort = shallowRef<LineScrollPort | null>(null)

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
  if (formatInput()) message.success('已格式化')
  else message.error('请求体不是有效 JSON 对象，无法格式化')
}

async function copyOutput() {
  if (!outputJsonText.value) return
  try {
    await navigator.clipboard.writeText(outputJsonText.value)
    message.success('已复制转换结果')
  } catch {
    message.error('复制失败，请检查浏览器剪贴板权限')
  }
}
</script>

<template>
  <section class="group-card" :class="{ 'group-card--disabled': !group.enabled }">
    <!-- 卡片头部：组元信息 -->
    <header class="group-header">
      <div class="group-identity">
        <span class="group-order">{{ index + 1 }}</span>
        <label class="group-field group-field--name">
          <span class="group-field-label">规则组名</span>
          <NInput
            :value="group.name"
            @update:value="patch({ name: $event })"
            size="small"
            placeholder="规则组名称"
            class="group-name-input"
          />
        </label>
        <label class="group-field group-field--protocol">
          <span class="group-field-label">适用协议</span>
          <NSelect
            :value="group.protocols"
            @update:value="patch({ protocols: $event as WireProtocol[] })"
            :options="PROTOCOL_OPTIONS"
            multiple
            max-tag-count="responsive"
            size="small"
            class="group-protocol-select"
            placeholder="适用协议"
          />
        </label>
        <label class="group-field group-field--template">
          <span class="group-field-label">预设请求体</span>
          <NSelect
            :value="group.templateKeys"
            @update:value="updateTemplateSelection"
            :options="REQUEST_BODY_TEMPLATE_OPTIONS"
            multiple
            clearable
            max-tag-count="responsive"
            size="small"
            class="group-template-select"
            placeholder="选择预览内容"
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

    <div class="group-workspace">
      <div class="group-preview">
        <StackedDiff
          class="group-stacked-preview"
          source-title="请求体编辑区"
          result-title="转换后请求体"
          :aria-label="`${group.name || '规则组'}的请求体对比`"
          :source-scroll-port="sourcePort"
          :result-scroll-port="resultPort"
          :line-map="inputError ? null : virtualLineMap"
        >
          <template #source-actions>
            <NButton text size="tiny" @click="formatJson">格式化</NButton>
          </template>
          <template #source>
            <DiffTextEditor
              :value="inputJsonText"
              :lines="sourceLines"
              :invalid="!!inputError"
              aria-label="请求体编辑区"
              placeholder="在此编辑或粘贴请求体 JSON"
              @update:value="updateInputJsonText"
              @port="sourcePort = $event"
            />
          </template>
          <template v-if="inputError" #source-notice><span class="preview-error">{{ inputError }}</span></template>
          <template #result-title>
            <span>转换后请求体</span>
            <span v-if="scheduler.loading.value" class="preview-badge">计算中…</span>
            <span v-else-if="scheduler.stale.value && transformResult" class="preview-badge preview-badge--stale">
              结果已过期
            </span>
          </template>
          <template #result-actions>
            <NButton text size="tiny" :disabled="!outputJsonText" @click="copyOutput">复制</NButton>
          </template>
          <template #result>
            <DiffTextView
              v-if="lineView.comparison"
              :lines="resultLines"
              aria-label="转换后请求体内容"
              @port="resultPort = $event"
            />
            <pre v-else class="preview-output">{{ outputJsonText || previewNotice }}</pre>
          </template>
          <template v-if="previewNotice" #result-notice>
            <span :class="{ 'preview-error': scheduler.error.value }">{{ previewNotice }}</span>
          </template>
        </StackedDiff>
      </div>

      <div class="group-rules">
        <div ref="rulesArea" class="group-rules-zoom">
          <div class="rules-section-header">
            <span class="rules-section-title">该组规则（{{ group.rules.length }} 条）</span>
            <div class="rules-section-actions">
              <NButton size="tiny" class="rules-action-button" @click="loadImageToolCompatibilityRules">
                加载图片工具兼容规则
              </NButton>
              <NButton size="tiny" class="rules-action-button" @click="clearRules">清空</NButton>
            </div>
          </div>
          <div v-if="warnings.length > 0" class="editor-warnings">
            <div class="warnings-header">执行警告（{{ warnings.length }}）</div>
            <div v-for="(warning, i) in warnings" :key="i" class="warning-item">
              <span class="warning-path">{{ warning.fieldPath || '(根)' }}</span>
              <span class="warning-msg">{{ warning.message }}</span>
            </div>
          </div>
          <RequestBodyRuleList
            :rules="group.rules"
            :scope-object="scopeObject"
            :depth="0"
            @update:rules="updateRules"
          />
        </div>
      </div>
    </div>
  </section>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.group-card {
  container: rule-group / inline-size;
  min-width: 0;
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
  margin-bottom: $space-md;
}

.group-identity {
  display: flex;
  align-items: center;
  gap: $space-sm;
  min-width: 0;
  /* 按字段真实所需宽度参与换行判断，避免固定 basis 在有空余时也把右侧控件挤下去。 */
  flex: 1 1 auto;
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
  width: 160px;
  min-width: 0;
  flex-shrink: 1;
}

.group-controls {
  display: flex;
  align-items: center;
  gap: $space-sm;
  flex-shrink: 0;
}

.group-protocol-select {
  width: 120px;
  min-width: 0;
  flex-shrink: 1;
}

.group-template-select {
  width: 180px;
  min-width: 0;
  flex-shrink: 1;
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

.group-workspace {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(0, 3fr);
  align-items: stretch;
  gap: $space-md;
  min-height: 380px;
}

.group-preview {
  position: relative;
  min-width: 0;
  min-height: 380px;
}

// 预览绝对填充自身网格区域，内容不参与网格高度计算，右侧规则决定最终高度。
.group-stacked-preview {
  --stacked-diff-min-height: 0px;
  --stacked-diff-color: #{$text-body};
  --stacked-diff-muted: #{$text-muted};
  --stacked-diff-accent: #{$accent};
  --stacked-diff-deleted: #{$danger};
  --stacked-diff-border: #{$border};
  --stacked-diff-text-font: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  --stacked-diff-text-size: 12px;
  --stacked-diff-text-line-height: 1.6;
  --stacked-diff-text-padding: 12px;
  position: absolute;
  inset: 0;
}

/** 预览状态角标：加载中与结果过期共用样式，只差颜色。 */
.preview-badge {
  margin-left: 6px;
  font-size: 11px;
  color: $text-muted;
  white-space: nowrap;
}

.preview-badge--stale {
  color: $warning;
}

.preview-output {
  margin: 0;
  padding: 12px;
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.6;
  color: $text-muted;
  white-space: pre-wrap;
  word-break: break-all;
}

.preview-error {
  color: $danger;
  font-size: 12px;
  overflow-wrap: anywhere;
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
  flex-wrap: wrap;
  overflow-wrap: anywhere;
}

.warning-path {
  font-family: monospace;
  color: $accent;
  flex-shrink: 0;
  max-width: 100%;
}

.group-rules {
  min-width: 0;
}

/* 像浏览器缩小显示比例一样整体缩放规则区内容（字体、间距、控件尺寸一起缩小）。
   放在内部包裹层而非 .group-rules 本身：后者是 grid 子项，直接在它上面 zoom
   会撑破 grid 轨道导致溢出，内部包裹层缩放不影响轨道分配。
   数值可通过 --group-rules-zoom 覆盖。 */
.group-rules-zoom {
  min-width: 0;
  zoom: var(--group-rules-zoom, 0.85);
}

.rules-section-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: $space-xs;
  gap: $space-sm;
  flex-wrap: wrap;
}

.rules-section-title {
  white-space: nowrap;
  font-size: 13px;
  font-weight: 600;
  color: $text-primary;
}

.rules-section-actions {
  display: flex;
  gap: $space-xs;
  flex-wrap: wrap;
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

@container rule-group (max-width: 700px) {
  .group-workspace {
    grid-template-columns: minmax(0, 1fr);
  }

  .group-preview {
    height: 380px;
  }
}

@container rule-group (max-width: 420px) {
  .group-field {
    flex: 1 1 100%;
  }

  .group-field-label {
    width: 60px;
  }

  .group-name-input,
  .group-protocol-select,
  .group-template-select {
    flex: 1;
    width: 0;
  }
}
</style>
