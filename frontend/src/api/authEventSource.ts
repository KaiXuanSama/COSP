import http, { auth, redirectToLogin } from '@/api'

/**
 * 带认证的 SSE 客户端。
 *
 * 浏览器原生 {@link EventSource} 无法自定义请求头，发不出 `Authorization: Bearer xxx`，
 * 因此改用 fetch + ReadableStream 手动解析 SSE 帧，使认证方式与其它请求统一（均走 Bearer header）。
 *
 * 复刻原生 EventSource 的核心行为：
 * - 按事件名分发（对应 SSE 帧的 `event:` 字段，缺省为 `message`）；
 * - 连接中断后自动重连（默认 3s），手动 {@link AuthEventSource.close} 后不再重连；
 * - 注释帧（以 `:` 开头，如心跳 `: keep-alive`）忽略。
 *
 * 与原生的差异：
 * - token 全程在 header，不落入 URL / 服务端访问日志；
 * - 认证失败（401）会停止重连并触发全局登出跳转（复用 axios 拦截器的语义）。
 */

/** 单个事件的处理器：收到该事件名的 data 时回调。 */
export type SseEventHandler = (data: string) => void

/** 事件名 -> 处理器映射。键对应 SSE 帧的 `event:` 字段。 */
export type SseHandlers = Record<string, SseEventHandler>

export interface AuthEventSourceOptions {
  /** 相对 http.baseURL 的路径，如 `/calls/stream`。 */
  path: string
  /** 事件处理器映射。 */
  handlers: SseHandlers
  /** 断线重连间隔（毫秒），默认 3000。 */
  reconnectDelay?: number
  /**
   * 连接失败回调 —— 每次即将重连前触发一次。
   *
   * 对「首屏数据也由流下发」的视图是必需的：没有 HTTP 请求可供 catch，
   * 失败态只能由这里告知。401 不触发（那会跳登录，不是可重试的失败）。
   *
   * 会被重复调用（每轮重连各一次），调用方应做幂等处理。
   */
  onError?: () => void
}

/** 带认证的 SSE 连接句柄。 */
export interface AuthEventSource {
  /** 主动断开连接，之后不再自动重连。 */
  close(): void
}

const DEFAULT_RECONNECT_DELAY = 3000

/**
 * 建立一条带认证的 SSE 连接。立即发起，返回可关闭的句柄。
 */
export function createAuthEventSource(options: AuthEventSourceOptions): AuthEventSource {
  const { path, handlers, reconnectDelay = DEFAULT_RECONNECT_DELAY, onError } = options
  const url = `${http.defaults.baseURL ?? ''}${path}`

  let abortController: AbortController | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let closed = false

  function scheduleReconnect() {
    if (closed || reconnectTimer) return
    onError?.()
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      if (!closed) void connect()
    }, reconnectDelay)
  }

  async function connect() {
    if (closed) return
    const token = auth.getToken()
    if (!token) {
      // 无 token 不发起连接：挂载 SSE 的页面都要求登录，此处只是防御。
      return
    }

    abortController = new AbortController()
    try {
      const response = await fetch(url, {
        headers: { Authorization: `Bearer ${token}`, Accept: 'text/event-stream' },
        signal: abortController.signal,
      })

      if (response.status === 401) {
        // 认证失效：停止重连，清除 token 并跳登录（对齐 axios 拦截器语义）。
        closed = true
        redirectToLogin()
        return
      }
      if (!response.ok || !response.body) {
        // 其它错误（如 503 连接数超限）：稍后重连。
        scheduleReconnect()
        return
      }

      await readStream(response.body)
      // 流正常结束（服务端关闭）：若非手动关闭则重连。
      if (!closed) scheduleReconnect()
    } catch (e) {
      // AbortError 是主动关闭触发的，不重连；其它网络错误重连。
      if (!closed && (e as Error)?.name !== 'AbortError') {
        scheduleReconnect()
      }
    }
  }

  /** 逐块读取 ReadableStream，按 SSE 帧协议解析并分发。 */
  async function readStream(body: ReadableStream<Uint8Array>) {
    const reader = body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''

    while (!closed) {
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })

      // SSE 以空行（\n\n）分隔事件块。逐块切分，保留未完成的尾部。
      let sepIndex: number
      while ((sepIndex = buffer.indexOf('\n\n')) !== -1) {
        const rawEvent = buffer.slice(0, sepIndex)
        buffer = buffer.slice(sepIndex + 2)
        dispatchEvent(rawEvent)
      }
    }
  }

  /** 解析单个 SSE 事件块的 event: / data: 字段并分发给对应处理器。 */
  function dispatchEvent(rawEvent: string) {
    let eventName = 'message'
    const dataLines: string[] = []

    for (const line of rawEvent.split('\n')) {
      if (line.startsWith(':')) {
        // 注释帧（如心跳），忽略。
        continue
      }
      if (line.startsWith('event:')) {
        eventName = line.slice(6).trim()
      } else if (line.startsWith('data:')) {
        dataLines.push(line.slice(5).replace(/^ /, ''))
      }
    }

    if (!dataLines.length) return
    const handler = handlers[eventName]
    if (handler) handler(dataLines.join('\n'))
  }

  function close() {
    closed = true
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    if (abortController) {
      abortController.abort()
      abortController = null
    }
  }

  void connect()
  return { close }
}
