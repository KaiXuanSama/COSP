<script setup lang="ts">
/**
 * DiffJsonNode — 递归渲染带状态着色的 JSON 树节点。
 *
 * 状态着色：
 * - same: 浅灰
 * - changed / added: 橙色
 * - deleted: 红色 + 删除线
 */
import type { DiffNode } from '@/features/request-body-rules/diff'
import { formatPrimitive } from '@/features/request-body-rules/diff'
import DiffJsonNode from './DiffJsonNode.vue'

defineProps<{
  node: DiffNode
  /** 是否为父级 children 的最后一个节点（控制逗号） */
  isLast?: boolean
}>()
</script>

<template>
  <div class="diff-node" :class="`diff-node--${node.status}`">
    <!-- 键名 -->
    <span v-if="node.key !== null" class="diff-key">"{{ node.key }}"</span>
    <span v-if="node.key !== null" class="diff-colon">: </span>

    <!-- 对象 -->
    <template v-if="node.kind === 'object'">
      <span class="diff-bracket">{</span>
      <div v-if="node.children && node.children.length > 0" class="diff-children">
        <DiffJsonNode
          v-for="(child, index) in node.children"
          :key="`${child.key ?? index}`"
          :node="child"
          :is-last="index === node.children.length - 1"
        />
      </div>
      <span class="diff-bracket">}</span>
    </template>

    <!-- 数组 -->
    <template v-else-if="node.kind === 'array'">
      <span class="diff-bracket">[</span>
      <div v-if="node.children && node.children.length > 0" class="diff-children">
        <DiffJsonNode
          v-for="(child, index) in node.children"
          :key="`${child.key ?? index}`"
          :node="child"
          :is-last="index === node.children.length - 1"
        />
      </div>
      <span class="diff-bracket">]</span>
    </template>

    <!-- 原始值 -->
    <template v-else>
      <span class="diff-value">{{ formatPrimitive(node.value) }}</span>
    </template>

    <span v-if="!isLast" class="diff-comma">,</span>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.diff-node {
  font-family: $font-mono, 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.65;
  white-space: pre-wrap;
  word-break: break-all;
}

.diff-children {
  padding-left: 16px;
}

.diff-key,
.diff-colon,
.diff-bracket,
.diff-value,
.diff-comma {
  color: inherit;
}

/* 默认 / 未修改：浅灰 */
.diff-node--same {
  color: $text-muted;
}

/* 修改 / 新增：橙色 */
.diff-node--changed,
.diff-node--added {
  color: $accent;
  font-weight: 500;
}

/* 删除：红色 + 删除线 */
.diff-node--deleted {
  color: $danger;
  text-decoration: line-through;
  text-decoration-thickness: 1px;
  opacity: 0.85;
}
</style>
