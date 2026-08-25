import { describe, expect, it } from 'vitest'

import {
  ANTHROPIC_ENDPOINT_SUFFIX,
  DEFAULT_NEW_PROVIDER_PROTOCOLS,
  OPENAI_ENDPOINT_SUFFIX,
  describeEndpoint,
  mirrorAnthropicBaseUrl,
  normalizeProtocols,
  orderProtocolRows,
  protocolsToJson,
  resolveModelPullTarget,
  resolvePrimaryProtocol,
  shouldMirrorOnFocus,
  toggleProtocol,
} from './protocolUrls'

describe('normalizeProtocols', () => {
  it('保留后端给出的协议并按固定顺序排列', () => {
    expect(normalizeProtocols(['ANTHROPIC', 'OPENAI'])).toEqual(['OPENAI', 'ANTHROPIC'])
  })

  it('大小写与空白被归一化', () => {
    expect(normalizeProtocols([' openai '])).toEqual(['OPENAI'])
  })

  it('去重', () => {
    expect(normalizeProtocols(['OPENAI', 'OPENAI'])).toEqual(['OPENAI'])
  })

  // 后端对「读不懂」的配置回退为全集，前端必须同口径 —— 否则界面显示没勾选、
  // 实际两条线路都能跑，用户看到的和发生的不是一回事。
  it('字段缺失或不是数组时回退为两种协议', () => {
    expect(normalizeProtocols(undefined)).toEqual(['OPENAI', 'ANTHROPIC'])
    expect(normalizeProtocols('OPENAI')).toEqual(['OPENAI', 'ANTHROPIC'])
  })

  it('数组里全是脏值时同样回退为两种协议', () => {
    expect(normalizeProtocols(['GEMINI', 42])).toEqual(['OPENAI', 'ANTHROPIC'])
  })

  // 空数组是用户主动声明的非法状态，必须在界面上如实显示才能被改回来；
  // 悄悄补成全集会让「一个都没勾」这件事永远看不见。
  it('显式空数组如实保留为空', () => {
    expect(normalizeProtocols([])).toEqual([])
  })
})

describe('toggleProtocol', () => {
  it('勾选后按固定顺序排列而非追加到末尾', () => {
    expect(toggleProtocol(['ANTHROPIC'], 'OPENAI', true)).toEqual(['OPENAI', 'ANTHROPIC'])
  })

  it('取消勾选移除该项', () => {
    expect(toggleProtocol(['OPENAI', 'ANTHROPIC'], 'ANTHROPIC', false)).toEqual(['OPENAI'])
  })

  it('可以取消到空集，由调用方决定是否拦下', () => {
    expect(toggleProtocol(['OPENAI'], 'OPENAI', false)).toEqual([])
  })

  it('重复勾选不产生重复项', () => {
    expect(toggleProtocol(['OPENAI'], 'OPENAI', true)).toEqual(['OPENAI'])
  })
})

describe('protocolsToJson', () => {
  it('序列化为固定顺序的 JSON 数组', () => {
    expect(protocolsToJson(['ANTHROPIC', 'OPENAI'])).toBe('["OPENAI","ANTHROPIC"]')
  })

  // 后端把空串当作「未提供，保留原值」，只有字面的 [] 才表示空集。
  it('空集序列化为字面的空数组', () => {
    expect(protocolsToJson([])).toBe('[]')
  })
})

describe('Anthropic 地址联动', () => {
  it('Anthropic 为空时本轮编辑联动', () => {
    expect(shouldMirrorOnFocus('')).toBe(true)
    expect(shouldMirrorOnFocus('   ')).toBe(true)
  })

  it('Anthropic 已有值时不联动', () => {
    expect(shouldMirrorOnFocus('https://ant.example')).toBe(false)
  })

  /**
   * 这是联动逻辑存在的全部理由：判断依据是获得焦点那一刻的状态，
   * 而不是每次输入时重新判空。若每次判空，敲下第一个字符后 Anthropic
   * 就不再为空，于是它永远停在一个字母上。
   */
  it('联动状态在整轮输入中保持，不因已被同步而中断', () => {
    let anthropic = ''
    const mirroring = shouldMirrorOnFocus(anthropic)

    for (const typed of ['h', 'ht', 'htt', 'http']) {
      anthropic = mirrorAnthropicBaseUrl(typed, mirroring, anthropic)
    }

    expect(anthropic).toBe('http')
  })

  it('未联动时 Anthropic 保持原值', () => {
    expect(mirrorAnthropicBaseUrl('https://openai.example', false, 'https://ant.example'))
      .toBe('https://ant.example')
  })

  it('联动时清空 OpenAI 也会同步清空 Anthropic', () => {
    expect(mirrorAnthropicBaseUrl('', true, 'https://openai.example')).toBe('')
  })
})

