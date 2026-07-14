<script setup lang="ts">
/**
 * RequestBodyRuleItem — 单条字段规则的编辑卡片。
 *
 * 递归渲染：当操作为 edit_object 时，内部嵌套 RequestBodyRuleList。
 * 字段下拉选项从当前作用域的原始 JSON 动态生成。
 */
import { computed } from 'vue'
import { NSelect, NSwitch, NInput, NButton, NIcon } from 'naive-ui'
import type { FieldRule, RuleCondition, ConditionOperator } from '@/features/request-body-rules/types'
import { createEmptyRule, createEmptyCondition } from '@/features/request-body-rules/types'
import RequestBodyRuleList from './RequestBodyRuleList.vue'

const props = defineProps<{
  rule: FieldRule
  /** 当前作用域的原始 JSON 对象，用于生成字段下拉选项 */
  scopeObject: Record<string, unknown> | null
  /** 层级深度，用于缩进和标签 */
  depth: number
}>()

const emit = defineEmits<{
  (e: 'update:rule', rule: FieldRule): void
  (e: 'move-up'): void
  (e: 'move-down'): void
  (e: 'remove'): void
}>()

// ==================== 字段下拉选项 ====================

interface FieldOption {
  label: string
  value: string
  [key: string]: unknown
}

const fieldOptions = computed<FieldOption[]>(() => {
  if (!props.scopeObject) return []
  const options: FieldOption[] = []
  for (const [key, val] of Object.entries(props.scopeObject)) {
    if (Array.isArray(val)) {
      options.push({ label: `${key}[*]`, value: key })
    } else {
      options.push({ label: key, value: key })
    }
  }
  return options
})

// ==================== 操作类型选项 ====================

const operationTypeOptions = [
  { label: '调整对象内容', value: 'edit_object' },
  { label: '设置字段值', value: 'set_value' },
  { label: '删除字段', value: 'delete' },
]

const conditionOperatorOptions = [
  { label: '存在', value: 'exists' },
  { label: '等于', value: 'equals' },
]

// ==================== 当前操作类型 ====================

const currentOperationType = computed({
  get: () => {
    const op = props.rule.operations[0]
    return op ? op.type : null
  },
  set: (val: string | null) => {
    if (!val) {
      emit('update:rule', { ...props.rule, operations: [] })
      return
    }
    let newOp
    if (val === 'edit_object') {
      newOp = { type: 'edit_object' as const, rules: [] }
    } else if (val === 'set_value') {
      newOp = { type: 'set_value' as const, value: '' }
    } else {
      newOp = { type: 'delete' as const }
    }
    emit('update:rule', { ...props.rule, operations: [newOp] })
  },
})

// ==================== set_value 值编辑 ====================

const setValueText = computed({
  get: () => {
    const op = props.rule.operations[0]
    if (!op || op.type !== 'set_value') return ''
    return typeof op.value === 'string' ? op.value : JSON.stringify(op.value, null, 2)
  },
  set: (val: string) => {
    const parsed = tryParseJson(val)
    const newOp = { type: 'set_value' as const, value: parsed.value }
    emit('update:rule', { ...props.rule, operations: [newOp] })
  },
})

const setValueError = computed(() => {
  const op = props.rule.operations[0]
  if (!op || op.type !== 'set_value') return ''
  const text = typeof op.value === 'string' ? op.value : JSON.stringify(op.value)
  return tryParseJson(text).error
})

// ==================== edit_object 嵌套规则 ====================

const nestedRules = computed(() => {
  const op = props.rule.operations[0]
  if (!op || op.type !== 'edit_object') return []
  return op.rules || []
})

const nestedScopeObject = computed<Record<string, unknown> | null>(() => {
  if (!props.scopeObject || !props.rule.field) return null
  const val = props.scopeObject[props.rule.field]
  if (props.rule.array) {
    // 数组模式：取第一个对象元素作为字段来源
    if (Array.isArray(val)) {
      for (const item of val) {
        if (item && typeof item === 'object' && !Array.isArray(item)) {
          return item as Record<string, unknown>
        }
      }
    }
    return null
  }
  if (val && typeof val === 'object' && !Array.isArray(val)) {
    return val as Record<string, unknown>
  }
  return null
})

function updateNestedRules(rules: FieldRule[]) {
  const newOp = { type: 'edit_object' as const, rules }
  emit('update:rule', { ...props.rule, operations: [newOp] })
}

// ==================== 条件编辑 ====================

