/**
 * 调用日志相关的共享类型。
 *
 * 抽到独立模块是因为调用者视角（CallLog.vue）与消费者视角（UsageLog.vue）
 * 都要消费同一份详情结构，再加上公共详情组件 CallLogDetail.vue，
 * 三处若各自声明会在字段增减时静默漂移。
 */

/**
 * 调用 token 用量（来自独立的 api_call_usage 表，经详情端点的 usage 字段回传）。
 *
 * token 字段遵循 null vs 0 语义：null 表示上游未提供该数据，0 表示上游报告了真实零值。
 * 展示时不得把 null 渲染成 0。
 */
export interface UsageDetail {
  id: number
  log_id: number | null
  provider_key: string | null
  model_name: string | null
  is_stream: number
  usage_raw: string | null
  prompt_tokens: number | null
  completion_tokens: number | null
  cached_tokens: number | null
  ttfb_ms: number | null
  created_at: string
}

/** 单条调用日志的完整详情（GET /config/api/logs/{id} 的响应体）。 */
export interface DetailItem {
  id: number
  provider_key: string
  model_name: string
  is_stream: number
  status_code: number
  request_headers: string | null
  request_body: string | null
  response_headers: string | null
  response_body: string | null
  chunks: string | null
  duration_ms: number | null
  created_at: string
  /** 该次调用的 token 用量；null 表示未查询到（旧日志 / 失败调用 / 上游未返回 usage）。 */
  usage: UsageDetail | null
}
