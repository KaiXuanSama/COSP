<script setup lang="ts">
/**
 * RequestBodyRuleItem — 单条字段规则的编辑卡片。
 *
 * 递归渲染：当操作为 edit_object 时，内部嵌套 RequestBodyRuleList。
 * 字段下拉选项从当前作用域的原始 JSON 动态生成。
 */
import { computed, ref } from 'vue'
import { NSelect, NSwitch, NInput, NButton, NIcon } from 'naive-ui'
import type { FieldRule, RuleCondition, ConditionOperator } from '@/features/request-body-rules/types'
import { createEmptyRule, createEmptyCondition } from '@/features/request-body-rules/types'
import type { SetValueType } from '@/features/request-body-rules/setValueEditing'
import {
  BOOLEAN_VALUE_OPTIONS,
  SET_VALUE_TYPE_OPTIONS,
  defaultSetValueText,
  formatSetValueText,
  inferSetValueType,
  parseSetValue,
  sanitizeNumberInput,
} from '@/features/request-body-rules/setValueEditing'
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
 * 用户显式选择的类型档位。
 *
 * <p>档位通常能从已保存的 JSON 值反推（见 {@link inferSetValueType}），但有一档反推不出来：
 * 数值档位刚切过去时输入框是空的，空文本解析失败、值写不回去，于是反推仍得到旧类型 ——
 * 用户选了「数值」界面却弹回「字符串」。档位是用户的**意图**，当值暂时无法表达它时
 * 意图必须能独立存在，因此这里显式记一份，写回成功后清空交还给反推。
 */
const pendingSetValueType = ref<SetValueType | null>(null)

/** 当前生效的类型档位：用户刚选的优先，否则从值反推。 */
const setValueType = computed<SetValueType>(() => {
  if (pendingSetValueType.value !== null) return pendingSetValueType.value
  const op = props.rule.operations[0]
  if (!op || op.type !== 'set_value') return 'string'
  return inferSetValueType(op.value)
})

const setValueText = computed(() => {
  const op = props.rule.operations[0]
  if (!op || op.type !== 'set_value') return ''
  return formatSetValueText(op.value, setValueType.value)
})

/** 布尔档位用下拉，其余用文本输入。 */
const setValueUsesSelect = computed(() => setValueType.value === 'boolean')

/** null 档位禁止输入。 */
const setValueDisabled = computed(() => props.readonly || setValueType.value === 'null')

/** 各档位的输入提示。 */
const setValuePlaceholder = computed(() => {
  switch (setValueType.value) {
    case 'string':
      return '原样作为字符串，如 user'
    case 'number':
      return '如 0.7 或 -2'
    case 'list':
      return '如 [12.38, false, "hello world", null]'
    case 'null':
      return 'null'
    default:
      return ''
  }
})

/**
 * 尚未写回的文本。
 *
 * 解析失败时值不写回规则，但输入框必须显示用户实际打的内容 ——
 * 否则打到 `[1,` 就会被弹回上一个合法值，根本没法输入完整的列表。
 */
const pendingSetValueText = ref<string | null>(null)

/** 输入框显示的文本：优先显示未写回的草稿。 */
const setValueDisplayText = computed(() => pendingSetValueText.value ?? setValueText.value)

/** 展示用的解析错误：以输入框里实际显示的文本为准。 */
const setValueDisplayError = computed(() =>
  parseSetValue(setValueDisplayText.value, setValueType.value).error,
)

/**
 * 写回解析后的值；解析失败时保留原值，只把文本留作草稿。
 *
 * <p>类型作为显式参数而非读 `setValueType` —— 切换档位时后者尚未更新完毕，
 * 用它解析新档位的文本会得到错的值。
 *
 * <h3>为何成功后仍可能保留草稿</h3>
 * 解析成功不代表输入已经「写完」。`12.` 是合法数值（解析为 12），但把它规范化回 `12`
 * 会吃掉尾部小数点 —— 用户接着打 `3` 就得到 `123` 而不是 `12.3`，小数根本打不出来。
 * 列表同理：`[1, 2]` 规范化成 `[1,2]` 会在打字过程中不断吞掉空格。
 * 因此只在**文本与其规范形式一致**时才交还给反推，否则保留用户的原始文本。
 */
