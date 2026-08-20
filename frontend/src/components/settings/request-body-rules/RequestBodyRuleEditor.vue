<script setup lang="ts">
/**
 * RequestBodyRuleEditor — 请求体规则配置二级模态框。
 *
 * 本组件是**规则组列表容器**：新增组、组间排序、整体应用，以及可视化 / JSON 双视图切换。
 * 单组的预览双栏与规则列表都下沉到 `RuleGroupCard.vue`。
 *
 * 草稿语义：打开时深拷贝父级规则集，"应用"才提交，"取消"丢弃。
 *
 * <h2>为何是多组而不是单一规则列表</h2>
 * 适配一个上游差异往往要好几条规则协同（改 messages、删字段、补字段），它们同进同出；
 * 而不同线路协议（OpenAI / Anthropic）需要完全不同的规则与调试样本。
 * 组是「一批规则 + 它们适用的线路 + 该批规则的调试样本」这个整体的自然单位。
 */
import { computed, ref, watch } from 'vue'
import { NButton, NInput, NModal, useMessage } from 'naive-ui'
import type { RuleGroup, RuleSetV2 } from '@/features/request-body-rules/types'
import { createEmptyRuleSetV2 } from '@/features/request-body-rules/types'
import type { RequestBodyEditorState } from '@/features/request-body-rules/editorState'
import { countRules } from '@/features/request-body-rules/editorState'
import {
  appendGroup,
  moveGroup as moveGroupIn,
  removeGroup as removeGroupIn,
  replaceGroup,
} from '@/features/request-body-rules/groupOperations'
import { formatRuleSetJson, parseRuleSetJson } from '@/features/request-body-rules/ruleSetJson'
import { migrateRuleSet } from '@/features/request-body-rules/migration'
import RuleGroupCard from './RuleGroupCard.vue'
import RequestBodyRuleHelp from './RequestBodyRuleHelp.vue'

const props = defineProps<{
  show: boolean
  /** 父级传入的完整编辑器状态（草稿来源） */
  modelValue: RequestBodyEditorState
}>()

const emit = defineEmits<{
  (e: 'update:show', val: boolean): void
  (e: 'apply', value: RequestBodyEditorState): void
}>()

const message = useMessage()

// ==================== 草稿状态 ====================

const draftRuleSet = ref<RuleSetV2>(createEmptyRuleSetV2())
const showRuleHelp = ref(false)
const viewMode = ref<'visual' | 'json'>('visual')
const rulesJsonText = ref('')
const rulesJsonError = ref('')

const groups = computed(() => draftRuleSet.value.groups)
const totalRules = computed(() => countRules(draftRuleSet.value))

/**
 * 打开时用迁移函数归一化来源。
 *
 * 走 `migrateRuleSet` 而非直接当 V2 用：规则集是可手工编辑的文本，父级传进来的可能是
 * 旧导出、也可能缺字段，归一化保证卡片渲染时每个组的字段都齐备。
 */
watch(
  () => props.show,
  (visible) => {
    if (!visible) return
    const source = props.modelValue.rules ?? createEmptyRuleSetV2()
    draftRuleSet.value = migrateRuleSet(JSON.parse(JSON.stringify(source)))
    viewMode.value = 'visual'
    rulesJsonText.value = formatRuleSetJson(draftRuleSet.value)
    rulesJsonError.value = ''
  },
)

// ==================== 规则组操作 ====================

/**
 * 写回单个组。
 *
 * 卡片是受控组件：它不持有组数据，改动一律经此回写，于是组数组始终是唯一真源。
 * 具体的数组操作在 `features/request-body-rules/groupOperations`，那里有单测覆盖
 * 「order 必须与下标同步」这条不显然的约束。
 */
function updateGroup(index: number, group: RuleGroup) {
  draftRuleSet.value = replaceGroup(draftRuleSet.value, index, group)
}

function addGroup() {
  draftRuleSet.value = appendGroup(draftRuleSet.value)
}

function moveGroup(index: number, direction: -1 | 1) {
  draftRuleSet.value = moveGroupIn(draftRuleSet.value, index, direction)
}

function removeGroup(index: number) {
  draftRuleSet.value = removeGroupIn(draftRuleSet.value, index)
}

// ==================== JSON 视图 ====================

function updateRulesJson(value: string) {
  rulesJsonText.value = value
  const parsed = parseRuleSetJson(value)
  if (!parsed.valid) {
    rulesJsonError.value = parsed.error
    return
  }
  draftRuleSet.value = parsed.rules
  rulesJsonError.value = ''
}

