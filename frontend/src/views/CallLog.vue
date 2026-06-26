<script setup lang="ts">
import { NCard, NEmpty } from 'naive-ui'
</script>

<template>
    <div class="call-log-page">
        <!-- 左侧：调用列表（窄列） -->
        <n-card title="调用记录" :bordered="true" class="call-log-list">
            <div class="call-log-empty">
                <NEmpty description="无调用信息" />
            </div>
        </n-card>

        <!-- 右侧：详细信息（宽列） -->
        <n-card title="详细信息" :bordered="true" class="call-log-detail">
            <div class="call-log-empty">
                <NEmpty description="点击左侧以查看详细信息" />
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
}

.call-log-detail {
    flex: 3;
}

.call-log-empty {
    display: flex;
    align-items: center;
    justify-content: center;
    height: 100%;
    min-height: 300px;
}
</style>