function commitSetValue(text: string, type: SetValueType) {
  const parsed = parseSetValue(text, type)
  if (parsed.error) {
    pendingSetValueText.value = text
    return
  }
  const canonical = formatSetValueText(parsed.value, type)
  pendingSetValueText.value = text === canonical ? null : text
  pendingSetValueType.value = null
  emit('update:rule', { ...props.rule, operations: [{ type: 'set_value' as const, value: parsed.value }] })
}

function updateSetValueText(text: string) {
  const normalized = setValueType.value === 'number' ? sanitizeNumberInput(text) : text
  commitSetValue(normalized, setValueType.value)
}

/** 切换类型时用该档位的默认文本重置，避免把上一档位的内容按新类型硬解释。 */
function updateSetValueType(next: SetValueType) {
  pendingSetValueType.value = next
  pendingSetValueText.value = null
  commitSetValue(defaultSetValueText(next), next)
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

/**
 * 条件比较值的宽松解析。
 *
 * <p>能解析成 JSON 就按 JSON 值算，否则当字符串 —— 于是输入 `tool` 得到 `"tool"`、
 * 输入 `10` 得到数字 10。这与「设置字段值」的严格类型档位刻意不同：
 * 那边决定发给上游的内容，猜错会改坏请求；这边只决定一条规则是否命中，
 * 猜错的后果是规则不生效，而 `equals` 的绝大多数用法是与字符串字面量比较。
 *
 * <p>TODO 条件值也该有类型档位。当前 `equals` 无法表达「等于字符串 "10"」——
 * 输入 10 会被当数字。等这个需求真实出现时，复用
 * `features/request-body-rules/setValueEditing` 的那套映射即可。
 */
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
      <div class="rule-value-row">
        <NSelect
          :value="setValueType"
          @update:value="updateSetValueType($event as SetValueType)"
          :options="SET_VALUE_TYPE_OPTIONS"
          size="small"
          class="rule-value-type"
          :disabled="readonly"
        />
        <NSelect
          v-if="setValueUsesSelect"
          :value="setValueDisplayText"
          @update:value="updateSetValueText($event)"
          :options="BOOLEAN_VALUE_OPTIONS"
          size="small"
          class="rule-value-input"
          :disabled="readonly"
        />
        <NInput
          v-else
          :value="setValueDisplayText"
          @update:value="updateSetValueText"
          :placeholder="setValuePlaceholder"
          size="small"
          class="rule-value-input"
          :disabled="setValueDisabled"
        />
      </div>
      <span v-if="setValueDisplayError" class="rule-error">{{ setValueDisplayError }}</span>
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
        <NInput
          v-if="cond.operator === 'equals'"
          :value="conditionValueText(cond)"
          @update:value="updateConditionValue(idx, $event)"
          placeholder='比较值，如 "tool"'
          size="small"
          class="cond-value"
          :disabled="readonly"
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

/** 类型档位固定宽度，取值输入占满剩余空间。 */
.rule-value-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.rule-value-type {
  width: 96px;
  flex-shrink: 0;
}

.rule-value-input {
  flex: 1;
  min-width: 0;
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
  align-items: center;
  gap: 4px;
  margin-bottom: 2px;
}

.cond-path {
  flex: 1 1 auto;
  width: auto;
  min-width: 240px;
}

.cond-op {
  flex: 0 0 90px;
  width: 90px;
}

.cond-value {
  flex: 0 1 160px;
  width: 160px;
  min-width: 110px;
  max-width: 180px;
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
