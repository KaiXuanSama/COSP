/**
 * 一次性动画标记的调度器 —— 触发后自动过期。
 *
 * <h2>要解决的问题</h2>
 * 「滚轮推了一下」需要视觉反馈，但反馈的载体可能是**可编辑的输入框**：
 * 套 `<Transition>` 会在过渡期间同时存在两份内容，光标与选区随之错乱。
 *
 * <p>于是换成给元素挂一个短暂的 class，由 CSS 播一段位移动画，播完摘掉。
 * 输入框本体从未被替换，光标位置不受影响。
 *
 * <h2>为何标记必须自动过期</h2>
 * 若把方向长期存在 state 里，class 就会一直挂着，语义变成「当前方向是」而非
 * 「刚刚发生了一次」—— 之后任何一次重渲染都可能重播同一段动画。
 *
 * <h2>为何同值重复触发要隔一帧</h2>
 * CSS animation 只在 class **从无到有**时播放。同一个微任务里把标记清空又设回去，
 * 框架只会 patch 最终状态，class 从未真正离开 DOM，浏览器因此认为动画没有重新开始
 * —— 连续同向滚动就只有第一格有反馈。所以 {@link ScheduleHooks.defer} 存在。
 *
 * <h2>为何不直接写成组合式函数</h2>
 * 这里的两个易错点（自动过期、隔帧重触发）都与 Vue 无关，而项目的测试环境是
 * 纯 node（没有 `document`，也没装 `@vue/test-utils`）。把调度剥成纯函数后，
 * 计时与重触发都能单测；Vue 那一侧只剩「把值写进 ref」和「卸载时清理」。
 */

/** 调度器需要的外部能力，测试里可替换为可控实现。 */
export interface ScheduleHooks {
  /** 延时执行，返回可用于取消的句柄。对应 `setTimeout`。 */
  delay(callback: () => void, ms: number): number
  /** 取消延时。对应 `clearTimeout`。 */
  cancelDelay(handle: number): void
  /** 推到下一帧执行，返回可用于取消的句柄。对应 `requestAnimationFrame`。 */
  defer(callback: () => void): number
  /** 取消下一帧回调。对应 `cancelAnimationFrame`。 */
  cancelDefer(handle: number): void
}

/** 默认使用浏览器的定时器与帧回调。 */
export const browserScheduleHooks: ScheduleHooks = {
  delay: (callback, ms) => setTimeout(callback, ms) as unknown as number,
  cancelDelay: handle => clearTimeout(handle),
  defer: callback => requestAnimationFrame(callback),
  cancelDefer: handle => cancelAnimationFrame(handle),
}

/** 按键管理的一次性标记调度器。 */
export interface TransientFlagScheduler<T> {
  /**
   * 触发某个键的标记。
   *
   * 与当前相同的值也会重新触发：先写 `null`，下一帧再写回目标值。
   */
  trigger(key: number, flag: T): void
  /** 立即清除某个键并取消其待执行回调。 */
  clear(key: number): void
  /** 清除全部，供组件卸载时调用 —— 待执行的回调会写一个已不存在的状态。 */
  dispose(): void
}

/**
 * 创建调度器。
 *
 * @param durationMs 标记存活时长，应不短于 CSS 动画时长 ——
 *                   提前摘掉 class 会让动画中途被打断，看起来像闪了一下
 * @param read       读取某个键的当前标记，用于判断是否需要隔帧重触发
 * @param write      写入某个键的标记，由调用方接到响应式状态上
 * @param hooks      定时器与帧回调，默认用浏览器的
 */
export function createTransientFlagScheduler<T>(
  durationMs: number,
  read: (key: number) => T | null | undefined,
  write: (key: number, flag: T | null) => void,
  hooks: ScheduleHooks = browserScheduleHooks,
): TransientFlagScheduler<T> {
  const delays = new Map<number, number>()
  const defers = new Map<number, number>()

  function cancelPending(key: number) {
    const delayHandle = delays.get(key)
    if (delayHandle !== undefined) {
      hooks.cancelDelay(delayHandle)
      delays.delete(key)
    }
    const deferHandle = defers.get(key)
    if (deferHandle !== undefined) {
      hooks.cancelDefer(deferHandle)
      defers.delete(key)
    }
  }

  function scheduleExpiry(key: number) {
    delays.set(key, hooks.delay(() => {
      delays.delete(key)
      write(key, null)
    }, durationMs))
  }

  return {
    trigger(key, flag) {
      cancelPending(key)
      // 同值先清空一帧，让 CSS 动画能重新播放。
      if (read(key) === flag) {
        write(key, null)
        defers.set(key, hooks.defer(() => {
          defers.delete(key)
          write(key, flag)
          scheduleExpiry(key)
        }))
        return
      }
      write(key, flag)
      scheduleExpiry(key)
    },
    clear(key) {
      cancelPending(key)
      write(key, null)
    },
    dispose() {
      delays.forEach(hooks.cancelDelay)
      defers.forEach(hooks.cancelDefer)
      delays.clear()
      defers.clear()
    },
  }
}
