import { describe, expect, it } from 'vitest'
import {
  dayOffsetBetween,
  isClockAligned,
  resolveSelectableAxis,
  type UsageDateRangeMeta,
} from './dateRange'

/**
 * 验证与锁定：后端日期范围 → 选择器轴约束的换算。
 *
 * 三条主线，每条都对应一个「不写测试就会被顺手改掉」的决策：
 * <ul>
 *   <li><strong>只取下界</strong> —— 上界恒为今天，与 `latestDate` 无关。
 *       今天没有调用不等于今天不可选。</li>
 *   <li><strong>不足 7 天时收缩 minSpan</strong> —— 否则「至少选 7 天」与
 *       「只有 3 天可达」互相矛盾。</li>
 *   <li><strong>未加载 / 脏数据时放开整个池</strong> —— 而不是塌成一个点。</li>
 * </ul>
 */

/** 固定「今天」：2026-07-31。用本地构造以避免时区偏移。 */
const TODAY = new Date(2026, 6, 31, 14, 30)

const POOL_DAYS = 15
const PREFERRED_SPAN = 7

function axis(meta: UsageDateRangeMeta | null) {
  return resolveSelectableAxis(meta, TODAY, {
    preferredSpan: PREFERRED_SPAN,
    poolDays: POOL_DAYS,
  })
}

/** 构造响应体。`latest*` 给出与预期无关的值，用来证明它们确实没被读。 */
function meta(overrides: Partial<UsageDateRangeMeta> = {}): UsageDateRangeMeta {
  return {
    earliestDate: '2026-07-20',
    latestDate: '2026-07-30',
    today: '2026-07-31',
    hasData: true,
    earliestSelectable: '2026-07-20',
    latestSelectable: '2026-07-31',
    ...overrides,
  }
}

describe('dayOffsetBetween', () => {
  it('同一天为 0，无论时刻', () => {
    expect(dayOffsetBetween(new Date(2026, 6, 31, 23, 59), new Date(2026, 6, 31, 0, 0))).toBe(0)
  })

  it('更早的目标为负', () => {
    expect(dayOffsetBetween(TODAY, new Date(2026, 6, 20))).toBe(-11)
  })

  it('更晚的目标为正', () => {
    expect(dayOffsetBetween(TODAY, new Date(2026, 7, 2))).toBe(2)
  })

  it('跨月正确', () => {
    expect(dayOffsetBetween(new Date(2026, 7, 2), new Date(2026, 6, 31))).toBe(-2)
  })
})

describe('resolveSelectableAxis · 软墙位置', () => {
  /** 07-20 距 07-31 十一天，故软墙在 −11。 */
  it('按最早可选日期算出软墙位置', () => {
    expect(axis(meta())).toMatchObject({ reachableStart: -11, selectableDays: 12 })
  })

  /** 只有今天有数据时软墙就在今天，可选一天。 */
  it('仅今天可选时软墙为 0', () => {
    expect(axis(meta({ earliestSelectable: '2026-07-31' })))
      .toMatchObject({ reachableStart: 0, selectableDays: 1 })
  })

  /**
   * 比池宽更早的下界收敛到池左端。
   *
   * <p>那些位置根本不在池内，且后端的可查窗口也限制在这个深度内。
   */
  it('早于池左端的下界被收敛', () => {
    expect(axis(meta({ earliestSelectable: '2026-01-01' })))
      .toMatchObject({ reachableStart: -(POOL_DAYS - 1), selectableDays: POOL_DAYS })
  })

  /** 恰好落在池左端时不被改动。 */
  it('恰好落在池左端时保持原值', () => {
    expect(axis(meta({ earliestSelectable: '2026-07-17' })))
      .toMatchObject({ reachableStart: -14, selectableDays: 15 })
  })

  /**
   * 服务端下界晚于浏览器今天时轴不会反向。
   *
   * <p>时区差或时钟不同步会造成这种情况。反向的可达区间会让可达点集合为空，
   * 选择器整体变成不可操作，且没有任何报错。
   */
  it('晚于今天的下界钳回 0', () => {
    expect(axis(meta({ earliestSelectable: '2026-08-05' })))
      .toMatchObject({ reachableStart: 0, selectableDays: 1 })
  })
})

