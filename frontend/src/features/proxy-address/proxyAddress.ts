/** UI 在后端只保存 host 时展示的默认代理端口。 */
export const DEFAULT_PROXY_PORT = 7890

export interface ProxyAddressParts {
  host: string
  port: number
}

/**
 * 把后端的单字段代理地址拆成设置页的主机与端口。
 *
 * 空配置保留默认端口，方便用户只填写主机；无端口的历史值同样按后端默认 7890 展示。
 * 方括号 IPv6 会在输入框里去掉括号，保存时再补回。
 */
export function parseProxyAddress(address: string): ProxyAddressParts {
  const normalized = address.trim()
  if (!normalized) {
    return { host: '', port: DEFAULT_PROXY_PORT }
  }

  const bracketedIpv6 = normalized.match(/^\[([^\]]+)](?::(\d+))?$/)
  if (bracketedIpv6) {
    return {
      host: bracketedIpv6[1],
      port: parsePort(bracketedIpv6[2]),
    }
  }

  const separator = normalized.lastIndexOf(':')
  const portText = separator > 0 ? normalized.slice(separator + 1) : ''
  if (/^\d+$/.test(portText)) {
    return {
      host: normalized.slice(0, separator),
      port: Number(portText),
    }
  }

  return { host: normalized, port: DEFAULT_PROXY_PORT }
}

/** 将设置页的两个输入值收敛回后端使用的 host:port；空主机表示清空代理。 */
export function formatProxyAddress(host: string, port: number): string {
  const normalizedHost = host.trim()
  if (!normalizedHost) return ''

  const hostWithIpv6Brackets = normalizedHost.includes(':')
    && !normalizedHost.startsWith('[')
    ? `[${normalizedHost}]`
    : normalizedHost
  return `${hostWithIpv6Brackets}:${port}`
}

function parsePort(value: string | undefined): number {
  return value ? Number(value) : DEFAULT_PROXY_PORT
}