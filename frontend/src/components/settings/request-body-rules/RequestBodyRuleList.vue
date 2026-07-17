<script setup lang="ts">
/**
 * RequestBodyRuleList — 递归规则列表。
 *
 * 管理规则排序（上移/下移）、增删，并递归渲染嵌套规则。
 */
import { NButton } from 'naive-ui'
import type { FieldRule } from '@/features/request-body-rules/types'
import { createEmptyRule } from '@/features/request-body-rules/types'
import RequestBodyRuleItem from './RequestBodyRuleItem.vue'

const props = defineProps<{
  rules: FieldRule[]
  /** 当前作用域的原始 JSON 对象，用于生成字段下拉选项 */
  scopeObject: Record<string, unknown> | null
  /** 层级深度 */
  depth: number
  /** 是否仅用于展示；为 true 时禁止修改、排序和增删规则 */
  readonly?: boolean
}>()

const emit = defineEmits<{
  (e: 'update:rules', rules: FieldRule[]): void
}>()

function updateRule(index: number, updated: FieldRule) {
  const newRules = props.rules.map((r, i) => (i === index ? updated : r))
  emit('update:rules', newRules)
}

function moveUp(index: number) {
  if (index === 0) return
  const newRules = [...props.rules]
  const tmp = newRules[index - 1]
  newRules[index - 1] = newRules[index]
  newRules[index] = tmp
  renumber(newRules)
  emit('update:rules', newRules)
}

function moveDown(index: number) {
  if (index === props.rules.length - 1) return
  const newRules = [...props.rules]
  const tmp = newRules[index + 1]
  newRules[index + 1] = newRules[index]
  newRules[index] = tmp
  renumber(newRules)
  emit('update:rules', newRules)
}

function removeRule(index: number) {
  const newRules = props.rules.filter((_, i) => i !== index)
  renumber(newRules)
  emit('update:rules', newRules)
}

function addRule() {
  const newRule = createEmptyRule(props.rules.length)
  emit('update:rules', [...props.rules, newRule])
}

function renumber(rules: FieldRule[]) {
  rules.forEach((r, i) => {
    r.order = i
  })
}
</script>

<template>
  <div class="rule-list">
    <div v-if="rules.length === 0" class="rule-list-empty">
      暂无规则，点击下方"添加规则"开始配置
    </div>

    <RequestBodyRuleItem
      v-for="(rule, index) in rules"
      :key="rule.id"
      :rule="rule"
      :scope-object="scopeObject"
      :depth="depth"
      :readonly="readonly"
      @update:rule="updateRule(index, $event)"
      @move-up="moveUp(index)"
      @move-down="moveDown(index)"
      @remove="removeRule(index)"
    />

    <button class="rule-list-add" :disabled="readonly" @click="addRule" type="button">
      <span class="rule-list-add-icon">+</span>
      <span>添加规则</span>
    </button>
  </div>
</template>

<style lang="scss" scoped>
@use 'sass:color';
@use '@/styles/variables' as *;

.rule-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.rule-list-empty {
  padding: 12px;
  text-align: center;
  color: $text-muted;
  font-size: 12px;
  border: 1px dashed rgba($border, 0.6);
  border-radius: $radius;
  background: transparent;
}

.rule-list-add {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  align-self: flex-start;
  margin-top: 2px;
  padding: 4px 10px;
  font-size: 12px;
  color: $accent;
  background: rgba($accent, 0.06);
  border: none;
  border-radius: $radius;
  cursor: pointer;
  transition: background-color 0.15s, color 0.15s;

  &:hover {
    background: rgba($accent, 0.12);
    color: color.adjust($accent, $lightness: -8%);
  }

  &:active {
    background: rgba($accent, 0.18);
  }

  &:disabled {
    opacity: 0.45;
    cursor: not-allowed;
  }
}

.rule-list-add-icon {
  font-size: 14px;
  font-weight: 600;
  line-height: 1;
}
</style>
