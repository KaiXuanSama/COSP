/**
 * 请求体规则预览的异步取值。
 *
 * 预览由后端生产引擎计算（见 `api/index.ts` 的 `previewRequestBodyRules`），
 * 本模块负责把「一次异步请求」包装成编辑器能直接消费的响应式状态，处理四件事：
 * 防抖、请求竞态、加载态、失败降级。
 *
 * <h2>为何要有 stale 标记</h2>
 * 请求失败时保留上一次成功的结果，但必须标记它已过期。直接丢掉会让预览闪成空白，
 * 而假装它仍然对应当前输入则是另一种形式的说谎 —— 接口化本来就是为了消除说谎的预览。
 *
 * <h2>为何请求函数由外部传入</h2>
 * 便于单测：竞态与防抖是这里唯一值得测的逻辑，注入一个可控的 fake 比 mock axios 直接得多。
 */
import { ref, shallowRef, type Ref } from 'vue'
import type { TransformWarning } from './types'

/** 一次预览请求的入参。 */
export interface PreviewInput {
  previewBody: Record<string, unknown>
  rules: unknown[]
}

/** 一次预览请求的结果。 */
export interface PreviewResult {
  output: unknown
  warnings: TransformWarning[]
}

/** 预览请求函数。 */
export type PreviewRequester = (input: PreviewInput) => Promise<PreviewResult>

/** 预览状态。 */
export interface PreviewStatus {
  /** 最近一次成功结果；从未成功时为 null */
  result: Ref<PreviewResult | null>
  /** 是否有请求在途 */
  loading: Ref<boolean>
  /** 最近一次失败的原因；成功后清空 */
  error: Ref<string>
  /** 当前展示的结果是否已不对应最新输入 */
  stale: Ref<boolean>
}

/** 预览调度器。 */
export interface PreviewScheduler extends PreviewStatus {
  /** 排入一次预览请求；同一防抖窗口内的多次调用只发最后一次 */
  schedule: (input: PreviewInput) => void
  /** 取消在途的防抖定时器，组件卸载时调用 */
  dispose: () => void
}

/** 默认防抖窗口。够短以至于感觉是即时的，够长以至于连续打字不会每个字符都发一次请求。 */
export const DEFAULT_PREVIEW_DEBOUNCE_MS = 250

/**
 * 创建预览调度器。
 *
 * @param requester 实际发请求的函数
 * @param debounceMs 防抖窗口，默认 {@link DEFAULT_PREVIEW_DEBOUNCE_MS}
 */
export function createPreviewScheduler(
  requester: PreviewRequester,
  debounceMs: number = DEFAULT_PREVIEW_DEBOUNCE_MS,
): PreviewScheduler {
  const result = shallowRef<PreviewResult | null>(null)
  const loading = ref(false)
  const error = ref('')
  const stale = ref(false)

  /**
   * 请求序号。
   *
   * 竞态守卫必须按序号而非「是否有在途请求」判断：后端对不同输入的耗时不同，
   * 先发的请求完全可能后到，那时它携带的是旧输入的结果。只接受序号最大的响应。
   */
  let latestSeq = 0
  let timer: ReturnType<typeof setTimeout> | null = null

  function schedule(input: PreviewInput) {
    if (timer !== null) clearTimeout(timer)
    stale.value = true
    timer = setTimeout(() => {
      timer = null
      void send(input)
    }, debounceMs)
  }

  async function send(input: PreviewInput) {
    const seq = ++latestSeq
    loading.value = true
    try {
      const next = await requester(input)
      if (seq !== latestSeq) return
      result.value = next
      error.value = ''
      stale.value = false
    } catch (cause) {
      if (seq !== latestSeq) return
      error.value = cause instanceof Error ? cause.message : '预览请求失败'
    } finally {
      // 只有最新那次请求能改 loading：过期请求结束时可能还有新请求在途。
      if (seq === latestSeq) loading.value = false
    }
  }

  function dispose() {
    if (timer !== null) clearTimeout(timer)
    timer = null
    // 让所有在途响应作废，避免卸载后仍写入 ref。
    latestSeq += 1
  }

  return { result, loading, error, stale, schedule, dispose }
}
