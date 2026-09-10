/**
 * 首字时长（TTFB）的档位判定。
 *
 * <h2>为何抽成纯函数</h2>
 * 列表页（`views/UsageLog.vue`）与详情组件（`components/calllog/CallLogDetail.vue`）
 * 都要按同一套阈值给首字时长上色，两处各写一遍 `if (ms >= 20000)` 迟早漂移 ——
 * 与 `cacheHitRate.ts` 抽出同一份的理由相同。
 *
 * <h2>为何是三档而不是二档</h2>
 * 首字时长这一列在密集表格里反复出现，只有「正常 / 异常」两态时，
 * 25s 与 90s 会用同一种颜色，读不出「有点慢」与「严重卡住」的区别。
 * 三档让异常程度在扫视时即可分辨。
 *
 * <h2>为何用暗色而非鲜亮告警色</h2>
 * 整体是低饱和的暖色调（见 `styles/_variables.scss`）。这一列会同时出现多行着色，
 * 高饱和的红/黄会把视线从「模型」「耗时」这些主要信息上拽走。故取主题里已有的
 * 三个暗色功能色：`$warning #b89a3a`、`$accent #c27a3e`、`$danger #b84a4a`，
 * 与页面其余部分同一套色值。
 *
 * <h2>阈值的边界取法</h2>
 * 用「达到即变色」而不是「严格超过」：显示精度是 0.1s，恰好 20.0s 的行走与
 * 刚被判为告警的行在界面上无从区分，写 `>` 会让 20.0s 看起来像漏判。
 */

/** 偏慢档阈值：20 秒。 */
export const TTFB_WARN_MS = 20_000
/** 缓慢档阈值：40 秒。 */
export const TTFB_SLOW_MS = 40_000
/** 严重档阈值：60 秒。 */
export const TTFB_CRITICAL_MS = 60_000

/**
 * 首字时长档位，按严重程度升序。
 *
 * `normal` 不加任何着色 —— 它是默认态，也是「无数据」态（见下）。
 */
export type TtfbSeverity = 'normal' | 'warn' | 'slow' | 'critical'

/**
 * 判定首字时长所在档位。
 *
 * `null` / `undefined` / 负数一律归 `normal`：
 * - `null` 表示非流式调用或上游未测得，界面上显示为「—」，把破折号染成告警色没有意义；
 * - 负数是上游时钟或计算异常时的哨兵值，不该让它触发最严重档。
 *
 * @param milliseconds 首字时长（毫秒），来自 `api_call_usage.ttfb_ms`
 * @returns 档位标识；调用方据此决定是否上色
 */
export function ttfbSeverity(milliseconds: number | null | undefined): TtfbSeverity {
  if (milliseconds == null || milliseconds < 0) {
    return 'normal'
  }
  if (milliseconds >= TTFB_CRITICAL_MS) {
    return 'critical'
  }
  if (milliseconds >= TTFB_SLOW_MS) {
    return 'slow'
  }
  if (milliseconds >= TTFB_WARN_MS) {
    return 'warn'
  }
  return 'normal'
}
