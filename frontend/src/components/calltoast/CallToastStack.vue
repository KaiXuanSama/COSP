<script setup lang="ts">
/**
 * 调用生命周期 Toast 栈。
 *
 * 挂在布局最外层（脱离 router-view），SSE 连接常驻，跨页面切换不消失。
 * 每次流经代理的 Copilot 调用对应一个 Toast，按 requestId 分组，
 * 随后端推送的阶段事件（RECEIVED → CONNECTED → CHUNK → COMPLETED/FAILED）实时更新文案，
 * 终态后短暂停留再向右淡出。不同调用的状态天然隔离（每条 Reactor 订阅链一个 requestId）。
 *
 * 主动断连通过右键上下文菜单触发，始终可用——后端取消端点不看任何「超时」标志，
 * 故前端也不需要计时器或 canCancel 字段。
 */
import { computed, ref } from 'vue'
import { useCallLifecycleStore, type CallToast, type CallPhase } from '@/stores/callLifecycle'

const store = useCallLifecycleStore()

// 越新的调用排在最上面，与 SSE 到达顺序相反。
const orderedToasts = computed(() => [...store.toasts].slice().reverse())

/** 当前打开的右键菜单目标（null 表示无菜单）。 */
const menuTarget = ref<CallToast | null>(null)
/** 菜单的定位坐标。 */
const menuX = ref(0)
const menuY = ref(0)
/** 断连请求是否在途（菜单项 loading 态，防重复）。 */
const canceling = ref(false)

/** 各阶段的展示文案。流式与非流式在 CHUNK/COMPLETED 上略有差异。 */
function phaseText(toast: CallToast): string {
  switch (toast.phase) {
    case 'RECEIVED':
      return '下游发出请求'
    case 'CONNECTED':
      return '已连接，正在等待首字响应'
    case 'CHUNK':
      return `已产生 chunk：${toast.chunkCount}`
    case 'RETRYING':
      return `上游异常，正在重试（第 ${toast.attempt} 次）`
    case 'COMPLETED':
      return toast.stream ? `响应完成，总 chunk 数：${toast.chunkCount}` : '响应完成'
    case 'FAILED':
      return '响应失败'
    case 'CANCELED':
      return toast.stream && toast.chunkCount > 0 ? `下游已断开，已产生 chunk：${toast.chunkCount}` : '下游已断开连接'
    case 'ABORTED':
      return '已主动取消本次调用'
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
    case 'RETRYING':
      return 'is-retrying'
    case 'COMPLETED':
      return 'is-completed'
    case 'FAILED':
      return 'is-failed'
    case 'CANCELED':
      return 'is-canceled'
    case 'ABORTED':
      return 'is-aborted'
    default:
      return ''
  }
}

/** 等待中的状态（未连接、等待首字、重试中）显示脉冲动画，提示"正在进行"。 */
function isPulsing(phase: CallPhase): boolean {
  return phase === 'RECEIVED' || phase === 'CONNECTED' || phase === 'RETRYING'
}

/** 是否为终态（已结束的调用不允许断连）。 */
function isTerminal(phase: CallPhase): boolean {
  return phase === 'COMPLETED' || phase === 'FAILED' || phase === 'CANCELED' || phase === 'ABORTED'
}

/** 右键 Toast：打开上下文菜单。终态调用不弹菜单（断连无意义）。 */
function onContextMenu(event: MouseEvent, toast: CallToast) {
  if (isTerminal(toast.phase)) return
  event.preventDefault()
  menuTarget.value = toast
  canceling.value = false
  menuX.value = event.clientX
  menuY.value = event.clientY
}

/** 点击「断连」：调用取消端点，关闭菜单。 */
async function onDisconnect() {
  if (!menuTarget.value || canceling.value) return
  canceling.value = true
  await store.cancelCall(menuTarget.value.requestId)
  closeMenu()
}

/** 关闭菜单。 */
function closeMenu() {
  menuTarget.value = null
  canceling.value = false
}
</script>

<template>
  <div class="call-toast-stack">
    <transition-group name="toast">
      <div v-for="toast in orderedToasts" :key="toast.requestId" class="call-toast"
        :class="{
          'call-toast--leaving': toast.leaving,
          'call-toast--interactive': !isTerminal(toast.phase),
        }"
        @contextmenu="onContextMenu($event, toast)">
        <span class="call-toast__dot" :class="phaseClass(toast.phase)"
          :data-pulsing="isPulsing(toast.phase) ? 'true' : 'false'"></span>
        <div class="call-toast__body">
          <div class="call-toast__model">{{ toast.model }}</div>
          <div class="call-toast__text">{{ phaseText(toast) }}</div>
        </div>
      </div>
    </transition-group>

    <!-- 右键上下文菜单 -->
    <Teleport to="body">
      <div v-if="menuTarget" class="call-toast-menu-overlay" @click="closeMenu" @contextmenu.prevent="closeMenu">
        <div class="call-toast-menu" :style="{ left: menuX + 'px', top: menuY + 'px' }"
          @click.stop @contextview.prevent>
          <button type="button" class="call-toast-menu__item" :disabled="canceling" @click="onDisconnect">
            <span v-if="canceling" class="call-toast-menu__spinner" aria-hidden="true"></span>
            {{ canceling ? '断连中…' : '断开连接' }}
          </button>
        </div>
      </div>
    </Teleport>
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

  &--interactive {
    cursor: context-menu;
  }
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
    background: $blue;
  }

  &.is-chunk {
    background: $success;
  }

  &.is-completed {
    background: $success;
  }

  &.is-failed {
    background: $danger;
  }

  &.is-canceled {
    background: $text-muted;
  }

  &.is-aborted {
    background: $text-muted;
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

/* 上下文菜单 */
.call-toast-menu-overlay {
  position: fixed;
  inset: 0;
  z-index: 300;
}

.call-toast-menu {
  position: fixed;
  z-index: 301;
  min-width: 140px;
  padding: $space-xs 0;
  background: $surface;
  border: 1px solid $border;
  border-radius: $radius;
  box-shadow: $shadow-md;
}

.call-toast-menu__item {
  display: flex;
  align-items: center;
  gap: $space-xs;
  width: 100%;
  padding: $space-xs $space-md;
  border: none;
  background: transparent;
  font-family: $font-body;
  font-size: 13px;
  color: $danger;
  cursor: pointer;
  transition: background 0.15s ease;

  &:hover:not(:disabled) {
    background: rgba($danger, 0.08);
  }

  &:disabled {
    cursor: default;
    opacity: 0.6;
  }
}

.call-toast-menu__spinner {
  width: 12px;
  height: 12px;
  border: 1.5px solid rgba($text-muted, 0.35);
  border-top-color: $text-muted;
  border-radius: 50%;
  animation: cancel-spin 0.6s linear infinite;
}

@keyframes cancel-spin {
  to {
    transform: rotate(360deg);
  }
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
