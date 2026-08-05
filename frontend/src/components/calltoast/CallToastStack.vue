<script setup lang="ts">
/**
 * 调用生命周期指示器 + 展开面板。
 *
 * 挂在布局最外层（脱离 router-view），SSE 连接常驻，跨页面切换不消失。
 *
 * 常驻形态是一个紧凑徽标，固定在 header 右侧：脉冲点 + 进行中调用数。
 * 占用从「每个调用一张卡片」降到「一行」，且落在 header 的空白 chrome 上，
 * 不再遮挡内容区右上角的操作控件（各页面卡片头部靠右的按钮/下拉都贴着内容列右缘）。
 * 徽标只在有进行中调用时出现，颜色取当前最值得注意的阶段（重试 > 等待首字 > 产出中）。
 *
 * 点击徽标展开面板：列出全部调用（含短暂保留的终态），越新的越靠上。
 * 面板<strong>固定</strong>——点击面板外不会收起，再次点击徽标才收起，
 * 便于边看状态边操作内容区。
 * 主动断连通过右键调用项触发，始终可用——后端取消端点不看任何「超时」标志，
 * 故前端也不需要计时器或 canCancel 字段。
 */
import { computed, ref } from 'vue'
import { useCallLifecycleStore, type CallToast, type CallPhase } from '@/stores/callLifecycle'

const store = useCallLifecycleStore()

/** 进行中（非终态）的调用 —— 徽标的计数与脉冲依据。 */
const activeToasts = computed(() => store.toasts.filter((t) => !isTerminal(t.phase)))
const activeCount = computed(() => activeToasts.value.length)

/** 面板里按到达顺序排列，越新的调用越靠上。 */
const orderedToasts = computed(() => [...store.toasts].slice().reverse())

/** 面板展开与否。 */
const panelOpen = ref(false)

/** 徽标状态点颜色：取进行中调用里最值得注意的阶段，空时用中性灰。 */
const badgePhaseClass = computed(() => {
  const phases = new Set(activeToasts.value.map((t) => t.phase))
  if (phases.has('RETRYING')) return 'is-retrying'
  if (phases.has('RECEIVED') || phases.has('CONNECTED')) return 'is-connected'
  if (phases.has('CHUNK')) return 'is-chunk'
  return 'is-idle'
})

/** 切换面板展开状态。面板固定，不随点击外部收起；再次点击徽标才收起。 */
function togglePanel() {
  panelOpen.value = !panelOpen.value
}

/** 当前打开的右键菜单目标（null 表示无菜单）。 */
const menuTarget = ref<CallToast | null>(null)
/** 菜单的定位坐标。 */
const menuX = ref(0)
const menuY = ref(0)
/** 断连请求是否在途（菜单项 loading 态，防重复）。 */
const canceling = ref(false)
/** 静默重试请求是否在途（菜单项 loading 态，防重复）。 */
const retrying = ref(false)

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

/** 右键 Toast：打开上下文菜单。终态调用不弹菜单（断连与重试均无意义）。 */
function onContextMenu(event: MouseEvent, toast: CallToast) {
  if (isTerminal(toast.phase)) return
  event.preventDefault()
  menuTarget.value = toast
  canceling.value = false
  retrying.value = false
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

/**
 * 点击「静默重试」：调用重试端点，关闭菜单。
 *
 * 后端只中断当前上游请求并重新发起，下游连接保持打开、Copilot 无感知；
 * 若上游已吐出部分 chunk，重发后会收到重复内容（有意的取舍，用户可感知）。
 */
async function onSilentRetry() {
  if (!menuTarget.value || retrying.value) return
  retrying.value = true
  await store.retryCall(menuTarget.value.requestId)
  closeMenu()
}

/** 关闭菜单。 */
function closeMenu() {
  menuTarget.value = null
  canceling.value = false
  retrying.value = false
}
</script>

<template>
  <div class="call-toast-stack">
    <!--
      常驻徽标：header 右侧的紧凑指示器，始终显示。
      计数归零时状态点退为中性灰（is-idle），脉冲随 activeCount 关闭；
      点击它展开 / 收起面板，无需进行中的调用即可查看历史记录。
    -->
    <button type="button" class="call-toast-badge"
      :class="{ 'is-open': panelOpen }" :aria-expanded="panelOpen" :title="panelOpen ? '收起实时调用' : '实时调用'"
      @click="togglePanel">
      <span class="call-toast-badge__dot" :class="badgePhaseClass"
        :data-pulsing="activeCount > 0 ? 'true' : 'false'"></span>
      <span class="call-toast-badge__count">{{ activeCount }}</span>
    </button>

    <Teleport to="body">
      <div v-if="panelOpen" class="call-toast-panel">
        <transition-group name="toast-item">
          <div v-for="toast in orderedToasts" :key="toast.requestId" class="call-toast-item"
            :class="{
              'call-toast-item--leaving': toast.leaving,
              'call-toast-item--interactive': !isTerminal(toast.phase),
            }"
            @contextmenu="onContextMenu($event, toast)">
            <span class="call-toast-item__dot" :class="phaseClass(toast.phase)"
              :data-pulsing="isPulsing(toast.phase) ? 'true' : 'false'"></span>
            <div class="call-toast-item__body">
              <div class="call-toast-item__model">{{ toast.model }}</div>
              <div class="call-toast-item__text">{{ phaseText(toast) }}</div>
            </div>
          </div>
        </transition-group>
        <div v-if="!orderedToasts.length" class="call-toast-panel__empty">暂无进行中的调用</div>
      </div>

      <!-- 右键上下文菜单 -->
      <div v-if="menuTarget" class="call-toast-menu-overlay" @click="closeMenu" @contextmenu.prevent="closeMenu">
        <div class="call-toast-menu" :style="{ left: menuX + 'px', top: menuY + 'px' }"
          @click.stop @contextview.prevent>
          <button type="button" class="call-toast-menu__item" :disabled="retrying" @click="onSilentRetry">
            <span v-if="retrying" class="call-toast-menu__spinner" aria-hidden="true"></span>
            {{ retrying ? '静默重试中…' : '静默重试' }}
          </button>
          <button type="button" class="call-toast-menu__item call-toast-menu__item--danger" :disabled="canceling" @click="onDisconnect">
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

