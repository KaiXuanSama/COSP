<script setup lang="ts">
/**
 * JsonNode — JSON 树节点（递归组件）。
 *
 * 渲染单个 JSON 键值对，对象/数组类型支持折叠/展开。
 */
import { ref, computed } from 'vue'

/**
 * 折叠规则：
 * - 'none': 不默认折叠任何节点（仅深度 > 3 时折叠）
 * - 'depth-only': 仅基于深度阈值折叠
 * - 函数: (key, depth, parentKey, indexInParent, totalChildren) => boolean
 */
export type CollapseRule =
  | 'none'
  | 'depth-only'
  | ((key: string, depth: number, parentKey: string, indexInParent: number, totalChildren: number) => boolean)

const props = withDefaults(
  defineProps<{
    nodeKey?: string
    value: unknown
    depth?: number
    isLast?: boolean
    collapseRule?: CollapseRule
    parentKey?: string
    indexInParent?: number
    totalChildren?: number
  }>(),
  {
    nodeKey: '',
    depth: 0,
    isLast: true,
    collapseRule: 'none',
    parentKey: '',
    indexInParent: 0,
    totalChildren: 1,
  },
)

const collapsed = ref(resolveCollapsed())

function resolveCollapsed(): boolean {
  if (props.depth > 3) return true
  const rule = props.collapseRule
  if (rule === 'none') return false
  if (rule === 'depth-only') return false
  if (typeof rule === 'function') {
    return rule(props.nodeKey, props.depth, props.parentKey, props.indexInParent, props.totalChildren)
  }
  return false
}

const valueType = computed(() => {
  if (props.value === null) return 'null'
  if (Array.isArray(props.value)) return 'array'
  return typeof props.value
})

const isExpandable = computed(() => valueType.value === 'object' || valueType.value === 'array')

const entries = computed(() => {
  if (valueType.value === 'object') return Object.entries(props.value as Record<string, unknown>)
  if (valueType.value === 'array') return (props.value as unknown[]).map((v, i) => [String(i), v] as [string, unknown])
  return []
})

const previewText = computed(() => {
  if (valueType.value === 'array') {
    const arr = props.value as unknown[]
    return arr.length === 0 ? '[]' : `[${arr.length} items]`
  }
  if (valueType.value === 'object') {
    const obj = props.value as Record<string, unknown>
    const keys = Object.keys(obj)
    return keys.length === 0 ? '{}' : `{${keys.length} keys}`
  }
  return ''
})

function toggle() {
  collapsed.value = !collapsed.value
}

function formatPrimitive(val: unknown): string {
  if (val === null) return 'null'
  if (typeof val === 'string') return `"${val}"`
  return String(val)
}
</script>

<template>
  <div class="json-node" :style="{ paddingLeft: depth > 0 ? '20px' : '0' }">
    <!-- 键名 -->
    <span v-if="nodeKey !== ''" class="json-key">"{{ nodeKey }}"</span>
    <span v-if="nodeKey !== ''" class="json-colon">: </span>

    <!-- 可展开的对象/数组 -->
    <template v-if="isExpandable">
      <span class="json-toggle" @click="toggle">
        <span class="json-toggle-icon">{{ collapsed ? '▶' : '▼' }}</span>
        <span v-if="collapsed" class="json-preview">{{ previewText }}</span>
        <span v-else class="json-bracket">{{ valueType === 'array' ? '[' : '{' }}</span>
      </span>

      <!-- 子节点 -->
      <template v-if="!collapsed">
        <JsonNode
          v-for="([childKey, childVal], index) in entries"
          :key="childKey"
          :node-key="valueType === 'array' ? '' : childKey"
          :value="childVal"
          :depth="depth + 1"
          :is-last="index === entries.length - 1"
          :collapse-rule="collapseRule"
          :parent-key="nodeKey"
          :index-in-parent="index"
          :total-children="entries.length"
        />
        <span class="json-bracket" :style="{ paddingLeft: depth > 0 ? '20px' : '0' }">
          {{ valueType === 'array' ? ']' : '}' }}
        </span>
      </template>
    </template>

    <!-- 原始值 -->
    <template v-else>
      <span :class="'json-' + valueType">{{ formatPrimitive(value) }}</span>
    </template>

    <!-- 逗号 -->
    <span v-if="!isLast" class="json-comma">,</span>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.json-node {
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 13px;
  line-height: 1.7;
}

.json-key {
  color: #a626a4;
}

.json-colon,
.json-comma {
  color: $text-muted;
}

.json-bracket {
  color: $text-muted;
  font-weight: 600;
}

.json-toggle {
  cursor: pointer;
  user-select: none;

  &:hover .json-toggle-icon {
    color: $accent;
  }
}

.json-toggle-icon {
  display: inline-block;
  width: 14px;
  font-size: 10px;
  color: $text-muted;
  transition: color 0.15s;
}

.json-preview {
  color: $text-muted;
  font-style: italic;
}

.json-string {
  color: #50a14f;
}

.json-number {
  color: #986801;
}

.json-boolean {
  color: #4078f2;
}

.json-null {
  color: $text-muted;
  font-style: italic;
}
</style>
