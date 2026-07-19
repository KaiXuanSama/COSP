<script setup lang="ts">
/**
 * RequestBodyRuleHelp — 请求体规则编辑器的新手帮助。
 *
 * 每个选项卡都使用正式转换引擎实时执行示例，左侧 JSON 可编辑，
 * 右侧通过与主编辑器相同的差异树展示结果，避免文档示例与实际行为脱节。
 */
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { NButton, NInput, NModal, NScrollbar, NTabPane, NTabs } from 'naive-ui'
import { transform } from '@/features/request-body-rules/engine'
import { buildDiffTree } from '@/features/request-body-rules/diff'
import { RULE_HELP_EXAMPLES } from '@/features/request-body-rules/helpExamples'
import { formatRuleSetJson } from '@/features/request-body-rules/ruleSetJson'
import type { RuleSet, TransformWarning } from '@/features/request-body-rules/types'
import DiffJsonNode from './DiffJsonNode.vue'
import RequestBodyRuleList from './RequestBodyRuleList.vue'

const props = defineProps<{
  show: boolean
}>()

const emit = defineEmits<{
  (e: 'update:show', value: boolean): void
}>()

const activeTab = ref(RULE_HELP_EXAMPLES[0].key)
const exampleInputs = ref<Record<string, string>>({})
const exampleRules = ref<Record<string, RuleSet>>({})
const exampleRuleViewModes = ref<Record<string, 'visual' | 'json'>>({})
const helpContentRoot = ref<HTMLElement | null>(null)

let leftTextarea: HTMLTextAreaElement | null = null
let rightScrollEl: HTMLElement | null = null
let syncingLeftToRight = false
let syncingRightToLeft = false

function cloneRuleSet(rules: RuleSet): RuleSet {
  return JSON.parse(JSON.stringify(rules))
}

function resetExamples() {
  exampleInputs.value = Object.fromEntries(
    RULE_HELP_EXAMPLES.map((example) => [example.key, JSON.stringify(example.input, null, 2)]),
  )
  exampleRules.value = Object.fromEntries(
    RULE_HELP_EXAMPLES.map((example) => [example.key, cloneRuleSet(example.rules)]),
  )
  exampleRuleViewModes.value = Object.fromEntries(
    RULE_HELP_EXAMPLES.map((example) => [example.key, 'visual' as const]),
  )
}

watch(
  () => props.show,
  (visible) => {
    if (visible && Object.keys(exampleInputs.value).length === 0) {
      resetExamples()
    }
  },
  { immediate: true },
)

const activeExample = computed(
  () => RULE_HELP_EXAMPLES.find((example) => example.key === activeTab.value) ?? RULE_HELP_EXAMPLES[0],
)

interface HelpLiveResult {
  input: Record<string, unknown> | null
  tree: ReturnType<typeof buildDiffTree> | null
  error: string
  warnings: TransformWarning[]
}

const exampleResults = computed<Record<string, HelpLiveResult>>(() => {
  return Object.fromEntries(RULE_HELP_EXAMPLES.map((example) => {
    const source = exampleInputs.value[example.key] ?? ''
    try {
      const input = JSON.parse(source)
      if (input == null || typeof input !== 'object' || Array.isArray(input)) {
        return [example.key, {
          input: null,
          tree: null,
          error: '示例请求体必须是 JSON 对象',
          warnings: [],
        }]
      }
      const rules = exampleRules.value[example.key] ?? example.rules
      const result = transform(input, rules)
      return [example.key, {
        input: input as Record<string, unknown>,
        tree: buildDiffTree(input, result.output),
        error: '',
        warnings: result.warnings,
      }]
    } catch (error: any) {
      return [example.key, {
        input: null,
        tree: null,
        error: `JSON 语法错误：${error.message}`,
        warnings: [],
      }]
    }
  }))
})

function restoreCurrentInput() {
  exampleInputs.value[activeExample.value.key] = JSON.stringify(activeExample.value.input, null, 2)
}

function toggleRuleView(exampleKey: string) {
  exampleRuleViewModes.value[exampleKey] = exampleRuleViewModes.value[exampleKey] === 'json'
    ? 'visual'
    : 'json'
}

function scrollRatio(element: HTMLElement): number {
  const max = element.scrollHeight - element.clientHeight
  return max > 0 ? element.scrollTop / max : 0
}

function onLeftScroll() {
  if (syncingRightToLeft || !leftTextarea || !rightScrollEl) return
  syncingLeftToRight = true
  const rightMax = rightScrollEl.scrollHeight - rightScrollEl.clientHeight
  rightScrollEl.scrollTop = scrollRatio(leftTextarea) * rightMax
  requestAnimationFrame(() => {
    syncingLeftToRight = false
  })
}

