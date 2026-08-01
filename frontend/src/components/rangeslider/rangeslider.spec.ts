/**
 * 离散范围选择模型的验证与锁定。
 *
 * 重点全在<strong>约束的相互作用</strong>上：三种手势各有不同的「谁让位」规则，
 * 而这些规则的错误几乎都不会抛异常 —— 只会让拖动手感变得别扭（块自己变宽、
 * 拖到头跳一下、拖左端把右端拽走），而单看代码很难判断哪种才对。
 */
import { describe, expect, it } from 'vitest'
import {
  clampIndex,
  clampReachable,
  indexFromRatio,
  isReachable,
  jumpTo,
  moveEnd,
  moveStart,
  normalizeSelection,
  reachableSpan,
  resolveBounds,
  selectionEquals,
  shiftBy,
  slideTo,
  spanOf,
  tickRatio,
} from './rangeslider'

/** 15 个刻度、跨度限定 7~15、全域可达 —— 概览窗口的实际配置。 */
const B = resolveBounds(15, { minSpan: 7, maxSpan: 15 })

/** 同上但左侧 4 格不可达（可达区间为下标 4~14，宽度 11）。 */
const LEFT_BLOCKED = resolveBounds(15, { minSpan: 7, maxSpan: 15, minIndex: 4 })

/** 同上但右侧 3 格不可达（可达区间为下标 0~11，宽度 12）。 */
const RIGHT_BLOCKED = resolveBounds(15, { minSpan: 7, maxSpan: 15, maxIndex: 11 })

describe('clampIndex', () => {
  it('收敛到 [0, count-1]', () => {
    expect(clampIndex(-3, 15)).toBe(0)
    expect(clampIndex(99, 15)).toBe(14)
    expect(clampIndex(7, 15)).toBe(7)
  })

  it('四舍五入到整数下标', () => {
    expect(clampIndex(3.4, 15)).toBe(3)
    expect(clampIndex(3.6, 15)).toBe(4)
  })

  it('非有限值按 0 处理，不让 NaN 顺着算式扩散', () => {
    expect(clampIndex(Number.NaN, 15)).toBe(0)
    expect(clampIndex(Number.POSITIVE_INFINITY, 15)).toBe(0)
  })

  it('count 为 0 或 1 时只有下标 0', () => {
    expect(clampIndex(5, 1)).toBe(0)
    expect(clampIndex(5, 0)).toBe(0)
  })
})

describe('resolveBounds', () => {
  it('缺省时跨度不受限、全域可达', () => {
    expect(resolveBounds(10)).toEqual({
      count: 10, minSpan: 1, maxSpan: 10, minIndex: 0, maxIndex: 9,
    })
  })

  /**
   * 上限先与下限比较、再与可达宽度比较。
   *
   * 顺序颠倒会得到 maxSpan < minSpan，此后所有约束判断相互矛盾且不报错。
   */
  it('上限低于下限时被抬到下限', () => {
    expect(resolveBounds(20, { minSpan: 10, maxSpan: 3 })).toMatchObject({
      minSpan: 10, maxSpan: 10,
    })
  })

  it('下限与上限都不超过刻度总数', () => {
    expect(resolveBounds(5, { minSpan: 9, maxSpan: 99 })).toMatchObject({
      count: 5, minSpan: 5, maxSpan: 5,
    })
  })

  it('总数至少为 1，跨度至少为 1', () => {
    expect(resolveBounds(0, { minSpan: 0, maxSpan: 0 })).toEqual({
      count: 1, minSpan: 1, maxSpan: 1, minIndex: 0, maxIndex: 0,
    })
    expect(resolveBounds(-5)).toEqual({
      count: 1, minSpan: 1, maxSpan: 1, minIndex: 0, maxIndex: 0,
    })
  })

  // ---------- 可达区间 ----------

  it('可达区间原样保留，刻度总数不变', () => {
    expect(resolveBounds(15, { minIndex: 4, maxIndex: 11 })).toMatchObject({
      count: 15, minIndex: 4, maxIndex: 11,
    })
  })

  it('可达边界被钳到刻度范围内', () => {
    expect(resolveBounds(15, { minIndex: -9, maxIndex: 99 })).toMatchObject({
      minIndex: 0, maxIndex: 14,
    })
  })

  it('倒置的可达边界被交换', () => {
    expect(resolveBounds(15, { minIndex: 11, maxIndex: 4 })).toMatchObject({
      minIndex: 4, maxIndex: 11,
    })
  })

  it('非有限值按全域处理', () => {
    expect(resolveBounds(15, { minIndex: Number.NaN, maxIndex: Number.NaN })).toMatchObject({
      minIndex: 0, maxIndex: 14,
    })
  })

  /**
   * 跨度上限收敛到<strong>可达宽度</strong>而非刻度总数。
   *
   * 15 格里只有 5 格可达时，maxSpan 不能是 15 —— 那意味着允许一个装不下的区间。
   */
  it('跨度上限不超过可达宽度', () => {
    expect(resolveBounds(15, { maxSpan: 15, minIndex: 10 })).toMatchObject({
      minSpan: 1, maxSpan: 5,
    })
  })

  /**
   * 跨度下限同样收敛到可达宽度。
   *
   * 否则会得到「至少要选 7 格，但只有 5 格可选」这种无解配置，
   * 而所有约束判断都不会报错，只会让选择在两个矛盾条件间反复被纠正。
   */
  it('跨度下限不超过可达宽度', () => {
    expect(resolveBounds(15, { minSpan: 7, minIndex: 10 })).toMatchObject({
      minSpan: 5, maxSpan: 5,
    })
  })
})

