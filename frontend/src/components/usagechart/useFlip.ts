import { nextTick, type Ref } from 'vue'

/**
 * FLIP 位移动画。
 *
 * <h2>要解决什么</h2>
 * 柱子的横向位置由 flex 布局决定（`justify-content: space-around` + `flex: 1`）。
 * 层级切换时柱子数量与顺序都会变，布局重排是<strong>瞬时</strong>的 ——
 * 柱子会直接"跳"到新位置。CSS transition 对此无能为力：
 * 它只能过渡具体属性值，而这里没有任何属性在变，变的是布局结果本身。
 *
 * <h2>FLIP 是什么</h2>
 * First-Last-Invert-Play 四步：
 * <ol>
 *   <li><b>First</b> —— 变更前记录每个元素的位置；</li>
 *   <li><b>Last</b> —— 让 DOM 更新到新状态，读取新位置；</li>
 *   <li><b>Invert</b> —— 用 transform 把元素"拉回"旧位置，视觉上仿佛没动；</li>
 *   <li><b>Play</b> —— 清除 transform 并开启过渡，元素平滑滑向新位置。</li>
 * </ol>
 * 关键在于只动 {@code transform}，不触发重排，动画全程走合成层，性能稳定。
 *
 * <h2>为何不用 TransitionGroup</h2>
 * Vue 的 {@code TransitionGroup} 自带 FLIP，但它只处理"同一列表内元素增删移动"。
 * 这里跨层级时整批柱子的身份都换了（日期 → 供应商 → 模型），
 * 在它看来是"全部删除 + 全部新增"，不会产生移动过渡。
 * 我们要的恰是"旧柱子滑到新柱子该在的地方"，故自行实现。
 */

/** 一次 FLIP 的可调参数。 */
export interface FlipOptions {
  /** 位移过渡时长（ms）。 */
  duration?: number
  /** 过渡缓动曲线。 */
  easing?: string
  /**
   * 元素身份属性名。
   *
   * 取值相同即视为「同一个元素」，其位移才会被补偿；
   * 新出现的元素没有旧位置可比，直接落位。
   */
  keyAttr?: string
}

const DEFAULT_DURATION = 420
/** 与柱高过渡同一条曲线，使"长高"和"横移"看起来是同一个动作。 */
const DEFAULT_EASING = 'cubic-bezier(0.4, 0, 0.2, 1)'
const DEFAULT_KEY_ATTR = 'data-flip-key'

/** 元素身份 -> 变更前的视口坐标。 */
type PositionMap = Map<string, DOMRect>

/**
 * 创建一个 FLIP 播放器。
 *
 * 分两段调用：数据变更前 {@code snapshot()}，DOM 更新后 {@code play()}。
 * 拆成两段而非一个 async 方法，是为了让调用方能拿到"是否有柱子留存"这个信号 ——
 * 它决定该走 FLIP 滑动还是重播入场动画，二者不能同时进行。
 *
 * @param getContainer 取容器元素（其后代中匹配 selector 的元素参与动画）
 * @param selector 参与动画的元素选择器
 * @param options 时长、缓动与身份属性名
 */
export function useFlip(
  getContainer: () => HTMLElement | null,
  selector: string,
  options: FlipOptions = {},
) {
  const duration = options.duration ?? DEFAULT_DURATION
  const easing = options.easing ?? DEFAULT_EASING
  const keyAttr = options.keyAttr ?? DEFAULT_KEY_ATTR

  /** 上一次 snapshot 记下的位置，play 时用它算位移。 */
  let firstPositions: PositionMap = new Map()

  /** 收集容器内所有参与 FLIP 的元素。 */
  function collect(): HTMLElement[] {
    const root = getContainer()
    if (!root) return []
    return Array.from(root.querySelectorAll<HTMLElement>(selector))
  }

  /**
   * First —— 记录变更前的位置。
   *
   * 必须在数据变化引起 DOM 更新<strong>之前</strong>调用（即 watch 回调开头、
   * await nextTick() 之前），此时读到的仍是旧布局。
   */
  function snapshot(): void {
    firstPositions = new Map()
    for (const el of collect()) {
      const key = el.getAttribute(keyAttr)
      if (key) firstPositions.set(key, el.getBoundingClientRect())
    }
  }

  /**
   * Last / Invert / Play —— 补偿位移并播放。
   *
   * 须在 DOM 已更新后调用（await nextTick() 之后）。
   *
   * @returns 是否有元素跨越了本次变更（即存在身份留存的柱子）。
   *   调用方据此决定要不要重播入场动画：有留存就交给 FLIP 滑动，
   *   若重播入场，这些柱子会先归零再长高，滑动的连续感就断了。
   */
  function play(): boolean {
    // 首次渲染没有基线，交给入场动画
    if (firstPositions.size === 0) return false

    const moved: Array<{ el: HTMLElement; dx: number; dy: number }> = []
    /** 身份在变更前后都存在的元素数量 —— 决定返回值，与是否真的位移无关。 */
    let survived = 0

    for (const el of collect()) {
      const key = el.getAttribute(keyAttr)
      if (!key) continue
      const before = firstPositions.get(key)
      // 新出现的元素没有可比位置，直接落位
      if (!before) continue

      survived += 1

      const after = el.getBoundingClientRect()
      const dx = before.left - after.left
      const dy = before.top - after.top
      // 亚像素抖动不值得动画，避免无谓的合成层开销
      if (Math.abs(dx) < 0.5 && Math.abs(dy) < 0.5) continue

      moved.push({ el, dx, dy })
    }

    firstPositions = new Map()

    // 尊重系统设置：跳过滑动，但仍视为"有留存"，避免退化成整批重播入场
    if (prefersReducedMotion()) return survived > 0
    if (!moved.length) return survived > 0

    // Invert：先抹掉过渡再位移，确保元素"瞬间"回到旧位置而不是滑过去
    for (const { el, dx, dy } of moved) {
      el.style.transition = 'none'
      el.style.transform = `translate(${dx}px, ${dy}px)`
    }

    // 强制浏览器应用上面的初始状态，否则下一步的清除会被合并成"无变化"
    forceReflow(getContainer())

    // Play：开启过渡并回到自然位置
    for (const { el } of moved) {
      el.style.transition = `transform ${duration}ms ${easing}`
      el.style.transform = ''
    }

    // 过渡结束后清理内联样式，避免残留影响后续布局与 hover 效果
    setTimeout(() => {
      for (const { el } of moved) {
        el.style.transition = ''
        el.style.transform = ''
      }
    }, duration)

    return true
  }

  return { snapshot, play }
}

/** 读取一次布局属性以强制同步重排，使刚写入的样式立即生效。 */
function forceReflow(el: HTMLElement | null): void {
  if (el) void el.offsetWidth
}

/**
 * 尊重系统「减少动态效果」设置：开启时跳过位移动画，直接落位。
 *
 * 无 window 或不支持 matchMedia 时一律视为「不需要减少动效」，
 * 使本模块在非浏览器环境（SSR、单测）下也能安全求值而不抛错。
 */
function prefersReducedMotion(): boolean {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return false
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches
}
