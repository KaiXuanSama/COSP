/**
 * 一次性动画标记 —— Vue 侧的薄封装。
 *
 * 调度逻辑（自动过期、隔帧重触发、按键隔离）在
 * `features/provider-config/transientFlag.ts`，那里不依赖 Vue 也不依赖 DOM，
 * 因此可以单测。这里只做两件事：把标记接到响应式状态上、卸载时清理待执行回调。
 *
 * <p>用法见 `ProviderModelsSection.vue` 里最大输出那一栏 —— 它的值段是可编辑输入框，
 * 套不了 `<Transition>`，只能靠短暂的 class 播 CSS 动画。
 */
import { onUnmounted, ref, type Ref } from 'vue'

import { createTransientFlagScheduler } from '@/features/provider-config'

/** 按键管理的一次性标记集合。 */
export interface TransientFlags<T> {
  /**
   * 各键当前的标记值。模板里直接读 `flags.value[index]`。
   *
   * 做成「按键」而非单值，是因为使用场景在 `v-for` 里：每个模型行都有自己的值段，
   * A 行滚动不该让 B 行也动一下。组合式函数无法在 `v-for` 内部逐项调用，
   * 于是把键（模型下标）作为参数。
   */
  readonly flags: Readonly<Ref<Record<number, T | null>>>
  /** 触发某个键的标记。与当前相同的值也会重新触发。 */
  trigger(key: number, flag: T): void
  /** 立即清除某个键。供「这次变化没有方向」的场景使用（手填、点击预设）。 */
  clear(key: number): void
}

/**
 * 创建一组会自动过期的动画标记。
 *
 * @param durationMs 标记存活时长，必须不短于对应的 CSS 动画时长
 */
export function useTransientFlags<T>(durationMs: number): TransientFlags<T> {
  const flags = ref<Record<number, T | null>>({}) as Ref<Record<number, T | null>>

  const scheduler = createTransientFlagScheduler<T>(
    durationMs,
    key => flags.value[key],
    (key, flag) => { flags.value[key] = flag },
  )

  onUnmounted(() => scheduler.dispose())

  return {
    flags,
    trigger: scheduler.trigger,
    clear: scheduler.clear,
  }
}