describe('reachableSpan / isReachable', () => {
  it('可达宽度含两端', () => {
    expect(reachableSpan(B)).toBe(15)
    expect(reachableSpan(LEFT_BLOCKED)).toBe(11)
    expect(reachableSpan(RIGHT_BLOCKED)).toBe(12)
  })

  it('可达判定是闭区间', () => {
    expect(isReachable(3, LEFT_BLOCKED)).toBe(false)
    expect(isReachable(4, LEFT_BLOCKED)).toBe(true)
    expect(isReachable(14, LEFT_BLOCKED)).toBe(true)
    expect(isReachable(11, RIGHT_BLOCKED)).toBe(true)
    expect(isReachable(12, RIGHT_BLOCKED)).toBe(false)
  })
})

describe('clampReachable', () => {
  it('收敛到可达区间而非刻度全域', () => {
    expect(clampReachable(0, LEFT_BLOCKED)).toBe(4)
    expect(clampReachable(9, LEFT_BLOCKED)).toBe(9)
    expect(clampReachable(99, RIGHT_BLOCKED)).toBe(11)
  })

  it('非有限值落到左界', () => {
    expect(clampReachable(Number.NaN, LEFT_BLOCKED)).toBe(4)
  })
})

describe('spanOf', () => {
  /** 闭区间：含两端。若按差值算，「7 天窗口」会变成 8 天。 */
  it('是闭区间跨度', () => {
    expect(spanOf({ start: 2, end: 8 })).toBe(7)
    expect(spanOf({ start: 3, end: 3 })).toBe(1)
  })
})

describe('tickRatio / indexFromRatio', () => {
  /**
   * 分母是 count-1：比例描述点的位置，n 个点之间有 n-1 段间隔。
   *
   * 用 count 会让最后一个点落在 (n-1)/n 处，右端凭空空出一格 ——
   * 且偏差随刻度增多而变小，很容易被当成渲染误差。
   */
  it('首尾刻度恰在 0 与 1', () => {
    expect(tickRatio(0, 15)).toBe(0)
    expect(tickRatio(14, 15)).toBe(1)
  })

  it('中点刻度在 0.5', () => {
    expect(tickRatio(2, 5)).toBe(0.5)
  })

  it('只有一个刻度时无从分布', () => {
    expect(tickRatio(0, 1)).toBe(0)
    expect(indexFromRatio(0.7, 1)).toBe(0)
  })

  it('与 indexFromRatio 互逆', () => {
    for (let i = 0; i < 15; i += 1) {
      expect(indexFromRatio(tickRatio(i, 15), 15)).toBe(i)
    }
  })

  it('越界比例收敛到两端', () => {
    expect(indexFromRatio(-0.4, 15)).toBe(0)
    expect(indexFromRatio(1.8, 15)).toBe(14)
  })
})

