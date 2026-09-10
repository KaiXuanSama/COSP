import { describe, expect, it } from 'vitest'
import { DEFAULT_PROXY_PORT, formatProxyAddress, parseProxyAddress } from './proxyAddress'

describe('proxyAddress', () => {
  it('为空配置保留默认端口', () => {
    expect(parseProxyAddress('')).toEqual({ host: '', port: DEFAULT_PROXY_PORT })
  })

  it('拆分 IPv4 或主机名与端口', () => {
    expect(parseProxyAddress('127.0.0.1:7890')).toEqual({ host: '127.0.0.1', port: 7890 })
    expect(parseProxyAddress('proxy.local:1080')).toEqual({ host: 'proxy.local', port: 1080 })
  })

  it('无端口的历史值使用默认端口', () => {
    expect(parseProxyAddress('proxy.local')).toEqual({ host: 'proxy.local', port: DEFAULT_PROXY_PORT })
  })

  it('往返方括号 IPv6 地址', () => {
    expect(parseProxyAddress('[::1]:7890')).toEqual({ host: '::1', port: 7890 })
    expect(formatProxyAddress('::1', 7890)).toBe('[::1]:7890')
  })

  it('保存时去除主机空白，空主机表示清空代理', () => {
    expect(formatProxyAddress(' 127.0.0.1 ', 7890)).toBe('127.0.0.1:7890')
    expect(formatProxyAddress('   ', 7890)).toBe('')
  })
})