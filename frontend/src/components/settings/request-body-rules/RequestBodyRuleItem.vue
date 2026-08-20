<script setup lang="ts">
/**
 * RequestBodyRuleItem — 单条字段规则的编辑卡片。
 *
 * 递归渲染：当操作为 edit_object 时，内部嵌套 RequestBodyRuleList。
 * 字段下拉选项从当前作用域的原始 JSON 动态生成。
 */
import { computed } from 'vue'
import { NSelect, NSwitch, NButton, NIcon } from 'naive-ui'
import type { FieldRule, RuleCondition, ConditionOperator } from '@/features/request-body-rules/types'
import { createEmptyRule, createEmptyCondition } from '@/features/request-body-rules/types'
import JsonValueInput from './JsonValueInput.vue'
import RequestBodyRuleList from './RequestBodyRuleList.vue'

const props = defineProps<{
  rule: FieldRule
  /** 当前作用域的原始 JSON 对象，用于生成字段下拉选项 */
  scopeObject: Record<string, unknown> | null
  /** 层级深度，用于缩进和标签 */
  depth: number
  /** 是否仅用于展示；为 true 时禁用全部编辑控件 */
  readonly?: boolean
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

/**
 * 当前 set_value 操作的值。
 *
 * 类型档位与输入草稿都由 `JsonValueInput` 自己管，这里只负责取值与写回 ——
 * 那些编辑态是纯粹的本地状态，放进规则只会让非法中间态污染预览与保存。
 */
const setValue = computed(() => {
  const op = props.rule.operations[0]
  return op && op.type === 'set_value' ? op.value : ''
})

function updateSetValue(value: unknown) {
  emit('update:rule', { ...props.rule, operations: [{ type: 'set_value' as const, value }] })
}

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

function generatePathOptions(
  obj: Record<string, unknown>,
  prefix: string,
  depth: number,
  seenPaths = new Set<string>(),
): FieldOption[] {
  if (depth > 2) return [] // 限制路径深度
  const options: FieldOption[] = []
  const addOption = (path: string) => {
    if (seenPaths.has(path)) return
    seenPaths.add(path)
    options.push({ label: path, value: path })
  }
  for (const [key, val] of Object.entries(obj)) {
    const path = prefix ? `${prefix}/${key}` : `./${key}`
    if (Array.isArray(val)) {
      const arrayPath = `${path}[*]`
      addOption(arrayPath)
      // 继续展开数组元素的子字段
      for (const item of val) {
        if (item && typeof item === 'object' && !Array.isArray(item)) {
          options.push(...generatePathOptions(
            item as Record<string, unknown>,
            arrayPath,
            depth + 1,
            seenPaths,
          ))
        }
      }
    } else if (val && typeof val === 'object') {
      addOption(path)
      options.push(...generatePathOptions(
        val as Record<string, unknown>,
        path,
        depth + 1,
        seenPaths,
      ))
    } else {
      addOption(path)
    }
  }
  return options
}

/**
 * 写回条件比较值。
 *
 * 类型档位与输入草稿由 `JsonValueInput` 管理，与「设置字段值」共用同一套映射 ——
 * 「等于 null」以前只能靠留空试出来，有了显式档位后它是可选项而非隐藏语义。
 */
function updateConditionValue(index: number, value: unknown) {
  updateCondition(index, { value })
}

// ==================== 工具函数 ====================

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
        :disabled="readonly"
      />

      <NSelect
        :value="currentOperationType"
        @update:value="currentOperationType = $event"
        :options="operationTypeOptions"
        placeholder="选择操作"
        size="small"
        class="rule-op-select"
        :disabled="readonly"
      />

      <label class="rule-conditional-toggle">
        <NSwitch
          :value="rule.conditional"
          @update:value="updateConditional"
          size="small"
          :disabled="readonly"
        />
        <span class="rule-conditional-label">条件执行</span>
      </label>

      <div class="rule-actions">
        <button
          class="rule-icon-btn"
          :disabled="readonly || rule.order === 0"
          @click="emit('move-up')"
          title="上移"
          type="button"
        >
          <svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="4 10 8 6 12 10" />
          </svg>
        </button>
        <button
          class="rule-icon-btn"
          :disabled="readonly"
          @click="emit('move-down')"
          title="下移"
          type="button"
        >
          <svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
            <polyline points="4 6 8 10 12 6" />
          </svg>
        </button>
        <button
          class="rule-icon-btn rule-icon-btn--danger"
          :disabled="readonly"
          @click="emit('remove')"
          title="删除"
          type="button"
        >
          <svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
            <line x1="4" y1="4" x2="12" y2="12" />
            <line x1="12" y1="4" x2="4" y2="12" />
          </svg>
        </button>
      </div>
    </div>

    <!-- set_value 值输入：左侧类型档位，右侧取值 -->
    <div v-if="currentOperationType === 'set_value'" class="rule-value-section">
      <label class="rule-value-label">字段值</label>
      <JsonValueInput
        :value="setValue"
        @update:value="updateSetValue"
        :readonly="readonly"
      />
    </div>

    <!-- 条件列表 -->
    <div v-if="rule.conditional" class="rule-conditions">
      <div class="rule-conditions-header">
        <span class="rule-conditions-title">条件（全部满足）</span>
        <NButton text size="tiny" :disabled="readonly" @click="addCondition">+ 添加条件</NButton>
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
          :disabled="readonly"
        />
        <NSelect
          :value="cond.operator"
          @update:value="updateCondition(idx, { operator: $event as ConditionOperator })"
          :options="conditionOperatorOptions"
          size="small"
          class="cond-op"
          :disabled="readonly"
        />
        <JsonValueInput
          v-if="cond.operator === 'equals'"
          :value="cond.value"
          @update:value="updateConditionValue(idx, $event)"
          :readonly="readonly"
          type-class="cond-value-type"
          class="cond-value"
        />
        <span v-else class="cond-value-placeholder">—</span>
        <button
          class="rule-icon-btn rule-icon-btn--danger"
          :disabled="readonly"
          @click="removeCondition(idx)"
          title="删除条件"
          type="button"
        >
          <svg viewBox="0 0 16 16" width="12" height="12" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round">
            <line x1="4" y1="4" x2="12" y2="12" />
            <line x1="12" y1="4" x2="4" y2="12" />
          </svg>
        </button>
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
        :readonly="readonly"
        @update:rules="updateNestedRules"
      />
    </div>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.rule-item {
  border: 1px solid rgba($accent, 0.18);
  border-radius: $radius;
  padding: 6px 8px;
  background: rgba($accent, 0.03);
  margin-bottom: 4px;
  font-size: 13px;

  &--nested {
    margin-left: 8px;
    border-left: 2px solid rgba($accent, 0.5);
    background: transparent;
  }
}

.rule-header {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.rule-order {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 18px;
  height: 18px;
  border-radius: 50%;
  background: $accent;
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  flex-shrink: 0;
}

.rule-field-select {
  width: 150px;
}

.rule-op-select {
  width: 130px;
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

.rule-icon-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: $text-muted;
  cursor: pointer;
  transition: background-color 0.15s, color 0.15s;

  &:hover:not(:disabled) {
    background: rgba($accent, 0.12);
    color: $accent;
  }

  &:active:not(:disabled) {
    background: rgba($accent, 0.2);
  }

  &:disabled {
    opacity: 0.4;
    cursor: not-allowed;
  }

  &--danger {
    color: $danger;

    &:hover:not(:disabled) {
      background: rgba($danger, 0.12);
      color: $danger;
    }
  }

  svg {
    display: block;
  }
}

.rule-value-section {
  margin-top: 4px;
  padding-left: 24px;
}

.rule-value-label {
  display: block;
  font-size: 11px;
  color: $text-muted;
  margin-bottom: 2px;
}

.rule-error {
  display: block;
  color: $danger;
  font-size: 11px;
  margin-top: 2px;
}

.rule-conditions {
  margin-top: 4px;
  padding-left: 24px;
}

.rule-conditions-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2px;
}

.rule-conditions-title {
  font-size: 11px;
  color: $text-muted;
}

.rule-condition-row {
  display: flex;
  align-items: flex-start;
  gap: 4px;
  margin-bottom: 2px;
}

.cond-path {
  flex: 1 1 auto;
  width: auto;
  min-width: 200px;
}

.cond-op {
  flex: 0 0 90px;
  width: 90px;
}

/**
 * 比较值区。
 *
 * 比原先的单输入框宽（内含类型档位 + 取值控件），故让它与路径下拉争抢剩余空间；
 * 路径的 min-width 相应从 240 降到 200，两者在窄容器下都还能读。
 */
.cond-value {
  flex: 1 1 260px;
  min-width: 200px;
  max-width: 340px;
}

/** 档位下拉在条件行里收窄：这一行控件比「设置字段值」那行多一个。 */
.cond-value :deep(.cond-value-type) {
  width: 84px;
}

.cond-value-placeholder {
  flex: 0 1 160px;
  width: 160px;
  min-width: 110px;
  max-width: 180px;
  text-align: center;
  color: $text-muted;
  font-size: 12px;
}

.rule-conditions-empty {
  font-size: 11px;
  color: $text-muted;
  font-style: italic;
}

.rule-nested {
  margin-top: 4px;
  padding-left: 24px;
}

.rule-nested-empty {
  font-size: 11px;
  color: $text-muted;
  font-style: italic;
  padding: 4px;
}
</style>