describe('normalizeSelection', () => {
  it('合法选择原样返回', () => {
    expect(normalizeSelection({ start: 2, end: 8 }, B)).toEqual({ start: 2, end: 8 })
  })

  it('倒置的区间被交换而非丢弃', () => {
    expect(normalizeSelection({ start: 8, end: 2 }, B)).toEqual({ start: 2, end: 8 })
  })

  /** 以 start 为锚向右扩 —— 时间轴场景下对应「保住起点，调整终点」。 */
  it('跨度不足时向右扩到下限', () => {
    expect(normalizeSelection({ start: 3, end: 4 }, B)).toEqual({ start: 3, end: 9 })
  })

  /** 右侧到底后转为向左扩，否则会算出越界的 end。 */
  it('右侧空间不足时改为向左扩', () => {
    expect(normalizeSelection({ start: 13, end: 14 }, B)).toEqual({ start: 8, end: 14 })
  })

  it('跨度超限时从右侧收', () => {
    const tight = resolveBounds(15, { minSpan: 7, maxSpan: 10 })
    expect(normalizeSelection({ start: 0, end: 14 }, tight)).toEqual({ start: 0, end: 9 })
  })

  /**
   * 刻度数量缩小后旧选择会越界 —— 这是最实际的一种失效场景
   * （窗口从 15 格切到 7 格），直接参与百分比计算会把范围块画到轨道外。
   */
  it('刻度变少后旧选择被收敛进新范围', () => {
    const small = resolveBounds(7, { minSpan: 7, maxSpan: 7 })
    expect(normalizeSelection({ start: 8, end: 14 }, small)).toEqual({ start: 0, end: 6 })
  })

  // ---------- 可达区间 ----------

  /**
   * 落在不可达区的旧选择被推进可达区。
   *
   * 这是可达区间收窄时的实际场景（后端返回的可查范围变了），
   * 不收敛的话范围块会停在灰色区里，看起来像禁用规则没生效。
   */
  it('起点落在左侧禁区时被推进可达区', () => {
    expect(normalizeSelection({ start: 0, end: 6 }, LEFT_BLOCKED)).toEqual({ start: 4, end: 10 })
  })

  it('终点落在右侧禁区时被拉回可达区', () => {
    expect(normalizeSelection({ start: 8, end: 14 }, RIGHT_BLOCKED)).toEqual({ start: 5, end: 11 })
  })

  /** 整个区间都在禁区外时同样落进可达区，且跨度补足到下限。 */
  it('整个区间在禁区外时落进可达区', () => {
    expect(normalizeSelection({ start: 0, end: 2 }, LEFT_BLOCKED)).toEqual({ start: 4, end: 10 })
  })
})

