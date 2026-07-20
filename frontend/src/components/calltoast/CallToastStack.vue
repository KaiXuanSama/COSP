<script setup lang="ts">
/**
 * 调用生命周期 Toast 栈。
 *
 * 挂在布局最外层（脱离 router-view），SSE 连接常驻，跨页面切换不消失。
 * 每次流经代理的 Copilot 调用对应一个 Toast，按 requestId 分组，
 * 随后端推送的阶段事件（RECEIVED → CONNECTED → CHUNK → COMPLETED/FAILED）实时更新文案，
 * 终态后短暂停留再向右淡出。不同调用的状态天然隔离（每条 Reactor 订阅链一个 requestId）。
 */
import { computed } from 'vue'
import { useCallLifecycleStore, type CallToast, type CallPhase } from '@/stores/callLifecycle'

const store = useCallLifecycleStore()

// 越新的调用排在最上面，与 SSE 到达顺序相反。
const orderedToasts = computed(() => [...store.toasts].slice().reverse())

/** 各阶段的展示文案。流式与非流式在 CHUNK/COMPLETED 上略有差异。 */
function phaseText(toast: CallToast): string {
  switch (toast.phase) {
    case 'RECEIVED':
      return '下游发出请求'
    case 'CONNECTED':
      return '已连接，正在等待首字响应'
    case 'CHUNK':
      return `已产生 chunk：${toast.chunkCount}`
    case 'COMPLETED':
      return toast.stream ? `响应完成，总 chunk 数：${toast.chunkCount}` : '响应完成'
    case 'FAILED':
      return '响应失败'
    default:
      return ''
  }
}

/** 阶段对应的状态点颜色类。 */
function phaseClass(phase: CallPhase): string {
  switch (phase) {
    case 'RECEIVED':
      return 'is-received'
    case 'CONNECTED':
      return 'is-connected'
    case 'CHUNK':
      return 'is-chunk'
    case 'COMPLETED':
      return 'is-completed'
    case 'FAILED':
      return 'is-failed'
    default:
      return ''
  }
}

/** CONNECTED / 首字前状态显示脉冲动画，提示"正在等待"。 */
function isPulsing(phase: CallPhase): boolean {
  return phase === 'RECEIVED' || phase === 'CONNECTED'
}
</script>

<template>
  <div class="call-toast-stack">
    <transition-group name="toast">
      <div v-for="toast in orderedToasts" :key="toast.requestId" class="call-toast"
        :class="{ 'call-toast--leaving': toast.leaving }">
        <span class="call-toast__dot" :class="phaseClass(toast.phase)"
          :data-pulsing="isPulsing(toast.phase) ? 'true' : 'false'"></span>
        <div class="call-toast__body">
          <div class="call-toast__model">{{ toast.model }}</div>
          <div class="call-toast__text">{{ phaseText(toast) }}</div>
        </div>
      </div>
    </transition-group>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.call-toast-stack {
  position: fixed;
  top: calc(#{$header-height} + #{$space-md});
  right: $space-md;
  z-index: 200;
  display: flex;
  flex-direction: column;
  gap: $space-sm;
  pointer-events: none;
  max-width: 300px;
}

.call-toast {
  display: flex;
  align-items: flex-start;
  gap: $space-sm;
  padding: $space-sm $space-md;
  background: $surface;
  border: 1px solid $border;
  border-radius: $radius;
  box-shadow: $shadow-md;
  pointer-events: auto;
  min-width: 240px;
}

.call-toast__dot {
  flex: 0 0 auto;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-top: 6px;
  background: $text-muted;

  &.is-received {
    background: $blue;
  }

  &.is-connected {
    background: $warning;
  }

  &.is-chunk {
    background: $accent;
  }

  &.is-completed {
    background: $success;
  }

  &.is-failed {
    background: $danger;
  }

  &[data-pulsing='true'] {
    animation: toast-dot-pulse 1.2s ease-in-out infinite;
  }
}

@keyframes toast-dot-pulse {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.4;
    transform: scale(0.75);
  }
}

.call-toast__body {
  min-width: 0;
  flex: 1;
}

.call-toast__model {
  font-family: $font-mono;
  font-size: 11px;
  font-weight: 500;
  color: $text-primary;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 2px;
}

.call-toast__text {
  font-family: $font-body;
  font-size: 13px;
  color: $text-body;
}

/* 进出场：从右侧淡入，向右淡出。 */
.toast-enter-from {
  opacity: 0;
  transform: translateX(24px);
}

.toast-enter-active {
  transition: opacity 0.32s ease, transform 0.32s cubic-bezier(0.22, 1, 0.36, 1);
}

.toast-leave-active {
  transition: opacity 0.32s ease, transform 0.32s ease;
  position: absolute;
}

.toast-leave-to {
  opacity: 0;
  transform: translateX(24px);
}

.toast-move {
  transition: transform 0.32s cubic-bezier(0.22, 1, 0.36, 1);
}

/* leaving 标志触发的即时淡出（终态停留后），与 transition-group leave 协同。 */
.call-toast--leaving {
  opacity: 0;
  transform: translateX(24px);
  transition: opacity 0.32s ease, transform 0.32s ease;
}
</style>
