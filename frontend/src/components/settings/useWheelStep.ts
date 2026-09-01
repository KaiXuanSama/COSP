/**
 * 滚轮步进的接线 —— 把「算下一档」与「触发位移动画」合成一次调用。
 *
 * <h2>抽出来的理由</h2>
 * 三处数值输入框（上下文、最大输出，以及将来的思考预算）需要同一套动作：
 * 读方向 → 在预设里步进 → 相同则不动 → 触发一次性动画标记 → 写回值。
 * 逐处手写这五步，很容易漏掉「相同则不动」（会让端点处也播动画）
 * 或漏掉动画标记（滚了但没反馈）。
 *
 * <h2>为何不做成组件</h2>
 * 这些栏的 DOM 形态各不相同（带模式的外壳 / 裸 `n-input`），差异只在事件绑定上。
 * 组合式函数不碰结构，因此可以给形态完全不同的控件用同一套行为 ——
 * 而抽一个组件出来会强行统一它们的 DOM，并要求各自的边框都交给壳层。
 *
 * <h2>为何值的读写用回调而不是 v-model</h2>
 * 三处的持久化形态都不一样：上下文是裸字符串，最大输出是 JSON 里的一个字段。
 * 让调用方给出读写函数，这里就不必知道任何一种形态。
 */
import {
  directionFromWheel,
  stepNumericPreset,
  type StepDirection,
} from '@/features/provider-config'

import { useTransientFlags } from './useTransientFlags'

/** 数值型滚轮步进的操作句柄。 */
export interface WheelStep {
  /**
   * 各行最近一次滚动的方向，供模板挂动画 class。
   *
   * 按行下标分别记：在 A 行滚动不该让 B 行也动一下。
   */
  readonly nudges: ReturnType<typeof useTransientFlags<StepDirection>>['flags']
  /**
   * 处理一次滚轮事件。
   *
   * 事件本身的 `preventDefault` 交给模板的 `.prevent` 修饰符 —— 那是声明式的，
   * 比在这里调用更容易在读模板时发现（不拦截会连带滚动抽屉）。
   *
   * @param key 行下标，用于隔离各行的动画
   */
  onWheel(key: number, event: WheelEvent, read: () => number, write: (next: number) => void): void
  /** 清除某行的方向。手填与点击预设时调用 —— 那些操作没有方向语义。 */
  clear(key: number): void
}

/**
 * 创建数值型滚轮步进。
 *
 * @param presets    预设数值清单，顺序任意（`stepNumericPreset` 自己排序）
 * @param durationMs 动画标记存活时长，必须不短于对应的 CSS 动画时长
 */
export function useWheelStep(presets: readonly number[], durationMs: number): WheelStep {
  const nudges = useTransientFlags<StepDirection>(durationMs)

  return {
    nudges: nudges.flags,
    onWheel(key, event, read, write) {
      const direction = directionFromWheel(event.deltaY)
      if (direction === null) return
      const current = read()
      const next = stepNumericPreset(current, presets, direction)
      // 端点处不动：写回相同的值会白播一次动画，看起来像「滚动生效了」。
      if (next === current) return
      nudges.trigger(key, direction)
      write(next)
    },
    clear(key) {
      nudges.clear(key)
    },
  }
}