function onRightScroll() {
  if (syncingLeftToRight || !leftTextarea || !rightScrollEl) return
  syncingRightToLeft = true
  const leftMax = leftTextarea.scrollHeight - leftTextarea.clientHeight
  leftTextarea.scrollTop = scrollRatio(rightScrollEl) * leftMax
  requestAnimationFrame(() => {
    syncingRightToLeft = false
  })
}

function unbindPreviewScroll() {
  leftTextarea?.removeEventListener('scroll', onLeftScroll)
  rightScrollEl?.removeEventListener('scroll', onRightScroll)
  leftTextarea = null
  rightScrollEl = null
}

async function bindPreviewScroll() {
  unbindPreviewScroll()
  await nextTick()
  const panel = helpContentRoot.value?.querySelector<HTMLElement>(
    `[data-help-example="${activeTab.value}"]`,
  )
  leftTextarea = panel?.querySelector<HTMLTextAreaElement>('.demo-input textarea') ?? null
  rightScrollEl = panel?.querySelector<HTMLElement>('.demo-output .n-scrollbar-container') ?? null
  leftTextarea?.addEventListener('scroll', onLeftScroll)
  rightScrollEl?.addEventListener('scroll', onRightScroll)
}

watch([() => props.show, activeTab], ([visible]) => {
  if (visible) void bindPreviewScroll()
  else unbindPreviewScroll()
}, { flush: 'post' })

onBeforeUnmount(unbindPreviewScroll)
</script>

<template>
  <NModal
    :show="show"
    @update:show="emit('update:show', $event)"
    preset="card"
    title="请求体调整规则 · 快速上手"
    :style="{ width: '92vw', maxWidth: '1180px', maxHeight: '90vh', display: 'flex', 'flex-direction': 'column' }"
    content-style="overflow-x: hidden; overflow-y: auto; flex: 1; min-height: 0; padding-top: 8px"
    closable
  >
    <div ref="helpContentRoot" class="help-content-root">
    <div class="help-intro">
      <div>
        <div class="help-intro-title">先看效果，再照着配置</div>
        <div class="help-intro-text">
          选择一种规则，编辑左侧示例即可实时观察结果。这里运行的就是编辑器正式转换引擎，不是静态演示图。
        </div>
      </div>
      <div class="diff-legend" aria-label="差异颜色说明">
        <span><i class="legend-dot legend-dot--same" />未变化</span>
        <span><i class="legend-dot legend-dot--changed" />修改或新增</span>
        <span><i class="legend-dot legend-dot--deleted" />已删除</span>
      </div>
    </div>

    <NTabs v-model:value="activeTab" type="segment" animated class="help-tabs">
      <NTabPane
        v-for="example in RULE_HELP_EXAMPLES"
        :key="example.key"
        :name="example.key"
        :tab="example.title"
      >
        <div class="help-tab-content" :data-help-example="example.key">
            <section class="explanation-card">
              <div class="explanation-main">
                <span class="example-badge">规则效果</span>
                <h3>{{ example.title }}</h3>
                <p>{{ example.summary }}</p>
                <div class="rule-formula">{{ example.ruleSummary }}</div>
              </div>
              <ol class="steps-list">
                <li v-for="step in example.steps" :key="step">{{ step }}</li>
              </ol>
            </section>

            <section class="live-demo">
              <div class="demo-panel">
                <div class="demo-header">
                  <div>
                    <span class="demo-title">示例输入</span>
                    <span class="demo-subtitle">可直接修改</span>
                  </div>
                  <NButton text size="tiny" @click="restoreCurrentInput">恢复输入</NButton>
                </div>
                <NInput
                  v-model:value="exampleInputs[example.key]"
                  type="textarea"
                  :autosize="{ minRows: 10, maxRows: 18 }"
                  class="demo-input"
                  placeholder="请输入 JSON 对象"
                />
                <div v-if="exampleResults[example.key]?.error" class="demo-error">
                  {{ exampleResults[example.key].error }}
                </div>
              </div>

              <div class="demo-arrow" aria-hidden="true">→</div>

              <div class="demo-panel">
                <div class="demo-header">
                  <div>
                    <span class="demo-title">实时转换结果</span>
                    <span class="demo-subtitle">颜色标出规则影响</span>
                  </div>
                </div>
                <div class="demo-output">
                  <NScrollbar>
                    <div v-if="exampleResults[example.key]?.tree" class="demo-tree">
                      <DiffJsonNode :node="exampleResults[example.key].tree!" :is-last="true" />
                    </div>
                    <div v-else class="demo-placeholder">修正左侧 JSON 后显示结果</div>
                  </NScrollbar>
                </div>
              </div>
            </section>

            <section class="example-rule-editor">
              <div class="example-rule-header">
                <div>
                  <span class="demo-title">对应规则设置</span>
                  <span class="demo-subtitle">与主编辑器完全一致，作为只读配置供对照学习</span>
                </div>
                <div class="example-rule-actions">
                  <span class="example-rule-count">
                    {{ exampleRules[example.key]?.rules.length ?? 0 }} 条顶层规则
                  </span>
                  <NButton
                    size="tiny"
                    class="example-rule-view-button"
                    @click="toggleRuleView(example.key)"
                  >
                    {{ exampleRuleViewModes[example.key] === 'json' ? '切换可视化视图' : '切换 JSON 视图' }}
                  </NButton>
                </div>
              </div>
              <RequestBodyRuleList
                v-if="exampleRuleViewModes[example.key] !== 'json'"
                :rules="exampleRules[example.key]?.rules ?? []"
                :scope-object="exampleResults[example.key]?.input ?? null"
                :depth="0"
                :readonly="true"
              />
              <NInput
                v-else
                :value="formatRuleSetJson(exampleRules[example.key] ?? example.rules)"
                type="textarea"
                :autosize="{ minRows: 12, maxRows: 24 }"
                :resizable="true"
                readonly
                class="example-rule-json"
                aria-label="只读规则 JSON，可框选复制"
              />
            </section>

            <div v-if="exampleResults[example.key]?.warnings.length" class="example-warnings">
              <strong>执行警告</strong>
              <span
                v-for="warning in exampleResults[example.key].warnings"
                :key="`${warning.ruleId}-${warning.fieldPath}`"
              >
                {{ warning.fieldPath || '根对象' }}：{{ warning.message }}
              </span>
            </div>

            <section class="tips-card">
              <span class="tips-title">记住这些</span>
              <ul>
                <li v-for="tip in example.tips" :key="tip">{{ tip }}</li>
              </ul>
            </section>
        </div>
      </NTabPane>
    </NTabs>
    </div>
  </NModal>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.help-intro {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: $space-md;
  padding: $space-sm $space-md;
  margin-bottom: $space-sm;
  border: 1px solid rgba($accent, 0.2);
  border-radius: $radius;
  background: linear-gradient(100deg, rgba($accent, 0.1), rgba($accent, 0.025));
}

