import { nextTick, onUnmounted, ref, watch, type ComponentPublicInstance, type ComputedRef, type Ref } from 'vue'
import type { ActivityHeatmapProps, RevealState, VisibleWeek } from './heatmap'

interface UseHeatmapTimelineOptions {
  props: Readonly<ActivityHeatmapProps>
  structuralSignature: ComputedRef<string>
  visibleStateSignature: ComputedRef<string>
  resolvedActiveMode: ComputedRef<string>
  normalizedDayCount: ComputedRef<number>
  visibleWeeks: ComputedRef<VisibleWeek[]>
  containerWidth: Ref<number>
  hideTooltip: () => void
}

const DEFAULT_INITIAL_REVEAL_DELAY = 900
const DEFAULT_RIPPLE_DELAY = 25
const DEFAULT_RIPPLE_DURATION = 400
const DEFAULT_FLIP_DELAY = 50
const DEFAULT_FLIP_DURATION = 300

/**
 * 负责热力图的时间线调度。
 *
 * 核心目标不是“立即切到目标模式”，而是维护一条可叠加的列级动画队列：
 * - reveal 先走完每列自己的入场节奏；
 * - 后续模式切换按列排队，不抢断前一轮已排好的翻转；
 * - 浏览器不支持 Web Animations API 时，退回 CSS 动画保证视觉仍然成立。
 */