describe('moveStart', () => {
  /**
   * 右端不动，起点跟到目标。
   *
   * 起点必须留出足够空间：end=14、minSpan=7 时上界是 8，故 5 在合法区内。
   * 若拿一个已经贴着下限的区间来测（如 end=8 时 start 上界只有 2），
   * 看到的会是「顶住」而不是「跟随」—— 两种行为都对，但测的不是同一件事。
   */
  it('右端不动，起点跟到目标', () => {
    expect(moveStart({ start: 3, end: 14 }, 5, B)).toEqual({ start: 5, end: 14 })
  })

  /**
   * 顶住而非跳变：继续拖只是无效，不会把右端拽着走。
   *
   * 若在这里改动 end，用户拖左端时会看到右端一起动，
   * 那是「平移」的手感，与拖端点的语义冲突。
   */
  it('触及跨度下限时顶住', () => {
    // end=8、minSpan=7 ⇒ start 最大只能到 2
    expect(moveStart({ start: 2, end: 8 }, 7, B)).toEqual({ start: 2, end: 8 })
  })

  it('触及跨度上限时顶住', () => {
    const tight = resolveBounds(15, { minSpan: 7, maxSpan: 10 })
    // end=12、maxSpan=10 ⇒ start 最小只能到 3
    expect(moveStart({ start: 3, end: 12 }, 0, tight)).toEqual({ start: 3, end: 12 })
  })

  it('不会越出左边界', () => {
    expect(moveStart({ start: 5, end: 14 }, -9, B).start).toBe(0)
  })

  /**
   * 触及可达左界时顶住，右端不动。
   *
   * 这是本功能最核心的一条：左手柄拖进禁区应当无效，而不是把整个区间拽过去。
   */
  it('触及可达左界时顶住', () => {
    expect(moveStart({ start: 6, end: 14 }, 0, LEFT_BLOCKED)).toEqual({ start: 4, end: 14 })
    expect(moveStart({ start: 6, end: 14 }, -99, LEFT_BLOCKED)).toEqual({ start: 4, end: 14 })
  })
})

describe('moveEnd', () => {
  it('左端不动，终点跟到目标', () => {
    expect(moveEnd({ start: 2, end: 8 }, 11, B)).toEqual({ start: 2, end: 11 })
  })

  it('触及跨度下限时顶住', () => {
    // start=2、minSpan=7 ⇒ end 最小只能到 8
    expect(moveEnd({ start: 2, end: 8 }, 4, B)).toEqual({ start: 2, end: 8 })
  })

  it('触及跨度上限时顶住', () => {
    const tight = resolveBounds(15, { minSpan: 7, maxSpan: 10 })
    // start=2、maxSpan=10 ⇒ end 最大只能到 11
    expect(moveEnd({ start: 2, end: 8 }, 14, tight)).toEqual({ start: 2, end: 11 })
  })

  it('不会越出右边界', () => {
    expect(moveEnd({ start: 0, end: 6 }, 99, B).end).toBe(14)
  })

  /** 触及可达右界时顶住，左端不动。与 moveStart 镜像。 */
  it('触及可达右界时顶住', () => {
    expect(moveEnd({ start: 2, end: 9 }, 14, RIGHT_BLOCKED)).toEqual({ start: 2, end: 11 })
    expect(moveEnd({ start: 2, end: 9 }, 99, RIGHT_BLOCKED)).toEqual({ start: 2, end: 11 })
  })
})

describe('slideTo', () => {
  it('跨度不变，整块平移', () => {
    expect(slideTo({ start: 2, end: 8 }, 5, B)).toEqual({ start: 5, end: 11 })
  })

  /**
   * 撞到边界只停住、不压缩。
   *
   * 若在这里让区间收窄，用户会看到「拖到头之后块变短了」，
   * 松手再拖回来也不复原 —— 平移手势不该改变宽度。
   */
  it('撞到右边界时停住且跨度不变', () => {
    const result = slideTo({ start: 2, end: 8 }, 99, B)
    expect(result).toEqual({ start: 8, end: 14 })
    expect(spanOf(result)).toBe(7)
  })

  it('撞到左边界时同样保持跨度', () => {
    const result = slideTo({ start: 5, end: 11 }, -9, B)
    expect(result).toEqual({ start: 0, end: 6 })
    expect(spanOf(result)).toBe(7)
  })

  it('跨度铺满全轨时无处可移', () => {
    expect(slideTo({ start: 0, end: 14 }, 5, B)).toEqual({ start: 0, end: 14 })
  })

  // ---------- 可达区间 ----------

  /**
   * 撞到可达左界时停住，跨度不变。
   *
   * 这是本功能的第三条核心行为：整块拖进禁区应当停在边界，
   * 而不是被压窄（压窄就成了「拖到头之后块变短了」）。
   */
  it('撞到可达左界时停住且跨度不变', () => {
    const result = slideTo({ start: 8, end: 14 }, 0, LEFT_BLOCKED)
    expect(result).toEqual({ start: 4, end: 10 })
    expect(spanOf(result)).toBe(7)
  })

  /**
   * 撞到可达右界时停住 —— 起点上界是 `maxIndex - span + 1`，
   * 使块的<strong>右端</strong>正好压在可达右界上。
   * 若沿用 `count - span`，块的右端会伸进禁区。
   */
  it('撞到可达右界时右端正好压在界上', () => {
    const result = slideTo({ start: 0, end: 6 }, 99, RIGHT_BLOCKED)
    expect(result).toEqual({ start: 5, end: 11 })
    expect(spanOf(result)).toBe(7)
  })

  /** 可达宽度恰等于跨度时只有一个合法位置。 */
  it('可达宽度等于跨度时无处可移', () => {
    const exact = resolveBounds(15, { minSpan: 7, maxSpan: 7, minIndex: 4, maxIndex: 10 })
    expect(slideTo({ start: 4, end: 10 }, 0, exact)).toEqual({ start: 4, end: 10 })
    expect(slideTo({ start: 4, end: 10 }, 99, exact)).toEqual({ start: 4, end: 10 })
  })
})

