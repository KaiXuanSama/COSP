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
      resetColumnState(true)
      initializeColumns(visibleLength, nextMode)
      return
    }

    if (!hasPendingTransitions() && columnModes.value.every((mode) => mode === nextMode)) return

    if (revealState.value === 'pending') {
      startInitialReveal()
    }

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

      columnReadyAt.value[index] = startAt + flipAnimationDuration

      scheduleTransition(startDelay, () => {
        playColumnFlip(index, flipAnimationDuration)
      })

      scheduleTransition(swapDelay, () => {
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