describe('describeEndpoint', () => {
  it('拼出完整端点', () => {
    expect(describeEndpoint('https://ps.air-outer.com/v1', OPENAI_ENDPOINT_SUFFIX))
      .toBe('https://ps.air-outer.com/v1/chat/completions')
    expect(describeEndpoint('https://ps.air-outer.com/v1', ANTHROPIC_ENDPOINT_SUFFIX))
      .toBe('https://ps.air-outer.com/v1/messages')
  })

  it('去掉尾斜杠，与后端归一化口径一致', () => {
    expect(describeEndpoint('https://a.example/v1//', OPENAI_ENDPOINT_SUFFIX))
      .toBe('https://a.example/v1/chat/completions')
  })

  // 只有路径的半成品（如 "/chat/completions"）在界面上比不显示更让人困惑。
  it('地址为空时返回空串而不是只剩路径', () => {
    expect(describeEndpoint('', OPENAI_ENDPOINT_SUFFIX)).toBe('')
    expect(describeEndpoint('   ', ANTHROPIC_ENDPOINT_SUFFIX)).toBe('')
  })
})

describe('DEFAULT_NEW_PROVIDER_PROTOCOLS', () => {
  it('新建供应商默认勾选两个协议', () => {
    expect(DEFAULT_NEW_PROVIDER_PROTOCOLS).toEqual(['OPENAI', 'ANTHROPIC'])
  })
})

describe('resolvePrimaryProtocol', () => {
  it('两个都启用时 OpenAI 在首行', () => {
    expect(resolvePrimaryProtocol(['OPENAI', 'ANTHROPIC'])).toBe('OPENAI')
  })

  it('只启用 OpenAI 时在首行', () => {
    expect(resolvePrimaryProtocol(['OPENAI'])).toBe('OPENAI')
  })

  // 折叠状态下只看得见第一行，若它恒为 OpenAI，一个只走 Anthropic 的供应商
  // 展开前看到的是一个自己禁用了的地址，等于没有信息。
  it('OpenAI 禁用而 Anthropic 启用时把 Anthropic 提到首行', () => {
    expect(resolvePrimaryProtocol(['ANTHROPIC'])).toBe('ANTHROPIC')
  })

  it('一个都没启用时仍给 OpenAI，不留无行可显的状态', () => {
    expect(resolvePrimaryProtocol([])).toBe('OPENAI')
  })
})

describe('orderProtocolRows', () => {
  it('首行为 OpenAI 时的顺序', () => {
    expect(orderProtocolRows('OPENAI')).toEqual(['OPENAI', 'ANTHROPIC'])
  })

  it('首行为 Anthropic 时的顺序', () => {
    expect(orderProtocolRows('ANTHROPIC')).toEqual(['ANTHROPIC', 'OPENAI'])
  })

  it('两行总是都在，只是次序不同', () => {
    for (const primary of ['OPENAI', 'ANTHROPIC'] as const) {
      expect(orderProtocolRows(primary)).toHaveLength(2)
      expect(orderProtocolRows(primary)).toContain('OPENAI')
      expect(orderProtocolRows(primary)).toContain('ANTHROPIC')
    }
  })
})

describe('resolveModelPullTarget', () => {
  const openai = 'https://openai.example/v1'
  const ant = 'https://ant.example/v1'

  // 两个协议的模型列表端点路径完全相同，无法从响应判断上游以哪种协议作答，
  // 所以「让用户选」没有参考价值 —— 固定优先 OpenAI 是唯一不需要用户判断的规则。
  it('两个都启用时用 OpenAI', () => {
    expect(resolveModelPullTarget(['OPENAI', 'ANTHROPIC'], openai, ant))
      .toEqual({ protocol: 'OPENAI', baseUrl: openai })
  })

  it('只启用 OpenAI 时用 OpenAI', () => {
    expect(resolveModelPullTarget(['OPENAI'], openai, ant))
      .toEqual({ protocol: 'OPENAI', baseUrl: openai })
  })

  it('只启用 Anthropic 时退到 Anthropic 地址', () => {
    expect(resolveModelPullTarget(['ANTHROPIC'], openai, ant))
      .toEqual({ protocol: 'ANTHROPIC', baseUrl: ant })
  })

  // 界面上「留空则与 OpenAI 相同」是一句提示，这里必须是同一条规则，
  // 否则用户会看到「提示说相同，但拉取报地址为空」。
  it('只启用 Anthropic 且其地址为空时回退到 OpenAI 地址', () => {
    expect(resolveModelPullTarget(['ANTHROPIC'], openai, '   '))
      .toEqual({ protocol: 'ANTHROPIC', baseUrl: openai })
  })

  // 随便挑一个地址发出去，会让「没启用协议」这个真正的问题被一个上游错误掩盖。
  it('一个都没启用时返回 null 交由调用方提示', () => {
    expect(resolveModelPullTarget([], openai, ant)).toBeNull()
  })

  it('地址两侧空白被去掉', () => {
    expect(resolveModelPullTarget(['OPENAI'], `  ${openai}  `, '')?.baseUrl).toBe(openai)
  })
})