function updateConditional(val: boolean) {
  emit('update:rule', { ...props.rule, conditional: val })
}

function updateCondition(index: number, patch: Partial<RuleCondition>) {
  const newConditions = props.rule.conditions.map((c, i) =>
    i === index ? { ...c, ...patch } : c
  )
  emit('update:rule', { ...props.rule, conditions: newConditions })
}

function addCondition() {
  emit('update:rule', {
    ...props.rule,
    conditions: [...props.rule.conditions, createEmptyCondition()],
  })
}

function removeCondition(index: number) {
  emit('update:rule', {
    ...props.rule,
    conditions: props.rule.conditions.filter((_, i) => i !== index),
  })
}

/** 条件路径下拉选项：从当前作用域生成 */
const conditionPathOptions = computed<FieldOption[]>(() => {
  if (!props.scopeObject) return []
  return generatePathOptions(props.scopeObject, '', 0)
})

function generatePathOptions(obj: Record<string, unknown>, prefix: string, depth: number): FieldOption[] {
  if (depth > 2) return [] // 限制路径深度
  const options: FieldOption[] = []
  for (const [key, val] of Object.entries(obj)) {
    const path = prefix ? `${prefix}/${key}` : `./${key}`
    if (Array.isArray(val)) {
      options.push({ label: `${path}[*]`, value: `${path}[*]` })
      // 继续展开数组元素的子字段
      for (const item of val) {
        if (item && typeof item === 'object' && !Array.isArray(item)) {
          options.push(...generatePathOptions(item as Record<string, unknown>, `${path}[*]`, depth + 1))
        }
      }
    } else if (val && typeof val === 'object') {
      options.push({ label: path, value: path })
      options.push(...generatePathOptions(val as Record<string, unknown>, path, depth + 1))
    } else {
      options.push({ label: path, value: path })
    }
  }
  return options
}

/** 条件值输入 */
function conditionValueText(condition: RuleCondition): string {
  if (condition.value == null) return ''
  return typeof condition.value === 'string' ? condition.value : JSON.stringify(condition.value)
}

function updateConditionValue(index: number, text: string) {
  const parsed = tryParseJson(text)
  updateCondition(index, { value: parsed.value })
}

function conditionValueError(condition: RuleCondition): string {
  if (condition.operator !== 'equals') return ''
  const text = conditionValueText(condition)
  return tryParseJson(text).error
}

// ==================== 工具函数 ====================

function tryParseJson(text: string): { value: unknown; error: string } {
  const trimmed = text.trim()
  if (!trimmed) return { value: '', error: '' }
  try {
    return { value: JSON.parse(trimmed), error: '' }
  } catch {
    // 不是合法 JSON，作为字符串处理
    return { value: trimmed, error: '' }
  }
}

function updateField(val: string) {
  // 从下拉选项中判断是否为数组
  const option = fieldOptions.value.find(o => o.value === val)
  const isArray = option ? option.label.endsWith('[*]') : false
  emit('update:rule', { ...props.rule, field: val, array: isArray })
}
</script>

