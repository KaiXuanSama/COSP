/**
 * 消费记录列表的两种模式与在途请求作废。
 *
 * 实时模式：无条件拉取，SSE 信号触发增量插入。
 * 检索模式：带条件拉取，SSE 信号直接丢弃（ADR-0020），只有手动刷新会重新拉。
 *
 * <h2>为何需要「列表代次」</h2>
 * 切换模式或修改条件的那一刻，可能还有请求在路上：实时模式的增量同步、
 * 上一组条件的翻页、连续两次检索中先发的那次。它们返回时如果直接写进列表，
 * 列表里就会出现不符合当前条件的行，或者游标指向另一组结果。
 * 每次列表「换了一份」时代次加一，请求发起时记下代次，返回时不一致就丢弃，
 * 一处判断覆盖所有这类竞态，不需要逐个请求去取消。
 *
 * 不依赖 Vue 与 DOM，便于用受控时序测试。
 */

import type { LogQueryParams } from './searchFilter'

export type LogListMode = 'live' | 'search'

export interface LogListSession {
  readonly mode: LogListMode
  /** 当前检索参数；实时模式下为 undefined。 */
  readonly params: LogQueryParams | undefined
  readonly generation: number
  /** 进入检索模式，或在检索模式下换一组条件。 */
  enterSearch(params: LogQueryParams): void
  /** 回到实时模式。 */
  exitSearch(): void
  /** 模式不变、整表重载（手动刷新）。同样作废在途请求，例如旧列表的翻页。 */
  invalidate(): void
  /** SSE 信号是否应触发增量同步。 */
  acceptsLiveSignal(): boolean
  /**
   * 以当前参数发起一次拉取；返回时若列表已换代则丢弃结果。
   *
   * 参数在发起时捕获并传给 fetch，所以「用哪组条件拉」和「结果能否落地」
   * 判断的是同一代，不会出现用旧条件拉、按新条件落地的错配。
   *
   * @returns 结果是否已应用。过期请求的失败同样被吞掉 —— 它对应的列表已经不在了。
   */
  run<T>(fetch: (params: LogQueryParams | undefined) => Promise<T>, apply: (result: T) => void): Promise<boolean>
}

export function createLogListSession(): LogListSession {
  let mode: LogListMode = 'live'
  let params: LogQueryParams | undefined
  let generation = 0

  return {
    get mode() {
      return mode
    },
    get params() {
      return params
    },
    get generation() {
      return generation
    },
    enterSearch(next) {
      mode = 'search'
      params = next
      generation += 1
    },
    exitSearch() {
      mode = 'live'
      params = undefined
      generation += 1
    },
    invalidate() {
      generation += 1
    },
    acceptsLiveSignal() {
      return mode === 'live'
    },
    async run(fetch, apply) {
      const token = generation
      const captured = params
      try {
        const result = await fetch(captured)
        if (token !== generation) return false
        apply(result)
        return true
      } catch (e) {
        if (token !== generation) return false
        throw e
      }
    },
  }
}
