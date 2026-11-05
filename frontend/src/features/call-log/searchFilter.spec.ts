import { describe, expect, it } from 'vitest'
import axios from 'axios'
import { logsRequestConfig } from '@/api'
import {
  STATUS_CLASS_OPTIONS,
  STATUS_CODE_OPTIONS,
  createEmptyFilter,
  formatLocalDateTime,
  isFilterEmpty,
  toQueryParams,
  type LogSearchFilter,
} from './searchFilter'

function filterWith(patch: Partial<LogSearchFilter>): LogSearchFilter {
  return { ...createEmptyFilter(), ...patch }
}

/** 按 axios 实际的序列化结果断言，而不是只看 params 对象。 */
function uriOf(cursor: number | null, search?: ReturnType<typeof toQueryParams>): string {
  return axios.getUri({ url: '/logs', ...logsRequestConfig(cursor, 50, search) })
}

describe('formatLocalDateTime', () => {
  it('输出本地时间而非 UTC，格式与 created_at 一致', () => {
    const ms = new Date(2026, 10, 5, 9, 3, 7).getTime()
    expect(formatLocalDateTime(ms)).toBe('2026-11-05T09:03:07')
  })

  it('舍去毫秒', () => {
    const ms = new Date(2026, 0, 1, 0, 0, 0, 999).getTime()
    expect(formatLocalDateTime(ms)).toBe('2026-01-01T00:00:00')
  })
})

describe('toQueryParams', () => {
  it('空表单不产生参数', () => {
    expect(toQueryParams(createEmptyFilter())).toBeUndefined()
    expect(isFilterEmpty(createEmptyFilter())).toBe(true)
  })

  it('只改匹配方式、文本留空不算条件', () => {
    const filter = filterWith({ provider: '   ', providerMatch: 'contains', modelMatch: 'contains' })
    expect(isFilterEmpty(filter)).toBe(true)
  })

  it('时间范围两端都转成本地时间字符串', () => {
    const from = new Date(2026, 10, 1, 8, 0, 0).getTime()
    const to = new Date(2026, 10, 1, 23, 59, 59).getTime()
    expect(toQueryParams(filterWith({ timeRange: [from, to] }))).toEqual({
      from: '2026-11-01T08:00:00',
      to: '2026-11-01T23:59:59',
    })
  })

  it('状态去重，文本去首尾空白，匹配方式随文本一起发送', () => {
    expect(
      toQueryParams(
        filterWith({
          statuses: ['5xx', '429', '5xx'],
          provider: ' stepfun ',
          model: 'glm',
          modelMatch: 'contains',
        }),
      ),
    ).toEqual({
      status: ['5xx', '429'],
      provider: 'stepfun',
      providerMatch: 'exact',
      model: 'glm',
      modelMatch: 'contains',
    })
  })
})

describe('状态选项', () => {
  it('取值都是后端接受的 token', () => {
    const values = [...STATUS_CLASS_OPTIONS, ...STATUS_CODE_OPTIONS].map((o) => o.value)
    for (const value of values) {
      expect(/^(2xx|4xx|5xx|-1|[1-5]\d\d)$/.test(value)).toBe(true)
    }
    expect(new Set(values).size).toBe(values.length)
  })
})

describe('logsRequestConfig', () => {
  it('不带检索参数时与原请求相同', () => {
    expect(uriOf(null)).toBe('/logs?pageSize=50')
    expect(uriOf(42)).toBe('/logs?cursor=42&pageSize=50')
  })

  it('status 序列化为重复参数而非 status[]', () => {
    const uri = uriOf(null, { status: ['2xx', '429', '-1'] })
    expect(uri).toBe('/logs?status=2xx&status=429&status=-1&pageSize=50')
  })

  it('检索参数与游标一起发送', () => {
    const uri = uriOf(7, { from: '2026-11-01T08:00:00', provider: 'step', providerMatch: 'contains' })
    const query = new URLSearchParams(uri.split('?')[1])
    expect(query.get('from')).toBe('2026-11-01T08:00:00')
    expect(query.get('provider')).toBe('step')
    expect(query.get('providerMatch')).toBe('contains')
    expect(query.get('cursor')).toBe('7')
  })
})