<template>
  <div class="rule-item" :class="{ 'rule-item--nested': depth > 0 }">
    <!-- 规则头部：顺序 + 字段 + 操作 + 条件开关 + 操作按钮 -->
    <div class="rule-header">
      <span class="rule-order">{{ rule.order + 1 }}</span>

      <NSelect
        :value="rule.field"
        @update:value="updateField"
        :options="fieldOptions"
        placeholder="选择字段"
        filterable
        tag
        size="small"
        class="rule-field-select"
      />

      <NSelect
        :value="currentOperationType"
        @update:value="currentOperationType = $event"
        :options="operationTypeOptions"
        placeholder="选择操作"
        size="small"
        class="rule-op-select"
      />

      <label class="rule-conditional-toggle">
        <NSwitch :value="rule.conditional" @update:value="updateConditional" size="small" />
        <span class="rule-conditional-label">条件执行</span>
      </label>

      <div class="rule-actions">
        <NButton text size="tiny" :disabled="rule.order === 0" @click="emit('move-up')" title="上移">
          ↑
        </NButton>
        <NButton text size="tiny" @click="emit('move-down')" title="下移">
          ↓
        </NButton>
        <NButton text size="tiny" type="error" @click="emit('remove')" title="删除">
          ✕
        </NButton>
      </div>
    </div>

    <!-- set_value 值输入 -->
    <div v-if="currentOperationType === 'set_value'" class="rule-value-section">
      <label class="rule-value-label">字段值（JSON 类型安全）</label>
      <NInput
        :value="setValueText"
        @update:value="setValueText = $event"
        type="textarea"
        :autosize="{ minRows: 1, maxRows: 6 }"
        placeholder='如 "user" 或 0.7 或 true 或 null'
        size="small"
      />
      <span v-if="setValueError" class="rule-error">{{ setValueError }}</span>
    </div>

    <!-- 条件列表 -->
    <div v-if="rule.conditional" class="rule-conditions">
      <div class="rule-conditions-header">
        <span class="rule-conditions-title">条件（全部满足）</span>
        <NButton text size="tiny" @click="addCondition">+ 添加条件</NButton>
      </div>
      <div v-for="(cond, idx) in rule.conditions" :key="idx" class="rule-condition-row">
        <NSelect
          :value="cond.path"
          @update:value="updateCondition(idx, { path: $event })"
          :options="conditionPathOptions"
          placeholder="相对路径"
          filterable
          tag
          size="small"
          class="cond-path"
        />
        <NSelect
          :value="cond.operator"
          @update:value="updateCondition(idx, { operator: $event as ConditionOperator })"
          :options="conditionOperatorOptions"
          size="small"
          class="cond-op"
        />
        <NInput
          v-if="cond.operator === 'equals'"
          :value="conditionValueText(cond)"
          @update:value="updateConditionValue(idx, $event)"
          placeholder='比较值，如 "tool"'
          size="small"
          class="cond-value"
        />
        <span v-else class="cond-value-placeholder">—</span>
        <NButton text size="tiny" type="error" @click="removeCondition(idx)" title="删除条件">
          ✕
        </NButton>
      </div>
      <div v-if="rule.conditions.length === 0" class="rule-conditions-empty">
        未添加条件（将始终执行）
      </div>
    </div>

    <!-- edit_object 嵌套规则 -->
    <div v-if="currentOperationType === 'edit_object'" class="rule-nested">
      <div v-if="!nestedScopeObject" class="rule-nested-empty">
        当前字段不是对象或数组，无法嵌套规则
      </div>
      <RequestBodyRuleList
        v-else
        :rules="nestedRules"
        :scope-object="nestedScopeObject"
        :depth="depth + 1"
        @update:rules="updateNestedRules"
      />
    </div>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.rule-item {
  border: 1px solid $border;
  border-radius: $radius;
  padding: $space-sm $space-sm $space-sm $space-xs;
  background: $bg;
  margin-bottom: $space-xs;

  &--nested {
    margin-left: $space-sm;
    border-left: 3px solid $accent;
  }
}

.rule-header {
  display: flex;
  align-items: center;
  gap: $space-xs;
  flex-wrap: wrap;
}

.rule-order {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  height: 22px;
  border-radius: 50%;
  background: $accent;
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  flex-shrink: 0;
}

.rule-field-select {
  width: 160px;
}

.rule-op-select {
  width: 140px;
}

.rule-conditional-toggle {
  display: flex;
  align-items: center;
  gap: 4px;
  cursor: pointer;
}

.rule-conditional-label {
  font-size: 12px;
  color: $text-muted;
  white-space: nowrap;
}

.rule-actions {
  display: flex;
  gap: 2px;
  margin-left: auto;
}

.rule-value-section {
  margin-top: $space-xs;
  padding-left: 30px;
}

.rule-value-label {
  display: block;
  font-size: 12px;
  color: $text-muted;
  margin-bottom: 4px;
}

.rule-error {
  display: block;
  color: $danger;
  font-size: 11px;
  margin-top: 2px;
}

.rule-conditions {
  margin-top: $space-xs;
  padding-left: 30px;
}

.rule-conditions-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 4px;
}

.rule-conditions-title {
  font-size: 12px;
  color: $text-muted;
}

.rule-condition-row {
  display: flex;
  align-items: center;
  gap: $space-xs;
  margin-bottom: 4px;
}

.cond-path {
  width: 200px;
}

.cond-op {
  width: 100px;
}

.cond-value {
  flex: 1;
  min-width: 100px;
}

.cond-value-placeholder {
  flex: 1;
  text-align: center;
  color: $text-muted;
  font-size: 12px;
}

.rule-conditions-empty {
  font-size: 12px;
  color: $text-muted;
  font-style: italic;
}

.rule-nested {
  margin-top: $space-xs;
  padding-left: 30px;
}

.rule-nested-empty {
  font-size: 12px;
  color: $text-muted;
  font-style: italic;
  padding: $space-xs;
}
</style>
