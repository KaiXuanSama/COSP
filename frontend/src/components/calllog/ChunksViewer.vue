<script setup lang="ts">
/**
 * ChunksViewer — 流式响应 chunks 查看器模态框。
 *
 * 支持两种显示模式：
 * - 块显示: 每个 chunk 以卡片形式逐条展示
 * - 规整显示: 暂未实现
 */
import { ref, computed } from 'vue'
import { NModal, NRadioGroup, NRadio, NEmpty, NScrollbar } from 'naive-ui'

const props = defineProps<{
  show: boolean
  chunks: string[]
}>()

const emit = defineEmits<{
  (e: 'update:show', value: boolean): void
}>()

const displayMode = ref<'block' | 'clean'>('block')

/**
 * 尝试将 chunk 字符串格式化为 JSON
 */
function formatChunk(chunk: string): string {
  try {
    return JSON.stringify(JSON.parse(chunk), null, 2)
  } catch {
    return chunk
  }
}
</script>

<template>
  <n-modal
    :show="show"
    preset="card"
    title="流式响应"
    :style="{ maxWidth: '720px', width: '90vw' }"
    closable
    :mask-closable="true"
    @update:show="(val: boolean) => emit('update:show', val)"
  >
    <template #header-extra>
      <n-radio-group v-model:value="displayMode" size="small">
        <n-radio value="block">块显示</n-radio>
        <n-radio value="clean">规整显示</n-radio>
      </n-radio-group>
    </template>

    <!-- 块显示模式 -->
    <div v-if="displayMode === 'block'" class="chunks-block">
      <n-scrollbar style="max-height: 65vh">
        <div v-for="(chunk, index) in chunks" :key="index" class="chunk-item">
          <div class="chunk-header">
            <span class="chunk-index">#{{ index + 1 }}</span>
          </div>
          <pre class="chunk-content">{{ formatChunk(chunk) }}</pre>
        </div>
      </n-scrollbar>
    </div>

    <!-- 规整显示模式 -->
    <div v-else class="chunks-clean">
      <n-empty description="暂未实现" />
    </div>
  </n-modal>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.chunks-block {
  display: flex;
  flex-direction: column;
  gap: $space-sm;
}

.chunk-item {
  background: $bg;
  border: 1px solid $border;
  border-radius: $radius;
  overflow: hidden;
}

.chunk-header {
  display: flex;
  align-items: center;
  padding: $space-xs $space-md;
  border-bottom: 1px solid $border-light;
  background: $surface;
}

.chunk-index {
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  color: $text-muted;
}

.chunk-content {
  margin: 0;
  padding: $space-sm $space-md;
  font-family: 'Cascadia Code', 'Fira Code', 'Consolas', monospace;
  font-size: 12px;
  line-height: 1.5;
  color: $text-body;
  white-space: pre-wrap;
  word-break: break-all;
}

.chunks-clean {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 200px;
}
</style>
