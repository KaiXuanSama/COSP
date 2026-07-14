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
      @update:rule="updateRule(index, $event)"
      @move-up="moveUp(index)"
      @move-down="moveDown(index)"
      @remove="removeRule(index)"
    />

    <NButton dashed size="small" @click="addRule" class="rule-list-add-btn">
      + 添加规则
    </NButton>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.rule-list {
  display: flex;
  flex-direction: column;
  gap: $space-xs;
}

.rule-list-empty {
  padding: $space-md;
  text-align: center;
  color: $text-muted;
  font-size: 13px;
  border: 1px dashed $border;
  border-radius: $radius;
}

.rule-list-add-btn {
  margin-top: $space-xs;
}
</style>
