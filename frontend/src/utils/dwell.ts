/**
 * 停留触发器 —— 值稳定一段时间后才执行副作用。
 *
 * ## 为什么是 debounce 而不是 throttle
 * 场景是「拖动选择器经过若干离散点，只为最终停下的那个点发请求」。
 * 节流（throttle）按固定速率放行，途中经过的点会各自触发一次 ——
 * 那些点用户根本没有停留，请求发出即作废。
 *
 * <p>停留触发的语义正好相反：每次值变化都<strong>重置</strong>计时，
 * 只有值在 `delayMs` 内不再变化才执行。快速划过 10 个点只产生 1 次调用，
 * 而在某个点上停住 200ms 就立刻加载 —— 不必等松手。
 *
 * ## 为什么不监听「松手」
 * 松手是一个终止信号，按它触发意味着「拖动过程中什么都不显示」。
 * 而停留触发让用户在拖动途中就能逐点看到数据，滑过去像在翻页。
 * 两者可以并存（{@link DwellTrigger.flush}），但默认不绑松手 ——
 * 停留计时已经覆盖了「拖到某点然后松手」这个动作，再补一次只会重复请求。
 */

/** 停留触发器的操作句柄。 */
export interface DwellTrigger<T> {
  /**
   * 提交一个新值并重置计时。
   *
   * <p>与上次提交的值<strong>相同</strong>时也会重置 —— 判重是调用方的事，
   * 因为「相同」的定义（引用、字段、序列化）随值类型而变。
   */
  schedule(value: T): void
  /** 取消待执行的回调。已执行过的不受影响。 */
  cancel(): void
  /**
   * 立即执行待处理的值，不再等待。
   *
   * <p>没有待处理值时什么都不做（不会重复执行上一次的值）。
   * 供「松手即刻加载」这类需要抢先的场景使用。
   */
  flush(): void
  /** 是否有待执行的值。 */
  readonly pending: boolean
}

/**
 * 创建一个停留触发器。
 *
 * @param delayMs 停留时长（毫秒）。非正数表示同步执行 —— 供测试与「关闭停留」使用
 * @param run     停留达成后执行的回调，收到最后一次提交的值
 */
export function createDwellTrigger<T>(delayMs: number, run: (value: T) => void): DwellTrigger<T> {
  let timer: ReturnType<typeof setTimeout> | null = null
  // 用一个单元素容器而非裸变量：T 可能允许 undefined，
  // 靠 `latest !== undefined` 判断「有没有待处理值」会在那种 T 上失效。
  let queued: { value: T } | null = null

  function fire() {
    timer = null
    const entry = queued
    queued = null
    if (entry) run(entry.value)
  }

  return {
    schedule(value: T) {
      queued = { value }
      if (delayMs <= 0) {
        if (timer !== null) {
          clearTimeout(timer)
          timer = null
        }
        fire()
        return
      }
      if (timer !== null) clearTimeout(timer)
      timer = setTimeout(fire, delayMs)
    },
    cancel() {
      if (timer !== null) {
        clearTimeout(timer)
        timer = null
      }
      queued = null
    },
    flush() {
      if (timer !== null) {
        clearTimeout(timer)
        timer = null
      }
      if (queued) fire()
    },
    get pending() {
      return queued !== null
    },
  }
}
