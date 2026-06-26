<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'
import { NCard, NEmpty, NSpin } from 'naive-ui'
import { fetchLogs } from '@/api'

interface LogItem {
  id: number
  provider_key: string
  model_name: string
  is_stream: number
  status_code: number
  created_at: string
}

const logs = ref<LogItem[]>([])
const currentPage = ref(1)
const totalPages = ref(0)
const pageSize = 50
const loading = ref(false)
const loadingMore = ref(false)
const initialLoading = ref(true)

const hasMore = computed(() => currentPage.value < totalPages.value)

/**
 * 加载第一页日志
 */
async function loadFirstPage() {
  loading.value = true
  initialLoading.value = true
  try {
    const res = await fetchLogs(1, pageSize)
    logs.value = res.data.items || []
    currentPage.value = res.data.currentPage
    totalPages.value = res.data.totalPages
  } catch (e) {
    console.error('加载日志失败:', e)
  } finally {
    loading.value = false
    initialLoading.value = false
  }
}

/**
 * 加载更多日志（追加到列表末尾）
 */
async function loadMore() {
  if (!hasMore.value || loadingMore.value) return

  loadingMore.value = true
  try {
    const nextPage = currentPage.value + 1
    const res = await fetchLogs(nextPage, pageSize)
    const newItems = res.data.items || []
    logs.value = [...logs.value, ...newItems]
    currentPage.value = res.data.currentPage
    totalPages.value = res.data.totalPages
  } catch (e) {
    console.error('加载更多日志失败:', e)
  } finally {
    loadingMore.value = false
  }
}

/**
 * 格式化时间（只显示时分秒）
 */
function formatTime(dateStr: string): string {
  if (!dateStr) return ''
  const parts = dateStr.split('T')
  return parts.length > 1 ? parts[1] : dateStr
}

/**
 * 获取状态码对应的颜色类名
 */
function getStatusClass(code: number): string {
  if (code >= 200 && code < 300) return 'success'
  if (code >= 400 && code < 500) return 'warning'
  if (code >= 500) return 'error'
  return 'default'
}

onMounted(() => {
  loadFirstPage()
})
</script>

<template>
  <div class="call-log-page">
    <!-- 左侧：调用列表（窄列） -->
    <n-card title="调用记录" :bordered="true" class="call-log-list" content-scrollable>
      <!-- 加载中状态 -->
      <div v-if="initialLoading" class="call-log-loading">
        <n-spin size="medium" />
      </div>

      <!-- 空状态 -->
      <div v-else-if="logs.length === 0" class="call-log-empty">
        <n-empty description="暂无调用记录" />
      </div>

      <!-- 日志列表 -->
      <div v-else class="log-list">
        <div v-for="log in logs" :key="log.id" class="log-item">
          <div class="log-item-top" :class="getStatusClass(log.status_code)"></div>
          <div class="log-item-content">
            <div class="log-item-header">
              <span class="log-provider">{{ log.provider_key }}</span>
              <span class="log-status">{{ log.status_code }}</span>
            </div>
            <div class="log-item-body">
              <span class="log-model">{{ log.model_name }}</span>
              <span class="log-time">{{ formatTime(log.created_at) }}</span>
            </div>
          </div>
        </div>

        <!-- 加载更多 -->
        <div class="log-load-more">
          <div v-if="loadingMore" class="load-more-loading">
            <n-spin size="small" />
            <span>加载中...</span>
          </div>
          <div v-else-if="hasMore" class="load-more-btn" @click="loadMore">
            显示更多条目
          </div>
          <div v-else class="load-more-end">
            已经到底了
          </div>
        </div>
      </div>
    </n-card>

    <!-- 右侧：详细信息（宽列） -->
    <n-card title="详细信息" :bordered="true" class="call-log-detail">
      <div class="call-log-empty">
        <n-empty description="点击左侧以查看详细信息" />
      </div>
    </n-card>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.call-log-page {
  display: flex;
  gap: $space-lg;
  height: calc(100vh - #{$header-height} - #{$space-2xl});
}

.call-log-list {
  flex: 2;
  height: 100%;
}

.call-log-detail {
  flex: 3;
}

.call-log-loading,
.call-log-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100%;
  min-height: 200px;
}

.log-list {
  display: flex;
  flex-direction: column;
  gap: $space-sm;
}

.log-item {
  background: $surface;
  border: 1px solid $border;
  border-radius: $radius;
  position: relative;
  overflow: hidden;
  transition: all 0.25s ease;
  cursor: default;

  &:hover {
    transform: translateY(-1px);
    box-shadow: $shadow-sm;
  }
}

.log-item-top {
  height: 3px;
  width: 100%;

  &.success {
    background: $success;
  }

  &.warning {
    background: $warning;
  }

  &.error {
    background: $danger;
  }

  &.default {
    background: $border;
  }
}

.log-item-content {
  padding: $space-sm $space-md;
}

.log-item-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: $space-xs;
}

.log-provider {
  font-family: $font-display;
  font-size: 15px;
  font-weight: 600;
  color: $text-primary;
}

.log-status {
  font-family: $font-body;
  font-size: 13px;
  font-variant-numeric: tabular-nums;
  color: $text-muted;
}

.log-item-body {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.log-model {
  font-family: $font-body;
  font-size: 13px;
  color: $text-body;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 60%;
}

.log-time {
  font-family: $font-body;
  font-size: 12px;
  color: $text-muted;
  font-variant-numeric: tabular-nums;
}

.log-load-more {
  text-align: center;
  padding: $space-md 0 $space-sm;
}

.load-more-loading {
  display: inline-flex;
  align-items: center;
  gap: $space-sm;
  color: $text-muted;
  font-family: $font-body;
  font-size: 13px;
}

.load-more-btn {
  display: inline-block;
  color: $accent;
  font-family: $font-body;
  font-size: 13px;
  cursor: pointer;
  padding: $space-xs $space-md;
  border: 1px solid $accent-mid;
  border-radius: $radius;
  transition: all 0.2s ease;

  &:hover {
    background: $accent-light;
    border-color: $accent;
  }
}

.load-more-end {
  color: $text-muted;
  font-family: $font-body;
  font-size: 13px;
}
</style>