describe('shiftBy', () => {
  it('按步数平移，跨度不变', () => {
    expect(shiftBy({ start: 2, end: 8 }, 3, B)).toEqual({ start: 5, end: 11 })
    expect(shiftBy({ start: 5, end: 11 }, -2, B)).toEqual({ start: 3, end: 9 })
  })

  it('越界时与 slideTo 同样停在边界', () => {
    expect(shiftBy({ start: 8, end: 14 }, 5, B)).toEqual({ start: 8, end: 14 })
  })
})

describe('selectionEquals', () => {
  it('逐字段比较', () => {
    expect(selectionEquals({ start: 1, end: 2 }, { start: 1, end: 2 })).toBe(true)
    expect(selectionEquals({ start: 1, end: 2 }, { start: 1, end: 3 })).toBe(false)
  })
})

/**
 * 点击跳转 —— 把<strong>更近的那个手柄</strong>移到被点的刻度上。
 *
 * <p>这组用例锁住三件事，每一件都是「不写测试就会被顺手改错」的判断：
 * <ul>
 *   <li>块外点击移哪个手柄（左侧移左、右侧移右）；</li>
 *   <li>块内点击按距离选手柄，且撞上 `minSpan` 时<strong>尽可能靠近</strong>
 *       而非拒绝整个操作 —— 后者会让块贴近下限时点击完全失效；</li>
 *   <li>正中点归<strong>左</strong>手柄（右端不动）。这是刻意的对称性破除，
 *       改成归右不会报错，只会让「锚定最近日期」这个语义悄悄反过来。</li>
 * </ul>
 */
