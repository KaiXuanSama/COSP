/**
 * 跟随光标的浮框定位。
 *
 * <h2>为什么共享</h2>
 * 柱状图与折线图面对的是同一个问题：浮框要贴着光标，又不能溢出容器。
 * 各写一份必然在阈值与偏移量上慢慢漂移，最终两处的手感不一致。
 *
 * <h2>为什么用阈值翻转而非测量浮框尺寸</h2>
 * 精确避让需要先知道浮框的实际宽高，而它在渲染前不可知 —— 测量就要等一帧，
 * 期间浮框会以错误位置闪现一次。改用「光标离边缘多近就翻转」的阈值判断，
 * 定位在同一帧内完成，代价只是阈值需按浮框的大致尺寸设定。
 */

/**
 * 光标与浮框之间的间距（px）。
 *
 * 不能为 0：浮框紧贴光标会盖住正在指的那个点，也容易让人以为浮框可以点击。
 */
export const CURSOR_GAP = 14

/**
 * 触发水平翻转的右缘距离（px）。
 *
 * 取略大于浮框宽度的值：光标进入这个区间后，向右展开的浮框就会溢出容器。
 */
export const EDGE_MARGIN = 170

/**
 * 触发垂直翻转的上缘距离（px）。
 *
 * 取略大于浮框高度的值。浮框默认在光标上方（不遮挡下方图形），
 * 只有当上方空间确实不足时才翻到下方。
 *
 * 这个距离量的是<strong>视口</strong>上缘而非容器上缘：浮框向上溢出卡片并不会被裁掉
 * （图表容器没有 {@code overflow: hidden}），真正看不见的情形只有溢出屏幕顶部。
 * 若按容器计算，绘图区本身只有两百来像素高，光标落在上半部分就会翻转 ——
 * 结果是「优先向下」，与设计意图相反。
 */
export const TOP_MARGIN = 120

/** 浮框相对容器的落点与展开方向。 */
export interface TooltipAnchor {
  /** 距容器左缘的像素距离。 */
  left: number
  /** 距容器上缘的像素距离。 */
  top: number
  /** 浮框向左展开（右缘对齐光标），用于光标靠近右缘时。 */
  alignEnd: boolean
  /** 浮框落在光标下方，用于光标靠近上缘时。 */
  below: boolean
}

/** 可调阈值。 */
export interface TooltipAnchorOptions {
  /** 触发水平翻转的右缘距离（px）。 */
  edgeMargin?: number
  /** 触发垂直翻转的上缘距离（px）。 */
  topMargin?: number
}

/**
 * 由光标位置算出浮框的落点。
 *
 * 坐标取<strong>容器内相对值</strong>而非视口值：浮框是容器的绝对定位子元素，
 * 用相对坐标则页面滚动、卡片位移都不会让它漂走。
 *
 * 但两个方向的<strong>翻转判断</strong>各有各的参照系：
 * <ul>
 *   <li>水平看<strong>容器</strong> —— 卡片宽度就是可用空间，浮框超出去会跑到相邻区块上；</li>
 *   <li>垂直看<strong>视口</strong> —— 图表容器没有裁剪，浮框向上盖住卡片标题是可以接受的，
 *       只有溢出屏幕顶部才真的看不见。按容器算会让绘图区上半部分全部向下弹出，
 *       与「优先向上」的意图相反。</li>
 * </ul>
 *
 * @param event 鼠标事件
 * @param container 浮框的定位上下文（须是 `position: relative` 的祖先）
 * @param options 翻转阈值
 */
export function anchorFromCursor(
  event: MouseEvent,
  container: HTMLElement,
  options: TooltipAnchorOptions = {},
): TooltipAnchor {
  const rect = container.getBoundingClientRect()
  const left = event.clientX - rect.left
  const top = event.clientY - rect.top
  const edgeMargin = options.edgeMargin ?? EDGE_MARGIN
  const topMargin = options.topMargin ?? TOP_MARGIN

  return {
    left,
    top,
    alignEnd: rect.width - left < edgeMargin,
    // 视口坐标：向上是否放得下与容器高度无关，只取决于屏幕顶部还剩多少空间
    below: event.clientY < topMargin,
  }
}
