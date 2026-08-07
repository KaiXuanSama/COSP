/**
 * 日志列表的实时插入与游标维护。
 *
 * 两个日志视角（调用者 / 消费者）共用这段逻辑：都是按 id 倒序的游标分页瀑布流，
 * 都在收到 SSE 信号后把新行插到表顶、并从表尾砍掉同样数量以保持表长可控。
 * 抽成纯函数是因为「砍尾」与「游标」必须成对变动，两处各写一遍必然再次走偏。
 */

/** 列表行的最小约束：只要能取到 id 就能参与游标计算。 */
export interface CursorRow {
  id: number
}

/** 一次实时插入后的列表与分页状态。 */
export interface PrependResult<T extends CursorRow> {
  rows: T[]
  nextCursor: number | null
  hasMore: boolean
}

/**
 * 把新行插到表顶，从表尾砍掉同样数量，并把游标前移到新的尾行。
 *
 * <h2>为何砍尾必须连带前移游标</h2>
 * 游标语义是「下一页从 id &lt; nextCursor 处继续」，它标记的是<strong>已渲染范围的尾部
 * 边界</strong>。砍掉尾行却不动游标，那几行就落进「已渲染之外、游标之内」的缝隙：
 * 屏幕上没有，翻页也跳过，除非手动刷新否则永久消失。这是实时列表最容易出现、
 * 又最难在开发期察觉的一类空洞 —— 只有先翻页、再等新数据到来才会显形。
 *
 * 把游标设为砍尾后的新尾行 id，被砍掉的行就正好是下一页的开头，翻页即可拿回。
 *
 * <h2>为何砍尾后 hasMore 一定为 true</h2>
 * 尾部既然有行被移出视图，它们就必然还在库里等着被翻出来。原本已经到底
 * （hasMore = false）的列表，砍尾后也重新「有更多」了 —— 漏掉这一步会让
 * 「加载更多」按钮消失，被砍掉的行同样取不回来。
 *
 * <h2>保持表长而非无限增长</h2>
 * 表长固定意味着长时间挂在页面上不会让 DOM 无限膨胀；配合游标前移，
 * 「砍掉的行」与「下一页的开头」始终是同一批，用户的浏览范围因此是连续的。
 *
 * @param rows 当前已渲染的行，按 id 倒序
 * @param fresh 新到的行，按 id 倒序（最新在前）
 * @param cursor 当前游标；null 表示已到底
 * @param hasMore 当前是否还有更多
 * @returns 插入后的列表与随之更新的游标状态
 */
export function prependWithCursorShift<T extends CursorRow>(
  rows: T[],
  fresh: T[],
  cursor: number | null,
  hasMore: boolean,
): PrependResult<T> {
  if (!fresh.length) {
    return { rows, nextCursor: cursor, hasMore }
  }

  // 列表还空着：没有尾巴可砍，直接铺上，游标交由调用方从响应里取。
  if (!rows.length) {
    return { rows: [...fresh], nextCursor: cursor, hasMore }
  }

  const merged = [...fresh, ...rows]
  const kept = merged.slice(0, rows.length)
  const trimmedCount = merged.length - kept.length

  if (trimmedCount === 0) {
    return { rows: kept, nextCursor: cursor, hasMore }
  }

  // 新尾行即被砍走那批的上一条，故下一页从它之后接续，衔接处不留缝也不重叠。
  const tail = kept[kept.length - 1]
  return { rows: kept, nextCursor: tail.id, hasMore: true }
}