describe('jumpTo', () => {
  /** 可达 0~10、跨度 7~15、当前选 1~9（宽 9）—— 对应「7/1~7/11 里选 7/2~7/10」。 */
  const J = resolveBounds(11, { minSpan: 7, maxSpan: 15 })
  const CURRENT = { start: 1, end: 9 }

  describe('块外点击', () => {
    it('点左侧：移左手柄，右端不动', () => {
      expect(jumpTo(CURRENT, 0, J)).toEqual({ start: 0, end: 9 })
    })

    it('点右侧：移右手柄，左端不动', () => {
      expect(jumpTo(CURRENT, 10, J)).toEqual({ start: 1, end: 10 })
    })

    /** 越界目标先被钳进可达区，再按块外规则处理。 */
    it('越界目标收敛到可达边界', () => {
      expect(jumpTo(CURRENT, -5, J)).toEqual({ start: 0, end: 9 })
      expect(jumpTo(CURRENT, 99, J)).toEqual({ start: 1, end: 10 })
    })

    /** 目标落在不可达区时收敛到可达界，而非移到灰点上。 */
    it('不可达目标收敛到可达界', () => {
      // 可达 4~14，当前 6~12；点下标 1（不可达）→ 收敛到 4
      expect(jumpTo({ start: 6, end: 12 }, 1, LEFT_BLOCKED)).toEqual({ start: 4, end: 12 })
    })
  })

  /**
   * 块内点击 —— 这是用户提出的核心场景，逐点验证。
   *
   * 当前 1~9（宽 9）、`minSpan = 7`，故左手柄最远只能到 3（`[3, 9]` 宽 7）。
   */
  describe('块内点击 · 靠左侧', () => {
    it('宽度仍大于下限时正常移动', () => {
      // [2, 9] 宽 8 > 7
      expect(jumpTo(CURRENT, 2, J)).toEqual({ start: 2, end: 9 })
    })

    it('宽度正好等于下限时允许移动', () => {
      // [3, 9] 宽 7 == minSpan
      expect(jumpTo(CURRENT, 3, J)).toEqual({ start: 3, end: 9 })
    })

    /**
     * 会突破下限时「尽可能靠近」—— 左手柄只到 3，不是原地不动。
     *
     * <p>这是本函数最容易写错的一处：直接拒绝会让块贴近下限时点击完全失效，
     * 而用户看到的是「点了没反应」。
     */
    it('会突破下限时停在能到的最远处', () => {
      // 点 4 本应得 [4, 9] 宽 6 < 7，故停在 3
      expect(jumpTo(CURRENT, 4, J)).toEqual({ start: 3, end: 9 })
    })

    /** 已在落点上时返回等价选择，调用方据此跳过多余的 emit。 */
    it('落点与当前相同时选择不变', () => {
      expect(jumpTo({ start: 3, end: 9 }, 4, J)).toEqual({ start: 3, end: 9 })
    })
  })

  describe('块内点击 · 靠右侧', () => {
    it('宽度仍大于下限时正常移动', () => {
      // 当前 1~9，点 8 → [1, 8] 宽 8
      expect(jumpTo(CURRENT, 8, J)).toEqual({ start: 1, end: 8 })
    })

    it('宽度正好等于下限时允许移动', () => {
      // [1, 7] 宽 7
      expect(jumpTo(CURRENT, 7, J)).toEqual({ start: 1, end: 7 })
    })

    it('会突破下限时停在能到的最远处', () => {
      // 当前 1~9 宽 9，中点是 5；点 6 靠右 → [1, 6] 宽 6 < 7，故停在 7
      expect(jumpTo(CURRENT, 6, J)).toEqual({ start: 1, end: 7 })
    })
  })

  /**
   * 正中点归左手柄 —— 右端不动。
   *
   * <p>跨度 9（奇数）时中点是 `start + 4 = 5`，到两端各 4 格。
   * 归左即锚定右端；日期轴上右端是「更近的日期」，是这类视图更有意义的锚点。
   *
   * <p>若改成归右，这个断言会变成 `{ start: 1, end: 5 }` —— 差异明显，
   * 不会被静默改掉。
   */
  it('正中点归左手柄（右端不动）', () => {
    // 中点 5：[5, 9] 宽 5 < 7 ⇒ 左手柄尽可能靠近，停在 3
    expect(jumpTo(CURRENT, 5, J)).toEqual({ start: 3, end: 9 })
  })

  /**
   * 跨度足够宽时正中点的归属才看得清楚。
   *
   * <p>可达 0~14、`minSpan = 3`、当前 2~12（宽 11，中点 7）：
   * 归左得 `[7, 12]`，归右会得 `[2, 7]`。
   */
  it('宽块的正中点：左手柄跳到中点，右端不动', () => {
    const wide = resolveBounds(15, { minSpan: 3, maxSpan: 15 })
    expect(jumpTo({ start: 2, end: 12 }, 7, wide)).toEqual({ start: 7, end: 12 })
  })

  /** 点在端点上时该手柄原地不动，选择不变。 */
  it('点在端点上时选择不变', () => {
    expect(jumpTo(CURRENT, 1, J)).toEqual(CURRENT)
    expect(jumpTo(CURRENT, 9, J)).toEqual(CURRENT)
  })

  /**
   * 撞上 `maxSpan` 时同样顺着 `moveStart` / `moveEnd` 的约束顶住。
   *
   * <p>当前配置里 `maxSpan == 刻度数` 所以碰不到，但那是配置的巧合而非保证 ——
   * 这条锁住「跨度上限也生效」，免得将来收紧上限时点击能拖出超宽区间。
   */
  it('撞上跨度上限时顶住', () => {
    const capped = resolveBounds(15, { minSpan: 3, maxSpan: 6 })
    // 当前 8~13（宽 6，已达上限），点 2 → 左手柄最远只能到 8
    expect(jumpTo({ start: 8, end: 13 }, 2, capped)).toEqual({ start: 8, end: 13 })
  })

  /**
   * 单点配置下点击不产生位移。
   *
   * <p>模型层如此，渲染层也据此关掉点击跳转（`clickToJumpActive`）——
   * 两条约束把端点夹死，任何目标都会被拒回原值。
   */
  it('单点配置下选择不变', () => {
    const S = resolveBounds(15, { minSpan: 1, maxSpan: 1 })
    expect(jumpTo({ start: 5, end: 5 }, 9, S)).toEqual({ start: 5, end: 5 })
  })
})

