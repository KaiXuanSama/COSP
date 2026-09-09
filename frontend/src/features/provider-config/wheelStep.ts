/**
 * 滚轮在离散序列上的步进。
 *
 * <h2>要解决的问题</h2>
 * 「模式 | 值」控件里的两段都是离散选择：模式在清单里循环，值落在预设档位上。
 * 鼠标停在哪一段、滚轮往哪个方向，就把那一段推到相邻的一档 —— 比「点开下拉再挑一项」
 * 快得多，且不用移开视线。
 *
 * <p>难点在**非标值**：最大输出允许手填（4096、6000 这类不在预设里的值）。
 * 从 6000 往下滚该到哪？这里的答案是「该方向上最近的预设」—— 下滚到 4000、上滚到 8000，
 * 而不是先跳到某个预设再步进（那样一次滚动会走两格，手感是「打滑」）。
 *
 * <h2>方向约定</h2>
 * 滚轮向上（`deltaY < 0`）映射为 `-1`、向下为 `+1` —— 也就是**下标增量**，
 * 而不是「值变大 / 变小」。
 *
 * <p>这个选择是被模式段逼出来的：模式清单没有大小关系（`override` 与 `delete`
 * 谁「更大」无从定义），唯一稳定的语义是「清单里的上一项 / 下一项」。
 * 于是统一按下标走：向上滚 = 前一项、向下滚 = 后一项，与滚动一个列表的直觉一致。
 *
 * <p>数值段因此要求预设**按降序声明**（`128K → 4K`，正是界面展示顺序），
 * 这样下标增大恰好等于数值减小，两段的方向语义自动一致：向上滚都是往清单前面走，
 * 在数值上就表现为变大。见 {@link stepNumericPreset}。
 */

/**
 * 滚轮步进方向，语义是**下标增量**。
 *
 * `-1` = 向上滚 = 清单里的前一项；`1` = 向下滚 = 后一项。
 * 不叫「调大 / 调小」是因为模式段没有大小关系。
 */
export type StepDirection = 1 | -1

/**
 * 把 `WheelEvent.deltaY` 归一化为下标增量。
 *
 * 只取符号、不看大小：不同设备的 delta 量级差好几个数量级（鼠标滚轮一格 100+、
 * 触控板惯性滚动可能是 1），按量级换算步数会让触控板要么毫无反应、要么一次跳到底。
 * 一次事件走一格，节流交给调用方。
 *
 * <p>`deltaY` 为 0 返回 `null` —— 横向滚动不该改变纵向语义的值。
 */
export function directionFromWheel(deltaY: number): StepDirection | null {
  if (deltaY < 0) return -1
  if (deltaY > 0) return 1
  return null
}

/**
 * 在一个有序清单里按下标走一格。
 *
 * 不循环：滚到端点就停住。点击轮转是循环的（那是「换一个」的语义，四档循环很自然），
 * 而滚轮是「往这个方向推」—— 从末档滚回首档会让人以为滚过头了。
 *
 * @param current   当前值，不在清单内时返回清单首项（认不出的值当作从头开始）
 * @param items     有序清单
 * @param direction 下标增量，`-1` 前一项、`1` 后一项
 * @returns 步进后的值。已在端点则返回 `current` 本身
 */
export function stepInSequence<T>(
  current: T,
  items: readonly T[],
  direction: StepDirection,
): T {
  if (items.length === 0) return current
  const index = items.indexOf(current)
  if (index < 0) return items[0]
  const next = index + direction
  if (next < 0 || next >= items.length) return current
  return items[next]
}

/**
 * 在一组数值预设里朝某个方向走一格，非标值吸附到该方向最近的预设。
 *
 * <p>三种情形：
 * 1. **当前值正好是某个预设** → 相邻的那个预设
 * 2. **当前值在两个预设之间**（如 6000 落在 4000 与 8000 之间）→ 该方向上最近的预设
 * 3. **当前值在清单范围之外**（如 200000 大于最大预设）→ 往回走时取最近的预设，
 *    继续往外走时保持不变
 *
 * <p>情形 2 是这个函数存在的理由。若先把非标值规整到最近的预设、再步进一格，
 * 从 6000 下滚会到 4000（先规整到 8000 再降一档）—— 用户填的值被无声改掉了两次。
 *
 * <p>方向仍是下标增量：预设按降序声明，`-1`（向上滚）因此对应**更大**的数值。
 *
 * @param current   当前数值，可以不在预设里
 * @param presets   预设清单，顺序任意（内部自己排序）
 * @param direction 下标增量。降序清单下 `-1` 取更大值、`1` 取更小值
 * @returns 目标数值。该方向上已无预设时返回 `current` 本身
 */
export function stepNumericPreset(
  current: number,
  presets: readonly number[],
  direction: StepDirection,
): number {
  if (presets.length === 0) return current
  // 自己排序而非要求调用方给有序清单：这里的逻辑只关心数值大小，
  // 而调用方按界面展示顺序声明（最大输出是降序）。
  const ascending = [...presets].sort((a, b) => a - b)

  // 降序清单里下标减小 = 数值变大。
  if (direction === -1) {
    // 严格大于：等于当前值的那个预设是「原地」，不是「下一格」。
    const target = ascending.find(value => value > current)
    return target ?? current
  }
  // 从大到小找第一个严格小于当前值的。
  for (let i = ascending.length - 1; i >= 0; i -= 1) {
    if (ascending[i] < current) return ascending[i]
  }
  return current
}
