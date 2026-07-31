/**
 * 离散范围滑块的拖动手势。
 *
 * ## 为什么单独成一个组合式函数
 * 拖动涉及三件与渲染无关的事：把指针坐标折算成刻度下标、在拖动期间接管全局事件、
 * 以及拖动结束后的清理。把它们从组件里拆出来，组件只剩「画出来 + 转发选择变化」，
 * 而这三件事各自都有明确的失败模式，值得独立表达。
 *
 * ## 用 Pointer Events 而非 mouse + touch
 * 一套事件同时覆盖鼠标、触屏与手写笔；配合 `setPointerCapture`，
 * 指针移出元素甚至移出窗口后事件仍会送达捕获者 —— 拖到轨道外再松手不会「粘住」。
 * 若用 mousemove，必须自己往 document 上挂监听并小心卸载，
 * 且触屏要另写一套 touch 分支。
 *
 * ## 折算始终基于轨道矩形
 * 每次移动都重新读一次 `getBoundingClientRect()`：面板可能在拖动期间因布局变化
 * 而移动（滚动、卡片展开）。缓存矩形会让指针与手柄逐渐错位，
 * 而这种错位只在特定交互序列下出现，极难复现。
 */
import { ref, type Ref } from 'vue'
import {
  indexFromRatio,
  moveEnd,
  moveStart,
  slideTo,
  spanOf,
  type RangeBounds,
  type RangeSelection,
} from './rangeslider'

/** 拖动中的手柄。`null` 表示没有正在进行的拖动。 */
export type DragTarget = 'start' | 'end' | 'range' | null

interface DragState {
  target: Exclude<DragTarget, null>
  pointerId: number
  /**
   * 按下时指针所在的刻度与区间起点的差。
   *
   * 只有整块平移需要它：拖动块中部时，用户预期块跟着指针走而<strong>不跳到指针下</strong>。
   * 少了这个偏移，按下的瞬间块会自己弹一下，让起点对齐指针。
   */
  grabOffset: number
}

export interface UseRangeDragOptions {
  /** 轨道元素 —— 比例折算的参照系，必须是离散点实际分布的那个盒子。 */
  trackRef: Ref<HTMLElement | null>
  /** 当前边界约束。取函数而非值，使拖动期间边界变化也能被看到。 */
  bounds: () => RangeBounds
  /** 当前选择。同样取函数，避免闭包捕获旧值。 */
  selection: () => RangeSelection
  /** 请求应用新选择。是否真的采纳由调用方决定（受控组件）。 */
  onChange: (next: RangeSelection) => void
}

export function useRangeDrag(options: UseRangeDragOptions) {
  /** 正在拖动的手柄，用于给对应元素加激活态样式。 */
  const dragging = ref<DragTarget>(null)

  let state: DragState | null = null

  /**
   * 指针横坐标 → 刻度下标。
   *
   * <p>用轨道宽度而非可视宽度：两者在有滚动条或 transform 时会不同，
   * 而 `getBoundingClientRect` 给出的是变换后的实际几何，与指针坐标同一坐标系。
   */
  function indexAt(clientX: number): number {
    const track = options.trackRef.value
    const { count } = options.bounds()
    if (!track) return 0
    const rect = track.getBoundingClientRect()
    if (rect.width <= 0) return 0
    return indexFromRatio((clientX - rect.left) / rect.width, count)
  }

  /** 开始拖动。返回是否真的接管了这个指针。 */
  function start(target: Exclude<DragTarget, null>, event: PointerEvent): boolean {
    // 只响应主按钮：右键与中键在这里没有语义，接管它们会吞掉浏览器默认行为。
    if (event.button !== 0) return false

    const selection = options.selection()
    state = {
      target,
      pointerId: event.pointerId,
      grabOffset: target === 'range' ? indexAt(event.clientX) - selection.start : 0,
    }
    dragging.value = target

    // 捕获指针：此后 move / up 都送到这个元素，即使指针已移出它的边界。
    const element = event.currentTarget as HTMLElement | null
    element?.setPointerCapture?.(event.pointerId)
    return true
  }

  /** 拖动中。非当前指针的事件被忽略 —— 多点触控下另一根手指不该干扰。 */
  function move(event: PointerEvent): void {
    if (!state || event.pointerId !== state.pointerId) return

    const bounds = options.bounds()
    const selection = options.selection()
    const index = indexAt(event.clientX)

    if (state.target === 'start') {
      options.onChange(moveStart(selection, index, bounds))
      return
    }
    if (state.target === 'end') {
      options.onChange(moveEnd(selection, index, bounds))
      return
    }
    options.onChange(slideTo(selection, index - state.grabOffset, bounds))
  }

  /**
   * 结束拖动。
   *
   * <p>`pointercancel` 同样走这里 —— 系统中断手势（来电、切应用）时若不清理，
   * `dragging` 会永久停在激活态，之后所有移动都被当成拖动。
   */
  function end(event: PointerEvent): void {
    if (!state || event.pointerId !== state.pointerId) return
    const element = event.currentTarget as HTMLElement | null
    element?.releasePointerCapture?.(event.pointerId)
    state = null
    dragging.value = null
  }

  /**
   * 键盘操作。返回是否消费了该按键。
   *
   * <p>左右箭头的语义随焦点所在的手柄而变：在端点上是移动该端点（改变跨度），
   * 在区间块上是整块平移（保持跨度）—— 与鼠标拖动的语义一一对应，
   * 键盘用户得到的是同一套心智模型而非另一套。
   *
   * <p>Home / End 在整块上是「滑到最左 / 最右」，同样保持跨度。
   */
  function handleKey(target: Exclude<DragTarget, null>, event: KeyboardEvent): boolean {
    const bounds = options.bounds()
    const selection = options.selection()
    const step = event.shiftKey ? 5 : 1

    const apply = (next: RangeSelection) => {
      options.onChange(next)
      return true
    }

    switch (event.key) {
      case 'ArrowLeft':
        if (target === 'start') return apply(moveStart(selection, selection.start - step, bounds))
        if (target === 'end') return apply(moveEnd(selection, selection.end - step, bounds))
        return apply(slideTo(selection, selection.start - step, bounds))
      case 'ArrowRight':
        if (target === 'start') return apply(moveStart(selection, selection.start + step, bounds))
        if (target === 'end') return apply(moveEnd(selection, selection.end + step, bounds))
        return apply(slideTo(selection, selection.start + step, bounds))
      case 'Home':
        if (target === 'start') return apply(moveStart(selection, 0, bounds))
        if (target === 'end') return apply(moveEnd(selection, 0, bounds))
        return apply(slideTo(selection, 0, bounds))
      case 'End':
        if (target === 'start') return apply(moveStart(selection, bounds.count - 1, bounds))
        if (target === 'end') return apply(moveEnd(selection, bounds.count - 1, bounds))
        return apply(slideTo(selection, bounds.count - spanOf(selection), bounds))
      default:
        return false
    }
  }

  return { dragging, start, move, end, handleKey, indexAt }
}