/**
 * 单点形态 —— `minSpan = maxSpan = 1`，选择退化成「一个位置」。
 *
 * <p>组件在这个配置下不渲染端点手柄（`moveStart` / `moveEnd` 被两条约束夹死，
 * 任何目标都会被拒回原值），整块拖动走 `slideTo`。这组用例锁住模型层的行为：
 * 「端点动不了」是<strong>正确</strong>的，「整块能动」也是正确的，
 * 两者一起决定了渲染层必须去掉手柄。
 */
describe('单点形态', () => {
  const S = resolveBounds(15, { minSpan: 1, maxSpan: 1 })
  const SR = resolveBounds(15, { minSpan: 1, maxSpan: 1, minIndex: 4, maxIndex: 11 })

  it('跨度恒为 1', () => {
    expect(S.minSpan).toBe(1)
    expect(S.maxSpan).toBe(1)
  })

  it('超宽的选择被收成单点', () => {
    expect(normalizeSelection({ start: 5, end: 9 }, S)).toEqual({ start: 5, end: 5 })
  })

  /**
   * 端点<strong>动不了</strong> —— 这不是 bug，是两条约束的必然结果。
   *
   * `minSpan = maxSpan = 1` 时 `moveStart` 的上下界都等于 `end`，
   * 任何目标都被夹回原位。渲染层据此不渲染手柄，否则用户会遇到一个死区。
   */
  it('端点无法移动', () => {
    expect(moveStart({ start: 5, end: 5 }, 2, S)).toEqual({ start: 5, end: 5 })
    expect(moveStart({ start: 5, end: 5 }, 9, S)).toEqual({ start: 5, end: 5 })
    expect(moveEnd({ start: 5, end: 5 }, 9, S)).toEqual({ start: 5, end: 5 })
    expect(moveEnd({ start: 5, end: 5 }, 2, S)).toEqual({ start: 5, end: 5 })
  })

  /** 整块平移正常 —— 这是单点形态下唯一有效的手势。 */
  it('整块平移把点移到目标', () => {
    expect(slideTo({ start: 5, end: 5 }, 9, S)).toEqual({ start: 9, end: 9 })
    expect(slideTo({ start: 5, end: 5 }, 0, S)).toEqual({ start: 0, end: 0 })
  })

  it('整块平移撞界停住', () => {
    expect(slideTo({ start: 5, end: 5 }, 99, S)).toEqual({ start: 14, end: 14 })
    expect(slideTo({ start: 5, end: 5 }, -99, S)).toEqual({ start: 0, end: 0 })
  })

  /** 键盘步进（走 shiftBy → slideTo）同样有效。 */
  it('按步数平移有效', () => {
    expect(shiftBy({ start: 5, end: 5 }, 3, S)).toEqual({ start: 8, end: 8 })
    expect(shiftBy({ start: 5, end: 5 }, -5, S)).toEqual({ start: 0, end: 0 })
  })

  // ---------- 与可达区间的配合 ----------

  it('可达区间同样约束单点', () => {
    expect(slideTo({ start: 6, end: 6 }, 99, SR)).toEqual({ start: 11, end: 11 })
    expect(slideTo({ start: 6, end: 6 }, 0, SR)).toEqual({ start: 4, end: 4 })
  })

  it('落在禁区的单点被推回可达区', () => {
    expect(normalizeSelection({ start: 0, end: 0 }, SR)).toEqual({ start: 4, end: 4 })
    expect(normalizeSelection({ start: 14, end: 14 }, SR)).toEqual({ start: 11, end: 11 })
  })

  /** 单点在可达区间内可自由移动到任一可达位置。 */
  it('可达区间内每个位置都能到达', () => {
    for (let target = SR.minIndex; target <= SR.maxIndex; target += 1) {
      expect(slideTo({ start: 6, end: 6 }, target, SR)).toEqual({ start: target, end: target })
    }
  })
})