.help-intro-title {
  margin-bottom: 2px;
  color: $text-primary;
  font-size: 14px;
  font-weight: 650;
}

.help-intro-text {
  color: $text-body;
  font-size: 12px;
  line-height: 1.5;
}

.diff-legend {
  display: flex;
  flex-wrap: wrap;
  flex-shrink: 0;
  gap: $space-sm;
  color: $text-muted;
  font-size: 11px;
}

.diff-legend span {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.legend-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: $text-muted;
}

.legend-dot--changed {
  background: $accent;
}

.legend-dot--deleted {
  background: $danger;
}

.help-tabs {
  min-width: 0;
}

.help-content-root {
  min-width: 0;
}

.help-tab-content {
  padding: $space-sm 2px $space-md;
}

.explanation-card {
  display: grid;
  grid-template-columns: minmax(0, 1.25fr) minmax(260px, 0.75fr);
  gap: $space-lg;
  padding: $space-md;
  border: 1px solid $border;
  border-radius: $radius;
  background: $surface;
  box-shadow: $shadow-sm;
}

.example-badge {
  display: inline-block;
  padding: 2px 7px;
  border-radius: 999px;
  background: $accent-light;
  color: $accent;
  font-size: 10px;
  font-weight: 650;
}

.explanation-card h3 {
  margin: 5px 0 2px;
  color: $text-primary;
  font-size: 16px;
}

.explanation-card p {
  margin: 0;
  color: $text-body;
  font-size: 12px;
  line-height: 1.6;
}

.rule-formula {
  margin-top: $space-sm;
  padding: 7px 9px;
  border-left: 3px solid $accent;
  border-radius: 0 6px 6px 0;
  background: $accent-light;
  color: $text-primary;
  font-family: $font-mono, monospace;
  font-size: 11px;
}

.steps-list {
  margin: 0;
  padding-left: 20px;
  color: $text-body;
  font-size: 12px;
  line-height: 1.75;
}

.steps-list li::marker {
  color: $accent;
  font-weight: 650;
}

.live-demo {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 24px minmax(0, 1fr);
  align-items: stretch;
  gap: $space-xs;
  margin-top: $space-sm;
}

.demo-panel {
  min-width: 0;
}