/*
  常驻容器：对齐 header（sticky top:0，高 56px），badge 垂直居中。
  容器本身 pointer-events: none —— 空白区域穿透，只有 badge 可交互，
  不挡 header 上其它元素（将来若在 header 右侧放别的东西）。
  展开面板用 fixed 挂到 body（经 Teleport），z-index 低于徽标、高于页面内容。
*/
.call-toast-stack {
  position: fixed;
  top: 0;
  right: $space-lg;
  height: $header-height;
  display: flex;
  align-items: center;
  z-index: 205;
  pointer-events: none;
}

/* ── 常驻徽标 ── */
.call-toast-badge {
  pointer-events: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  background: $surface;
  border: 1px solid $border;
  border-radius: 999px;
  box-shadow: $shadow-sm;
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;

  &:hover {
    background: $bg;
  }

  &.is-open {
    background: $bg;
    border-color: darken($border, 8%);
  }
}

.call-toast-badge__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: $text-muted;

  &.is-received,
  &.is-connected {
    background: $blue;
  }

  &.is-chunk {
    background: $success;
  }

  &.is-retrying {
    background: $accent;
  }

  &.is-idle {
    background: $text-muted;
  }

  &[data-pulsing='true'] {
    animation: toast-dot-pulse 1.2s ease-in-out infinite;
  }
}

.call-toast-badge__count {
  font-family: $font-mono;
  font-size: 12px;
  font-weight: 600;
  color: $text-primary;
  line-height: 1;
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

/* ── 展开面板 ── */
.call-toast-panel {
  position: fixed;
  top: calc(#{$header-height} + 4px);
  right: $space-lg;
  z-index: 201;
  width: 320px;
  max-width: calc(100vw - #{$space-lg} * 2);
  max-height: calc(100vh - #{$header-height} - #{$space-lg});
  overflow-y: auto;
  padding: $space-xs;
  background: $surface;
  border: 1px solid $border;
  border-radius: $radius;
  box-shadow: $shadow-lg;
}

.call-toast-item {
  display: flex;
  align-items: flex-start;
  gap: $space-sm;
  padding: $space-sm;
  border-radius: $radius;
  cursor: default;

  &:hover {
    background: $bg;
  }

  &--interactive {
    cursor: context-menu;
  }
}

.call-toast-item__dot {
  flex: 0 0 auto;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  margin-top: 5px;
  background: $text-muted;

  &.is-received,
  &.is-connected {
    background: $blue;
  }

  &.is-chunk,
  &.is-completed {
    background: $success;
  }

  &.is-failed {
    background: $danger;
  }

  &.is-canceled,
  &.is-aborted {
    background: $text-muted;
  }

  &.is-retrying {
    background: $accent;
  }

  &[data-pulsing='true'] {
    animation: toast-dot-pulse 1.2s ease-in-out infinite;
  }
}

.call-toast-item__body {
  min-width: 0;
  flex: 1;
}

.call-toast-item__model {
  font-family: $font-mono;
  font-size: 11px;
  font-weight: 500;
  color: $text-primary;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 2px;
}

.call-toast-item__text {
  font-family: $font-body;
  font-size: 13px;
  color: $text-body;
}

.call-toast-panel__empty {
  padding: $space-lg $space-sm;
  text-align: center;
  font-size: 13px;
  color: $text-muted;
}

/* ── 过渡动画 ── */

/* 面板展开/收起。 */
.panel-enter-active,
.panel-leave-active {
  transition: opacity 0.18s ease, transform 0.18s ease;
}

.panel-enter-from,
.panel-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

/* 面板内条目进出。 */
.toast-item-enter-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.toast-item-enter-from {
  opacity: 0;
  transform: translateY(-4px);
}

.toast-item-leave-active {
  transition: opacity 0.2s ease;
}

.toast-item-leave-to {
  opacity: 0;
}

.toast-item-move {
  transition: transform 0.2s ease;
}

/* leaving 标志触发的即时淡出（终态停留后），与 transition-group leave 协同。 */
.call-toast-item--leaving {
  opacity: 0;
  transition: opacity 0.32s ease;
}

/* ── 右键上下文菜单 ── */
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

/*
  菜单项基类用中性色 —— 静默重试等非破坏操作是常态；
  危险操作（断开连接）通过 --danger modifier 单独标红，
  避免「普通项也红、危险项也红」分不清轻重。
*/
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
  color: $text-primary;
  cursor: pointer;
  transition: background 0.15s ease;

  &:hover:not(:disabled) {
    background: $bg;
  }

  &:disabled {
    cursor: default;
    opacity: 0.6;
  }
}

.call-toast-menu__item--danger {
  color: $danger;

  &:hover:not(:disabled) {
    background: rgba($danger, 0.08);
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
</style>