describe('不变量', () => {
  /**
   * 任何操作的结果都必须满足边界约束。
   *
   * 这一条比逐个用例更有价值：它挡住的是「某条分支忘了钳制」这类遗漏，
   * 而那种遗漏通常只在特定起点上才暴露。
   *
   * 三种可达配置一起跑：全域、左侧禁、右侧禁。加了可达区间之后，
   * 「结果必须落在 [minIndex, maxIndex] 内」成了新的必查项 ——
   * 只断言落在 [0, count-1] 内会让禁区越界悄悄通过。
   */
  it('三种操作在所有起点、目标与可达配置下都产出合法区间', () => {
    for (const bounds of [B, LEFT_BLOCKED, RIGHT_BLOCKED]) {
      for (let start = 0; start < 15; start += 1) {
        for (let target = -3; target <= 17; target += 1) {
          const base = normalizeSelection({ start, end: start + 6 }, bounds)
          for (const next of [
            moveStart(base, target, bounds),
            moveEnd(base, target, bounds),
            slideTo(base, target, bounds),
          ]) {
            expect(next.start).toBeGreaterThanOrEqual(bounds.minIndex)
            expect(next.end).toBeLessThanOrEqual(bounds.maxIndex)
            expect(next.start).toBeLessThanOrEqual(next.end)
            expect(spanOf(next)).toBeGreaterThanOrEqual(bounds.minSpan)
            expect(spanOf(next)).toBeLessThanOrEqual(bounds.maxSpan)
          }
        }
      }
    }
  })

  /** 平移永不改变跨度 —— 单独立一条，因为它是三种手势里唯一的宽度不变量。 */
  it('平移在任何目标与可达配置下都保持跨度', () => {
    for (const bounds of [B, LEFT_BLOCKED, RIGHT_BLOCKED]) {
      for (const span of [7, 10, 11]) {
        if (span > reachableSpan(bounds)) continue
        const base = normalizeSelection(
          { start: bounds.minIndex, end: bounds.minIndex + span - 1 },
          bounds,
        )
        for (let target = -5; target <= 20; target += 1) {
          expect(spanOf(slideTo(base, target, bounds))).toBe(spanOf(base))
        }
      }
    }
  })

  /**
   * 禁区永不被触及 —— 把「可达」这个语义本身立成一条不变量。
   *
   * 上一条已隐含此意，但那是按 bounds 字段断言；这一条改用 `isReachable`
   * 逐个下标校验，两者若因某次重构而分叉（比如 clampReachable 改错了边界含义），
   * 会在这里暴露。
   */
  it('任何操作结果的两端都是可达位置', () => {
    for (const bounds of [LEFT_BLOCKED, RIGHT_BLOCKED]) {
      for (let target = -5; target <= 20; target += 1) {
        const base = normalizeSelection({ start: 0, end: 6 }, bounds)
        for (const next of [
          moveStart(base, target, bounds),
          moveEnd(base, target, bounds),
          slideTo(base, target, bounds),
          shiftBy(base, target, bounds),
        ]) {
          expect(isReachable(next.start, bounds)).toBe(true)
          expect(isReachable(next.end, bounds)).toBe(true)
        }
      }
    }
  })
})