export function useHeatmapTimeline(options: UseHeatmapTimelineOptions) {
  const columnModes = ref<string[]>([])
  const columnReadyAt = ref<number[]>([])
  const columnElements = ref<Array<HTMLElement | null>>([])
  const revealState = ref<RevealState>('pending')

  let revealTimer: ReturnType<typeof setTimeout> | null = null
  let revealStartedAt = 0
  const transitionTimers = new Set<ReturnType<typeof setTimeout>>()
  const columnAnimations = new Map<number, Animation>()
  const columnFallbackTimers = new Map<number, ReturnType<typeof setTimeout>>()

  watch(options.structuralSignature, (nextSignature, previousSignature) => {
    options.hideTooltip()
    if (!nextSignature) {
      resetHeatmapState('pending')
      return
    }

    if (!previousSignature) {
      resetRevealState('pending')
      return
    }

    if (nextSignature !== previousSignature) {
      // 数据轴换了但组件还在时，直接清掉旧时间线，避免旧列状态污染新数据。
      resetHeatmapState('done')
    }
  })

  watch([options.normalizedDayCount, options.containerWidth], async ([dayCount, width]) => {
    if (!dayCount || !width || revealState.value !== 'pending') return
    await nextTick()
    startInitialReveal()
  })

  watch([options.visibleStateSignature, options.resolvedActiveMode], ([nextVisibleSignature, nextMode], [previousVisibleSignature]) => {
    options.hideTooltip()

    const visibleLength = options.visibleWeeks.value.length

    if (!nextMode || !visibleLength || !nextVisibleSignature) {
      resetColumnState(true)
      return
    }

    if (
      nextVisibleSignature !== previousVisibleSignature
      || columnModes.value.length !== visibleLength
      || columnReadyAt.value.length !== visibleLength
    ) {
      // 可见窗口一变，列级队列就失效了，必须按新的可见列重新建状态。
      resetColumnState(true)
      initializeColumns(visibleLength, nextMode)
      return
    }

    if (!hasPendingTransitions() && columnModes.value.every((mode) => mode === nextMode)) return

    if (revealState.value === 'pending') {
      startInitialReveal()
    }

    // activeMode 变化只是在已有时间线上追加一轮 flip，而不是抢占当前动画。
    queueModeSwitch(nextMode)
  }, { immediate: true })

  onUnmounted(() => {
    clearTransitionTimers()
    stopColumnAnimations()
    resetRevealState('pending')
  })

  function startInitialReveal() {
    if (revealState.value !== 'pending' || !options.visibleWeeks.value.length) return

    resetRevealState('pending')
    revealState.value = 'animating'
    revealStartedAt = getNow()

    const initialRevealDelay = options.props.initialRevealDelay ?? DEFAULT_INITIAL_REVEAL_DELAY
    const rippleDelay = options.props.rippleDelay ?? DEFAULT_RIPPLE_DELAY
    const rippleDuration = options.props.rippleDuration ?? DEFAULT_RIPPLE_DURATION
    const maxDistance = Math.max(0, options.visibleWeeks.value.length - 1 + 6)
    const totalDuration = initialRevealDelay + maxDistance * rippleDelay + rippleDuration + 60

    revealTimer = setTimeout(() => {
      // reveal 结束后，后续 tooltip 和 cell hover 才会恢复到稳定状态。
      revealState.value = 'done'
      revealTimer = null
    }, totalDuration)
  }

  function getNow() {
    return typeof performance !== 'undefined' ? performance.now() : Date.now()
  }

  function clearTransitionTimers() {
    for (const timer of transitionTimers) clearTimeout(timer)
    transitionTimers.clear()
  }

  function resetRevealState(nextState: Exclude<RevealState, 'animating'>) {
    if (revealTimer) {
      clearTimeout(revealTimer)
      revealTimer = null
    }

    revealState.value = nextState
    revealStartedAt = 0
  }

  function resetColumnState(clearElements = false) {
    clearTransitionTimers()
    stopColumnAnimations()
    columnModes.value = []
    columnReadyAt.value = []
    if (clearElements) {
      columnElements.value = []
    }
  }

  function resetHeatmapState(nextRevealState: Exclude<RevealState, 'animating'>) {
    resetColumnState(true)
    resetRevealState(nextRevealState)
  }

  function stopColumnAnimations() {
    for (const animation of columnAnimations.values()) animation.cancel()
    columnAnimations.clear()

    for (const timer of columnFallbackTimers.values()) clearTimeout(timer)
    columnFallbackTimers.clear()

    for (const element of columnElements.value) {
      if (!element) continue
      element.style.animation = ''
    }
  }

  function scheduleTransition(delay: number, callback: () => void) {
    const timer = setTimeout(() => {
      transitionTimers.delete(timer)
      callback()
    }, Math.max(0, delay))

    transitionTimers.add(timer)
  }

  function initializeColumns(visibleLength: number, modeKey: string) {
    // 初始化时所有列先对齐到同一个模式，后续再由队列逐列改写。
    columnModes.value = Array.from({ length: visibleLength }, () => modeKey)
    columnReadyAt.value = Array.from({ length: visibleLength }, () => 0)
    columnElements.value = Array.from({ length: visibleLength }, (_, index) => columnElements.value[index] ?? null)
  }

  function hasPendingTransitions() {
    const now = getNow()
    return columnReadyAt.value.some((readyAt) => readyAt > now)
  }

  function getColumnRevealReadyAt(columnIndex: number) {
    if (revealState.value === 'done' || revealStartedAt === 0) return 0
    const rippleDelay = options.props.rippleDelay ?? DEFAULT_RIPPLE_DELAY
    const rippleDuration = options.props.rippleDuration ?? DEFAULT_RIPPLE_DURATION
    return revealStartedAt + (columnIndex + 6) * rippleDelay + rippleDuration
  }

  function queueModeSwitch(nextMode: string) {
    if (!options.props.modes.length || !nextMode || !columnModes.value.length) return

    const now = getNow()
    const flipDelay = options.props.flipDelay ?? DEFAULT_FLIP_DELAY
    const flipDuration = options.props.flipDuration ?? DEFAULT_FLIP_DURATION
    const flipAnimationDuration = flipDuration * 2

    for (let index = 0; index < columnModes.value.length; index += 1) {
      const plannedStartAt = now + index * flipDelay
      const startAt = Math.max(
        plannedStartAt,
        columnReadyAt.value[index] ?? 0,
        getColumnRevealReadyAt(index),
      )
      const startDelay = startAt - now
      const swapDelay = startDelay + flipDuration

      // 每列记录自己的下一次可执行时间，形成真正的列级排队，而不是全局抢占。
      columnReadyAt.value[index] = startAt + flipAnimationDuration

      scheduleTransition(startDelay, () => {
        playColumnFlip(index, flipAnimationDuration)
      })

      scheduleTransition(swapDelay, () => {
        // 数据在翻转半程切换，用户看到的是“翻过去变色”，而不是平面直接跳色。
        columnModes.value[index] = nextMode
      })
    }
  }

  function setColumnRef(element: Element | ComponentPublicInstance | null, columnIndex: number) {
    if (element instanceof HTMLElement) {
      columnElements.value[columnIndex] = element
      return
    }

    const componentRoot = element && typeof element === 'object' && '$el' in element ? element.$el : null
    columnElements.value[columnIndex] = componentRoot instanceof HTMLElement ? componentRoot : null
  }

  function playColumnFlip(columnIndex: number, duration: number) {
    const element = columnElements.value[columnIndex]
    if (!element) return

    if (typeof element.animate !== 'function') {
      // Web Animations API 不可用时回退到 CSS keyframes，至少保住翻转视觉。
      playColumnFlipFallback(element, columnIndex, duration)
      return
    }

    columnAnimations.get(columnIndex)?.cancel()

    let animation: Animation

    try {
      animation = element.animate([
        { transform: 'rotateY(0deg) scale(1)', offset: 0 },
        { transform: 'rotateY(90deg) scale(0.96)', offset: 0.5 },
        { transform: 'rotateY(0deg) scale(1)', offset: 1 },
      ], {
        duration,
        easing: 'ease-in-out',
        fill: 'none',
      })
    } catch {
      // 某些环境下 animate 存在但调用失败，这里继续走 fallback，而不是静默丢动画。
      playColumnFlipFallback(element, columnIndex, duration)
      return
    }

    columnAnimations.set(columnIndex, animation)

    animation.addEventListener('finish', () => {
      if (columnAnimations.get(columnIndex) === animation) {
        columnAnimations.delete(columnIndex)
      }
    }, { once: true })

    animation.addEventListener('cancel', () => {
      if (columnAnimations.get(columnIndex) === animation) {
        columnAnimations.delete(columnIndex)
      }
    }, { once: true })
  }

  function playColumnFlipFallback(element: HTMLElement, columnIndex: number, duration: number) {
    const existingTimer = columnFallbackTimers.get(columnIndex)
    if (existingTimer) clearTimeout(existingTimer)

    element.style.animation = 'none'
    void element.offsetWidth
    element.style.animation = `activity-heatmap-col-flip ${duration}ms ease-in-out`

    const resetTimer = setTimeout(() => {
      if (columnFallbackTimers.get(columnIndex) !== resetTimer) return
      element.style.animation = ''
      columnFallbackTimers.delete(columnIndex)
    }, duration)

    columnFallbackTimers.set(columnIndex, resetTimer)
  }

  function getColumnMode(columnIndex: number) {
    return columnModes.value[columnIndex] ?? options.resolvedActiveMode.value
  }

  return {
    revealState,
    setColumnRef,
    getColumnMode,
  }
}