function toggleView() {
  if (viewMode.value === 'visual') {
    rulesJsonText.value = formatRuleSetJson(draftRuleSet.value)
    rulesJsonError.value = ''
    viewMode.value = 'json'
    return
  }
  const parsed = parseRuleSetJson(rulesJsonText.value)
  if (!parsed.valid) {
    rulesJsonError.value = parsed.error
    message.error('请先修正规则 JSON')
    return
  }
  draftRuleSet.value = parsed.rules
  rulesJsonError.value = ''
  viewMode.value = 'visual'
}

// ==================== 应用/取消 ====================

function handleApply() {
  if (rulesJsonError.value) {
    message.error('请先修正规则 JSON')
    return
  }
  emit('apply', { rules: JSON.parse(JSON.stringify(draftRuleSet.value)) })
  emit('update:show', false)
  message.success(
    `已应用 ${groups.value.length} 个规则组、${totalRules.value} 条规则，请保存供应商配置以完成落库`,
  )
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
    title="请求体映射规则配置"
    :style="{ width: '90vw', maxWidth: '1200px', maxHeight: '90vh', display: 'flex', 'flex-direction': 'column' }"
    content-style="overflow: auto; flex: 1; min-height: 0"
    closable
    :mask-closable="true"
  >
    <div class="editor-notice">
      每个规则组自带一份预览样本，仅用于编辑、验证该组规则的效果；实际请求体始终由下游客户端提交。
      运行时按组的顺序，依次把<strong>适用当前线路</strong>的组作用于同一份请求体 —— 组间预览不串联。
    </div>

    <div class="editor-toolbar">
      <div class="editor-toolbar-heading">
        <span class="editor-toolbar-title">规则组（{{ groups.length }}）</span>
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
      <div class="editor-toolbar-actions">
        <NButton size="tiny" class="rules-action-button" @click="addGroup">新增规则组</NButton>
        <NButton size="tiny" class="rules-action-button" @click="toggleView">
          {{ viewMode === 'visual' ? '切换 JSON 视图' : '切换可视化视图' }}
        </NButton>
      </div>
    </div>

    <template v-if="viewMode === 'visual'">
      <div v-if="groups.length === 0" class="editor-empty">
        暂无规则组。新增一个规则组即可开始配置；不加任何组时请求体原样转发。
      </div>
      <RuleGroupCard
        v-for="(group, index) in groups"
        :key="group.id"
        :group="group"
        :index="index"
        :total="groups.length"
        @update:group="updateGroup(index, $event)"
        @move="moveGroup(index, $event)"
        @remove="removeGroup(index)"
      />
    </template>

    <div v-else class="rules-json-editor">
      <NInput
        :value="rulesJsonText"
        @update:value="updateRulesJson"
        type="textarea"
        :autosize="{ minRows: 16, maxRows: 34 }"
        :resizable="true"
        class="rules-json-input"
        placeholder="在此编辑或粘贴完整的规则集 JSON"
      />
      <div v-if="rulesJsonError" class="rules-json-error">{{ rulesJsonError }}</div>
      <div v-else class="rules-json-valid">JSON 已同步到可视化视图</div>
    </div>

    <template #footer>
      <div class="editor-footer">
        <span class="editor-footer-count">
          共 {{ groups.length }} 个规则组、{{ totalRules }} 条规则
        </span>
        <div class="editor-footer-actions">
          <NButton @click="handleCancel">取消</NButton>
          <NButton type="primary" :disabled="Boolean(rulesJsonError)" @click="handleApply">应用</NButton>
        </div>
      </div>
    </template>
  </NModal>

  <RequestBodyRuleHelp v-model:show="showRuleHelp" />
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.editor-notice {
  padding: $space-xs $space-sm;
  background: rgba($accent, 0.08);
  border: 1px solid rgba($accent, 0.2);
  border-radius: $radius;
  font-size: 12px;
  line-height: 1.6;
  color: $text-muted;
  margin-bottom: $space-sm;
}

.editor-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: $space-sm;
}

.editor-toolbar-heading {
  display: flex;
  align-items: center;
  gap: 6px;
}

.editor-toolbar-title {
  font-size: 14px;
  font-weight: 600;
  color: $text-primary;
}

.editor-toolbar-actions {
  display: flex;
  gap: $space-xs;
}

.editor-empty {
  padding: $space-md;
  border: 1px dashed $border;
  border-radius: $radius;
  text-align: center;
  font-size: 12px;
  color: $text-muted;
  margin-bottom: $space-sm;
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

.rules-json-editor {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.rules-json-input {
  min-height: 300px;
  border: 1px solid $border;
  border-radius: $radius;
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
}

.rules-json-error,
.rules-json-valid {
  font-size: 12px;
}

.rules-json-error {
  color: $danger;
}

.rules-json-valid {
  color: $text-muted;
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
</style>
