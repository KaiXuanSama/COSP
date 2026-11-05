<script setup lang="ts">
/**
 * 消费记录检索面板（受控组件）。
 *
 * 不持有条件数据：每次编辑都 emit('update:modelValue') 回写，由页面决定何时真正检索。
 * 「编辑条件」与「执行检索」分开，是因为检索会整表替换并停掉实时更新，
 * 不该在用户还没输完时就随每次按键触发。
 *
 * 匹配语义以后端为准（ADR-0020）：供应商匹配的是 provider_key，模型匹配的是上游模型名。
 */
import { computed } from 'vue'
import { NAutoComplete, NButton, NDatePicker, NInput, NSelect } from 'naive-ui'
import type { SelectGroupOption, SelectOption } from 'naive-ui'
import {
  STATUS_CLASS_OPTIONS,
  STATUS_CODE_OPTIONS,
  type LogSearchFilter,
  type MatchMode,
} from '@/features/call-log/searchFilter'

const props = defineProps<{
  modelValue: LogSearchFilter
  /** 供应商标识候选；只作提示，允许输入列表外的值以查已删除的供应商。 */
  providerKeys: string[]
  /** 当前是否处于检索模式，决定「清除条件」是否可用。 */
  active: boolean
  loading?: boolean
}>()

const emit = defineEmits<{
  'update:modelValue': [value: LogSearchFilter]
  search: []
  clear: []
}>()

function patch(next: Partial<LogSearchFilter>) {
  emit('update:modelValue', { ...props.modelValue, ...next })
}

const statusOptions: Array<SelectOption | SelectGroupOption> = [
  { type: 'group', label: '状态类', key: 'classes', children: [...STATUS_CLASS_OPTIONS] },
  { type: 'group', label: '常见状态码', key: 'codes', children: [...STATUS_CODE_OPTIONS] },
]

const matchOptions: SelectOption[] = [
  { label: '全字', value: 'exact' },
  { label: '部分', value: 'contains' },
]

/** 按当前输入过滤候选；输入为空时列出全部，方便直接挑。 */
const providerOptions = computed(() => {
  const input = props.modelValue.provider.trim().toLowerCase()
  return props.providerKeys
    .filter((key) => !input || key.toLowerCase().includes(input))
    .slice(0, 20)
    .map((key) => ({ label: key, value: key }))
})

const hasInput = computed(() => {
  const f = props.modelValue
  return Boolean(f.timeRange || f.statuses.length || f.provider.trim() || f.model.trim())
})
</script>

<template>
  <div class="search-panel" @keydown.enter="emit('search')">
    <div class="search-field search-field--time">
      <span class="search-label">时间</span>
      <NDatePicker
        size="small"
        type="datetimerange"
        format="yyyy-MM-dd HH:mm:ss"
        clearable
        :value="modelValue.timeRange"
        start-placeholder="开始"
        end-placeholder="结束"
        @update:value="(v: [number, number] | null) => patch({ timeRange: v })"
      />
    </div>

    <div class="search-field search-field--status">
      <span class="search-label">状态</span>
      <NSelect
        size="small"
        multiple
        clearable
        max-tag-count="responsive"
        placeholder="全部"
        :options="statusOptions"
        :value="modelValue.statuses"
        @update:value="(v: string[]) => patch({ statuses: v })"
      />
    </div>

    <div class="search-field search-field--text">
      <span class="search-label">供应商</span>
      <NAutoComplete
        size="small"
        clearable
        placeholder="供应商标识，如 stepfun"
        :options="providerOptions"
        :value="modelValue.provider"
        :get-show="() => true"
        @update:value="(v: string | null) => patch({ provider: v ?? '' })"
      />
      <NSelect
        size="small"
        class="match-select"
        :options="matchOptions"
        :value="modelValue.providerMatch"
        @update:value="(v: MatchMode) => patch({ providerMatch: v })"
      />
    </div>

    <div class="search-field search-field--text">
      <span class="search-label">模型</span>
      <NInput
        size="small"
        clearable
        placeholder="上游模型名，不含 [供应商] 前缀"
        :value="modelValue.model"
        @update:value="(v: string) => patch({ model: v })"
      />
      <NSelect
        size="small"
        class="match-select"
        :options="matchOptions"
        :value="modelValue.modelMatch"
        @update:value="(v: MatchMode) => patch({ modelMatch: v })"
      />
    </div>

    <div class="search-actions">
      <NButton size="small" type="primary" :loading="loading" @click="emit('search')">检索</NButton>
      <NButton size="small" :disabled="!active && !hasInput" @click="emit('clear')">清除条件</NButton>
    </div>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.search-panel {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: $space-sm $space-md;
  padding-bottom: $space-sm;
  margin-bottom: $space-sm;
  border-bottom: 1px solid $border-light;
}

.search-field {
  display: flex;
  align-items: center;
  gap: $space-xs;
}

.search-field--time {
  width: 380px;
}

.search-field--status {
  width: 260px;
}

.search-field--text {
  width: 300px;
}

.search-label {
  flex: none;
  font-size: 12px;
  color: $text-muted;
}

.match-select {
  flex: none;
  width: 72px;
}

.search-actions {
  display: flex;
  gap: $space-sm;
}
</style>
