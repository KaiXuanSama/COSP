/**
 * 拉取模型失败的用户可读文案。
 *
 * 优先透出后端给出的具体原因，而不是把所有失败都归为「网络问题」——
 * 上游返回的鉴权错误、端点不存在等信息对排查最有价值。
 * 只有在拿不到任何后端信息时，才按状态码猜一个方向。
 */

const PREFIX = '模型拉取失败：'

/** 从后端响应体中提取 `error` 字段。响应可能是对象，也可能是 JSON 字符串。 */
function extractBackendError(data: unknown): string | null {
  if (data && typeof data === 'object') {
    const error = (data as Record<string, unknown>).error
    if (typeof error === 'string' && error.trim()) return error.trim()
    return null
  }

  if (typeof data === 'string' && data.trim()) {
    try {
      const parsed = JSON.parse(data)
      const error = parsed?.error
      if (typeof error === 'string' && error.trim()) return error.trim()
    } catch {
      // 非 JSON，原文本身就是可展示的错误信息
    }
    return data.trim()
  }

  return null
}

/** 状态码兜底文案。仅在后端未给出任何信息时使用。 */
function fallbackByStatus(status: number | undefined): string {
  if (status === 401 || status === 403) return PREFIX + 'API Key 无效或无权限'
  if (status === 404) return PREFIX + '模型列表端点不存在'
  return '拉取模型失败，请检查网络连接和 API 地址'
}

/** 把拉取模型的异常转换为展示文案。 */
export function resolvePullModelsErrorMessage(error: unknown): string {
  const response = (error as { response?: { status?: number; data?: unknown } } | undefined)?.response
  const backendError = extractBackendError(response?.data)
  if (backendError) return PREFIX + backendError
  return fallbackByStatus(response?.status)
}
