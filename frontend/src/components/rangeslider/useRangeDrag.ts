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

/**
 * 拖动中的手柄。`null` 表示没有正在进行的手势。
 *
 * <p>`rail` 是个特例：它不是拖动，而是「按在轨道空白处」这个<strong>可能变成点击</strong>
 * 的手势。移动时它什么都不做（不该让块跟着指针跑 —— 用户按的是轨道，不是块），
 * 只有松手时若几乎没位移才回报一次点击。把它也纳入同一套状态机是为了让
 * 「按下 → 移动 → 松手」的记账逻辑（指针 id 匹配、位移累计、捕获释放）只有一份。
 */
export type DragTarget = 'start' | 'end' | 'range' | 'rail' | null

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
  /** 按下时的指针横坐标 —— 与 {@link maxDelta} 一起用于「这是点击还是拖动」的判定。 */
  originX: number
  /**
   * 本次手势中指针偏离起点的<strong>最大</strong>横向距离（像素）。
   *
   * <p>取最大值而非松手时的距离：拖出去再拖回来是一次拖动，
   * 若只看终点位移，那种手势会被误判成点击、松手时手柄又跳一次。
   */
  maxDelta: number
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
  /**
   * 一次<strong>点击</strong>（按下与松手之间几乎没有位移）落在了某个刻度上。
   *
   * <p>与 `onChange` 分开：点击的语义是「跳到这里」，由调用方决定怎么响应
   * （当前是 `rangeslider.jumpTo`：移动更近的那个手柄）。不给则点击不产生任何效果。
   *
   * <p>在整块（`range`）与轨道空白（`rail`）上都会触发 —— 两者对用户是同一个动作
   * 「点了轨道上的某个位置」，块只是恰好盖在那儿。端点手柄上不触发：
   * 手柄已经在那个位置了，点它没有意义。
   */
  onTickClick?: (index: number) => void
}

/**
 * 判定「点击」的位移阈值（像素）。
 *
 * <p>指针从按下到松手若始终没偏离超过这个距离，就算一次点击而非拖动。
 * 取 4px 是因为：鼠标按下时的手抖通常在 1~2px，而触屏上更大；
 * 再放宽则会把「拖了半格又拖回来」也算成点击，那时手柄会在松手瞬间跳走。
 *
 * <p>不用时间阈值：长按不动再松手，用户预期仍是「点了这里」，
 * 而按时间判定会让那种手势什么都不发生。
 */
const CLICK_SLOP_PX = 4

export function useRangeDrag(options: UseRangeDragOptions) {
  /** 正在拖动的手柄，用于给对应元素加激活态样式。 */
  const dragging = ref<DragTarget>(null)

  let state: DragState | null = null

  /**
   * 指针横坐标 → 刻度下标。
   *
   * <p>用轨道宽度而非可视宽度：两者在有滚动条或 transform 时会不同，
   * 而 `getBoundingClientRect` 给出的是变换后的实际几何，与指针坐标同一坐标系。
   *
   * <p>这里<strong>不钳到可达区间</strong>：它给出的是「指针指着哪个刻度」这个
   * 客观事实，收敛由 `moveStart` / `moveEnd` / `slideTo` 各自按手势语义完成。
   * 若在此提前钳制，整块平移的抓取偏移（{@link DragState.grabOffset}）会算错 ——
   * 那个偏移是按下时指针与区间起点的真实差值，被钳过就不再是真实差值了。
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
      originX: event.clientX,
      maxDelta: 0,
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

    // 先记位移：即使这一帧没让选择变化（同一格内移动），它也要算进拖动判定。
    state.maxDelta = Math.max(state.maxDelta, Math.abs(event.clientX - state.originX))

    // 按在轨道空白处不是拖动 —— 块不该跟着指针跑。只等松手时判点击。
    if (state.target === 'rail') return

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
   *
   * <h2>顺带判定「这其实是一次点击」</h2>
   * 在块或轨道上按下后几乎没动就松手，语义是「点了这个刻度」而非「拖了整块」——
   * 后者此时是个空操作（`slideTo` 到原位）。故在这里回报 `onTickClick`，
   * 由调用方决定怎么响应。
   *
   * <p>`pointercancel` <strong>不</strong>算点击：手势被系统中断，
   * 用户的意图未完成，此时执行跳转是替他做决定。故只在 `pointerup` 上判。
   */
  function end(event: PointerEvent): void {
    if (!state || event.pointerId !== state.pointerId) return
    const element = event.currentTarget as HTMLElement | null
    element?.releasePointerCapture?.(event.pointerId)

    const clickable = state.target === 'range' || state.target === 'rail'
    const wasClick =
      event.type === 'pointerup' &&
      clickable &&
      Math.max(state.maxDelta, Math.abs(event.clientX - state.originX)) <= CLICK_SLOP_PX

    state = null
    dragging.value = null

    if (wasClick) options.onTickClick?.(indexAt(event.clientX))
  }

  /**
   * 键盘操作。返回是否消费了该按键。
   *
   * <p>左右箭头的语义随焦点所在的手柄而变：在端点上是移动该端点（改变跨度），
   * 在区间块上是整块平移（保持跨度）—— 与鼠标拖动的语义一一对应，
   * 键盘用户得到的是同一套心智模型而非另一套。
   *
   * <p>Home / End 在整块上是「滑到最左 / 最右」，同样保持跨度。
   *
   * <p>参数不含 `'rail'`：轨道空白不可聚焦（它只是块背后的底衬），
   * 键盘用户按 Tab 只会停在块与两个端点上。
   */
  function handleKey(target: 'start' | 'end' | 'range', event: KeyboardEvent): boolean {
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
        if (target === 'start') return apply(moveStart(selection, bounds.minIndex, bounds))
        if (target === 'end') return apply(moveEnd(selection, bounds.minIndex, bounds))
        return apply(slideTo(selection, bounds.minIndex, bounds))
      case 'End':
        if (target === 'start') return apply(moveStart(selection, bounds.maxIndex, bounds))
        if (target === 'end') return apply(moveEnd(selection, bounds.maxIndex, bounds))
        // 整块靠右：起点退到「右界减去跨度」，块的右端才正好压在可达右界上。
        return apply(slideTo(selection, bounds.maxIndex - spanOf(selection) + 1, bounds))
      default:
        return false
    }
  }

  return { dragging, start, move, end, handleKey, indexAt }
}