describe('resolveSelectableAxis · 上界恒为今天', () => {
  /**
   * `latestDate` 早于今天时轴上界仍是今天。
   *
   * <p>本函数不返回上界字段 —— 上界恒为 0 由调用方直接写死。
   * 这个测试通过「改 latestDate 不影响任何返回值」来锁住那个决策：
   * 若将来有人把上界读进来，这里必然出现差异。
   */
  it('latestDate 早于今天时不影响任何返回值', () => {
    const stale = axis(meta({ latestDate: '2026-07-25', latestSelectable: '2026-07-25' }))
    const fresh = axis(meta({ latestDate: '2026-07-31', latestSelectable: '2026-07-31' }))
    expect(stale).toEqual(fresh)
  })

  /** `latestSelectable` 被篡改成很早的日期也一样不被读取。 */
  it('latestSelectable 被篡改也不影响结果', () => {
    expect(axis(meta({ latestSelectable: '2026-01-01' })))
      .toEqual(axis(meta()))
  })
})

describe('resolveSelectableAxis · 跨度下限', () => {
  /** 可选天数充足时保持 7 天，与后端的窗口下限一致。 */
  it('可选天数 >= 7 时用 7', () => {
    expect(axis(meta({ earliestSelectable: '2026-07-25' })).minSpan).toBe(7)
  })

  /** 恰好 7 天时仍是 7。 */
  it('可选天数恰为 7 时用 7', () => {
    const result = axis(meta({ earliestSelectable: '2026-07-25' }))
    expect(result.selectableDays).toBe(7)
    expect(result.minSpan).toBe(7)
  })

  /**
   * 不足 7 天时收缩为实际可选天数。
   *
   * <p>不收缩的话「至少选 7 天」与「只有 3 天可达」互相矛盾，
   * 块会在两个条件间反复被纠正。
   */
  it('可选天数不足 7 时收缩为实际天数', () => {
    const result = axis(meta({ earliestSelectable: '2026-07-29' }))
    expect(result.selectableDays).toBe(3)
    expect(result.minSpan).toBe(3)
  })

  /** 只有今天可选时收缩到 1 —— 区间形态退化为单格。 */
  it('仅今天可选时收缩为 1', () => {
    expect(axis(meta({ earliestSelectable: '2026-07-31' })).minSpan).toBe(1)
  })
})

describe('resolveSelectableAxis · 兜底', () => {
  /**
   * 未加载时放开整个池。
   *
   * <p>不塌成一个点：那会让选择器先收缩、拿到响应后再展开，
   * 闪一下比慢一点更刺眼。
   */
  it('meta 为 null 时放开整个池', () => {
    expect(axis(null)).toEqual({
      reachableStart: -(POOL_DAYS - 1),
      selectableDays: POOL_DAYS,
      minSpan: PREFERRED_SPAN,
      hasData: false,
    })
  })

  /** 畸形日期串按未加载处理，但保留 hasData 供文案使用。 */
  it('畸形日期串放开整个池', () => {
    const result = axis(meta({ earliestSelectable: 'not-a-date' }))
    expect(result.reachableStart).toBe(-(POOL_DAYS - 1))
    expect(result.minSpan).toBe(PREFERRED_SPAN)
    expect(result.hasData).toBe(true)
  })

  /**
   * 越界的日历日（`2026-02-31`）也被拒绝。
   *
   * <p>正则拦不住这种值 —— `new Date(2026, 1, 31)` 会静默滚到 3 月 3 日，
   * 软墙随即偏移两天而毫无迹象。
   */
  it('越界日历日被拒绝', () => {
    expect(axis(meta({ earliestSelectable: '2026-02-31' })).reachableStart)
      .toBe(-(POOL_DAYS - 1))
  })

  /** 空库时 hasData 为 false，可选范围仍是后端给的那一天（今天）。 */
  it('空库时只有今天可选', () => {
    const result = axis(meta({
      earliestDate: null,
      latestDate: null,
      hasData: false,
      earliestSelectable: '2026-07-31',
    }))
    expect(result).toEqual({
      reachableStart: 0,
      selectableDays: 1,
      minSpan: 1,
      hasData: false,
    })
  })

  /** 池宽为 0 之类的非法配置被收敛，不产生 NaN。 */
  it('非法池宽被收敛为 1', () => {
    const result = resolveSelectableAxis(meta(), TODAY, { preferredSpan: 7, poolDays: 0 })
    expect(result).toMatchObject({ reachableStart: 0, selectableDays: 1, minSpan: 1 })
  })
})

describe('isClockAligned', () => {
  it('两端同一天时为 true', () => {
    expect(isClockAligned(meta(), TODAY)).toBe(true)
  })

  it('服务端日期不同时为 false', () => {
    expect(isClockAligned(meta({ today: '2026-07-30' }), TODAY)).toBe(false)
  })

  /** 无法判定时不误报 —— 未加载不是「时钟不一致」。 */
  it('meta 为 null 时为 true', () => {
    expect(isClockAligned(null, TODAY)).toBe(true)
  })
})