.demo-header {
  display: flex;
  min-height: 28px;
  align-items: center;
  justify-content: space-between;
}

.demo-title {
  color: $text-primary;
  font-size: 12px;
  font-weight: 650;
}

.demo-subtitle {
  margin-left: $space-xs;
  color: $text-muted;
  font-size: 10px;
}

.demo-input {
  height: 240px;
  border-radius: $radius;
  font-family: $font-mono, 'Cascadia Code', monospace;
  font-size: 11px;
}

.demo-input :deep(.n-input-wrapper),
.demo-input :deep(.n-input__textarea),
.demo-input :deep(.n-input__textarea-el) {
  height: 100%;
}

.demo-output {
  height: 240px;
  overflow: hidden;
  border: 1px solid $border;
  border-radius: $radius;
  background: $bg;
}

.demo-tree {
  padding: $space-sm $space-md;
}

.demo-placeholder {
  padding: $space-md;
  color: $text-muted;
  font-size: 12px;
}

.demo-arrow {
  display: flex;
  align-items: center;
  justify-content: center;
  padding-top: 28px;
  color: $accent;
  font-size: 20px;
}

.demo-error {
  margin-top: 4px;
  color: $danger;
  font-size: 11px;
}

.example-rule-editor {
  margin-top: $space-sm;
  padding: $space-sm;
  border: 1px solid $border;
  border-radius: $radius;
  background: rgba($surface, 0.82);
}

.example-rule-header {
  display: flex;
  min-height: 28px;
  align-items: center;
  justify-content: space-between;
  gap: $space-sm;
  margin-bottom: $space-xs;
}

.example-rule-count {
  flex-shrink: 0;
  padding: 2px 7px;
  border-radius: 999px;
  background: $accent-light;
  color: $accent;
  font-size: 10px;
}

.example-rule-actions {
  display: flex;
  align-items: center;
  gap: $space-xs;
}

.example-rule-view-button {
  --n-height: 24px !important;
  --n-padding: 0 9px !important;
  --n-border: 1px solid rgba(194, 122, 62, 0.3) !important;
  --n-border-hover: 1px solid rgba(194, 122, 62, 0.58) !important;
  --n-border-pressed: 1px solid $accent !important;
  --n-border-focus: 1px solid rgba(194, 122, 62, 0.58) !important;
  --n-color: rgba(194, 122, 62, 0.07) !important;
  --n-color-hover: rgba(194, 122, 62, 0.13) !important;
  --n-color-pressed: rgba(194, 122, 62, 0.18) !important;
  --n-color-focus: rgba(194, 122, 62, 0.13) !important;
  --n-text-color: $text-secondary !important;
  --n-text-color-hover: $accent !important;
  --n-text-color-pressed: $accent !important;
  --n-text-color-focus: $accent !important;
  --n-border-radius: 6px !important;
  font-size: 11px;
}

.example-rule-json {
  min-height: 240px;
  border: 1px solid $border;
  border-radius: $radius;
  font-family: $font-mono, 'Cascadia Code', monospace;
  font-size: 11px;
  user-select: text;
}

.example-rule-json :deep(textarea) {
  cursor: text;
  user-select: text;
}

.example-warnings {
  display: flex;
  flex-direction: column;
  gap: 2px;
  margin-top: $space-sm;
  padding: $space-xs $space-sm;
  border: 1px solid rgba($warning, 0.25);
  border-radius: $radius;
  background: rgba($warning, 0.08);
  color: $text-body;
  font-size: 11px;
}

.example-warnings strong {
  color: $warning;
}

.tips-card {
  display: flex;
  gap: $space-sm;
  margin-top: $space-sm;
  padding: $space-sm $space-md;
  border: 1px solid $border-light;
  border-radius: $radius;
  background: rgba($blue, 0.045);
}

.tips-title {
  flex-shrink: 0;
  color: $blue;
  font-size: 11px;
  font-weight: 650;
}

.tips-card ul {
  display: flex;
  flex-wrap: wrap;
  gap: 3px $space-lg;
  margin: 0;
  padding-left: 16px;
  color: $text-body;
  font-size: 11px;
}

@media (max-width: 800px) {
  .help-intro,
  .tips-card {
    align-items: flex-start;
    flex-direction: column;
  }

  .explanation-card {
    grid-template-columns: 1fr;
    gap: $space-sm;
  }

  .live-demo {
    grid-template-columns: 1fr;
  }

  .demo-arrow {
    padding: 0;
    transform: rotate(90deg);
  }

  .demo-input,
  .demo-output {
    height: 210px;
  }

  .example-rule-header {
    align-items: flex-start;
    flex-direction: column;
  }
}
</style>
