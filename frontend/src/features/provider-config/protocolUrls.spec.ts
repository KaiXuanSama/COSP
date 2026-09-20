import { describe, expect, it } from 'vitest'

import {
  CHAT_ENDPOINT_SUFFIX,
  DEFAULT_NEW_PROVIDER_PROTOCOLS,
  MESSAGES_ENDPOINT_SUFFIX,
  RESPONSES_ENDPOINT_SUFFIX,
  WIRE_PROTOCOL_ENDPOINT_SUFFIXES,
  describeEndpoint,
  mirrorBaseUrl,
  normalizeProtocols,
  orderProtocolRows,
  protocolsToJson,
  resolveModelPullTarget,
  resolvePrimaryProtocol,
  shouldMirrorOnFocus,
  toggleProtocol,
} from './protocolUrls'
import { ALL_WIRE_PROTOCOLS, type WireProtocol } from '@/types/protocol'

/** 三条线路地址的测试夹具，省去每个用例重复写全三个键。 */
function baseUrls(overrides: Partial<Record<WireProtocol, string>> = {}) {
  return { CHAT: '', RESPONSES: '', MESSAGES: '', ...overrides }
}

describe('normalizeProtocols', () => {
  it('保留后端给出的协议并按固定顺序排列', () => {
    // 后端可能以任意顺序回传（落库是字母序），这个函数负责归一到界面顺序。
    expect(normalizeProtocols(['MESSAGES', 'CHAT', 'RESPONSES']))
      .toEqual([...ALL_WIRE_PROTOCOLS])
    expect(normalizeProtocols(['RESPONSES', 'MESSAGES']))
      .toEqual(['MESSAGES', 'RESPONSES'])
  })

  it('大小写与空白被归一化', () => {
    expect(normalizeProtocols([' chat '])).toEqual(['CHAT'])
    expect(normalizeProtocols([' responses '])).toEqual(['RESPONSES'])
  })

  it('去重', () => {
    expect(normalizeProtocols(['CHAT', 'CHAT'])).toEqual(['CHAT'])
  })

  // 后端对「读不懂」的配置回退为全集，前端必须同口径 —— 否则界面显示没勾选、
  // 实际那条线路能跑，用户看到的和发生的不是一回事。
  it('字段缺失或不是数组时回退为全部协议', () => {
    expect(normalizeProtocols(undefined)).toEqual([...ALL_WIRE_PROTOCOLS])
    expect(normalizeProtocols('CHAT')).toEqual([...ALL_WIRE_PROTOCOLS])
  })

  it('数组里全是脏值时同样回退为全部协议', () => {
    expect(normalizeProtocols(['GEMINI', 42])).toEqual([...ALL_WIRE_PROTOCOLS])
  })

  /**
   * 旧协议名是脏值。V12 迁移后库里已不存在它们，但降级回滚或人为改库仍可能出现，
   * 此时该走「全是脏值 → 回退全集」而不是被当成某条线路。
   */
  it('旧协议名被当作脏值', () => {
    expect(normalizeProtocols(['OPENAI', 'ANTHROPIC'])).toEqual([...ALL_WIRE_PROTOCOLS])
  })

  // 空数组是用户主动声明的非法状态，必须在界面上如实显示才能被改回来；
  // 悄悄补成全集会让「一个都没勾」这件事永远看不见。
  it('显式空数组如实保留为空', () => {
    expect(normalizeProtocols([])).toEqual([])
  })
})

describe('toggleProtocol', () => {
  it('勾选后按固定顺序排列而非追加到末尾', () => {
    expect(toggleProtocol(['MESSAGES'], 'CHAT', true)).toEqual(['CHAT', 'MESSAGES'])
    // Responses 排末（回退优先级最低），因此这里恰好是追加 ——
    // 但判据是 ALL_WIRE_PROTOCOLS 的顺序，不是「追加」这个动作。下一条钉住这一点。
    expect(toggleProtocol(['CHAT', 'MESSAGES'], 'RESPONSES', true))
      .toEqual(['CHAT', 'MESSAGES', 'RESPONSES'])
    // MESSAGES 插在中间而非末尾，证明用的是顺序表而非 push。
    expect(toggleProtocol(['CHAT', 'RESPONSES'], 'MESSAGES', true))
      .toEqual(['CHAT', 'MESSAGES', 'RESPONSES'])
  })

  it('取消劾选移除该项', () => {
    expect(toggleProtocol(['CHAT', 'MESSAGES'], 'MESSAGES', false)).toEqual(['CHAT'])
    expect(toggleProtocol([...ALL_WIRE_PROTOCOLS], 'MESSAGES', false))
      .toEqual(['CHAT', 'RESPONSES'])
  })

  it('可以取消到空集，由调用方决定是否拦下', () => {
    expect(toggleProtocol(['CHAT'], 'CHAT', false)).toEqual([])
  })

  it('重复勾选不产生重复项', () => {
    expect(toggleProtocol(['CHAT'], 'CHAT', true)).toEqual(['CHAT'])
  })
})

describe('protocolsToJson', () => {
  it('序列化为固定顺序的 JSON 数组', () => {
    expect(protocolsToJson(['MESSAGES', 'CHAT'])).toBe('["CHAT","MESSAGES"]')
    expect(protocolsToJson(['RESPONSES', 'MESSAGES', 'CHAT']))
      .toBe('["CHAT","MESSAGES","RESPONSES"]')
  })

  // 后端把空串当作「未提供，保留原值」，只有字面的 [] 才表示空集。
  it('空集序列化为字面的空数组', () => {
    expect(protocolsToJson([])).toBe('[]')
  })
})

describe('协议地址联动', () => {
  it('目标地址为空时本轮编辑联动', () => {
    expect(shouldMirrorOnFocus('')).toBe(true)
    expect(shouldMirrorOnFocus('   ')).toBe(true)
  })

  it('目标地址已有值时不联动', () => {
    expect(shouldMirrorOnFocus('https://ant.example')).toBe(false)
  })

  /**
   * 这是联动逻辑存在的全部理由：判断依据是获得焦点那一刻的状态，
   * 而不是每次输入时重新判空。若每次判空，敲下第一个字符后目标
   * 就不再为空，于是它永远停在一个字母上。
   */
  it('联动状态在整轮输入中保持，不因已被同步而中断', () => {
    let target = ''
    const mirroring = shouldMirrorOnFocus(target)

    for (const typed of ['h', 'ht', 'htt', 'http']) {
      target = mirrorBaseUrl(typed, mirroring, target)
    }

    expect(target).toBe('http')
  })

  it('未联动时目标保持原值', () => {
    expect(mirrorBaseUrl('https://chat.example', false, 'https://ant.example'))
      .toBe('https://ant.example')
  })

  it('联动时清空 Chat 也会同步清空目标', () => {
    expect(mirrorBaseUrl('', true, 'https://chat.example')).toBe('')
  })

  /**
   * 一次 Chat 输入要同时驱动两条线路，而它们的联动状态**各自独立**。
   *
   * 这是加入 Responses 后新出现的组合，也是「每条线路各持一个布尔」的理由：
   * Anthropic 有值（不联动）而 Responses 为空（联动）是常见配置 ——
   * 共用一个快照会让其中一条要么该联动却不联动、要么不该联动却被覆写。
   */
  it('两条线路的联动状态互不影响', () => {
    const anthropic = 'https://ant.example'
    const responses = ''
    const mirrorAnthropic = shouldMirrorOnFocus(anthropic)
    const mirrorResponses = shouldMirrorOnFocus(responses)

    const chat = 'https://chat.example/v1'
    expect(mirrorBaseUrl(chat, mirrorAnthropic, anthropic)).toBe(anthropic)
    expect(mirrorBaseUrl(chat, mirrorResponses, responses)).toBe(chat)
  })
})

describe('describeEndpoint', () => {
  it('拼出完整端点', () => {
    expect(describeEndpoint('https://ps.air-outer.com/v1', CHAT_ENDPOINT_SUFFIX))
      .toBe('https://ps.air-outer.com/v1/chat/completions')
    expect(describeEndpoint('https://ps.air-outer.com/v1', RESPONSES_ENDPOINT_SUFFIX))
      .toBe('https://ps.air-outer.com/v1/responses')
    expect(describeEndpoint('https://ps.air-outer.com/v1', MESSAGES_ENDPOINT_SUFFIX))
      .toBe('https://ps.air-outer.com/v1/messages')
  })

  it('去掉尾斜杠，与后端归一化口径一致', () => {
    expect(describeEndpoint('https://a.example/v1//', CHAT_ENDPOINT_SUFFIX))
      .toBe('https://a.example/v1/chat/completions')
  })

  // 只有路径的半成品（如 "/chat/completions"）在界面上比不显示更让人困惑。
  it('地址为空时返回空串而不是只剩路径', () => {
    expect(describeEndpoint('', CHAT_ENDPOINT_SUFFIX)).toBe('')
    expect(describeEndpoint('   ', MESSAGES_ENDPOINT_SUFFIX)).toBe('')
  })
})

describe('WIRE_PROTOCOL_ENDPOINT_SUFFIXES', () => {
  /** 每条线路都要有后缀，否则端点预览会拼出一个没有路径的地址。 */
  it('三条线路各有后缀且互不相同', () => {
    expect(WIRE_PROTOCOL_ENDPOINT_SUFFIXES).toEqual({
      CHAT: '/chat/completions',
      RESPONSES: '/responses',
      MESSAGES: '/messages',
    })
    const suffixes = ALL_WIRE_PROTOCOLS.map(protocol => WIRE_PROTOCOL_ENDPOINT_SUFFIXES[protocol])
    expect(new Set(suffixes).size).toBe(ALL_WIRE_PROTOCOLS.length)
  })
})

describe('DEFAULT_NEW_PROVIDER_PROTOCOLS', () => {
  /**
   * 默认全勾，与后端三处口径同源（`schema.sql` DEFAULT、V13 回填、解析回退）。
   * 理由是同一条产品决定：勾上不通至多上游报错、用户可感知；默认不勾则让
   * 「支持却调不通」变成隐藏状态。
   */
  it('新建供应商默认勾选全部协议', () => {
    expect(DEFAULT_NEW_PROVIDER_PROTOCOLS).toEqual([...ALL_WIRE_PROTOCOLS])
  })
})

describe('resolvePrimaryProtocol', () => {
  it('全部启用时 Chat 在首行', () => {
    expect(resolvePrimaryProtocol([...ALL_WIRE_PROTOCOLS])).toBe('CHAT')
  })

  it('只启用 Chat 时在首行', () => {
    expect(resolvePrimaryProtocol(['CHAT'])).toBe('CHAT')
  })

  // 折叠状态下只看得见第一行，若它恒为 Chat，一个只走其它线路的供应商
  // 展开前看到的是一个自己禁用了的地址，等于没有信息。
  it('Chat 禁用时把第一个已启用的协议提到首行', () => {
    expect(resolvePrimaryProtocol(['MESSAGES'])).toBe('MESSAGES')
    expect(resolvePrimaryProtocol(['RESPONSES'])).toBe('RESPONSES')
    // 两个都启用时按 ALL_WIRE_PROTOCOLS 的顺序取，Messages 在 Responses 之前
    // —— 与翻译回退优先级同序。
    expect(resolvePrimaryProtocol(['RESPONSES', 'MESSAGES'])).toBe('MESSAGES')
  })

  it('一个都没启用时仍给 Chat，不留无行可显的状态', () => {
    expect(resolvePrimaryProtocol([])).toBe('CHAT')
  })
})

describe('orderProtocolRows', () => {
  it('首行为 Chat 时按优先级序排列', () => {
    expect(orderProtocolRows('CHAT')).toEqual([...ALL_WIRE_PROTOCOLS])
  })

  it('首行为其它协议时它被提到最前，其余保持原顺序', () => {
    expect(orderProtocolRows('MESSAGES')).toEqual(['MESSAGES', 'CHAT', 'RESPONSES'])
    expect(orderProtocolRows('RESPONSES')).toEqual(['RESPONSES', 'CHAT', 'MESSAGES'])
  })

  /**
   * 全部行总是都在，只是次序不同 —— 未勾选的协议也要有输入行，
   * 否则用户没法在勾选之前先填好地址。
   */
  it('每种首行选择都产出全部协议且不重复', () => {
    for (const primary of ALL_WIRE_PROTOCOLS) {
      const rows = orderProtocolRows(primary)
      expect(rows).toHaveLength(ALL_WIRE_PROTOCOLS.length)
      expect(new Set(rows).size).toBe(ALL_WIRE_PROTOCOLS.length)
      expect(rows[0]).toBe(primary)
      expect(rows).toEqual(expect.arrayContaining([...ALL_WIRE_PROTOCOLS]))
    }
  })
})

describe('resolveModelPullTarget', () => {
  const chat = 'https://chat.example/v1'
  const responses = 'https://resp.example/v1'
  const ant = 'https://ant.example/v1'
  const all = baseUrls({ CHAT: chat, RESPONSES: responses, MESSAGES: ant })

  // 三个协议的模型列表端点路径完全相同，无法从响应判断上游以哪种协议作答，
  // 所以「让用户选」没有参考价值 —— 按固定优先级取是唯一不需要用户判断的规则。
  it('全部启用时用 Chat', () => {
    expect(resolveModelPullTarget([...ALL_WIRE_PROTOCOLS], all))
      .toEqual({ protocol: 'CHAT', baseUrl: chat })
  })

  it('只启用 Chat 时用 Chat', () => {
    expect(resolveModelPullTarget(['CHAT'], all)).toEqual({ protocol: 'CHAT', baseUrl: chat })
  })

  it('只启用 Messages 时退到 Messages 地址', () => {
    expect(resolveModelPullTarget(['MESSAGES'], all))
      .toEqual({ protocol: 'MESSAGES', baseUrl: ant })
  })

  /**
   * Chat 未启用时优先 Responses 而非 Messages。
   *
   * 依据是**请求头相似度**：Responses 与 Chat 同为 OpenAI 系（同一个
   * `Authorization: Bearer`、不需要 `anthropic-version`），因此退到它比退到
   * Messages 少一处请求头差异。这个顺序与展示序数值相同但理由不同。
   */
  it('Chat 未启用时优先 Responses 而非 Messages', () => {
    expect(resolveModelPullTarget(['RESPONSES', 'MESSAGES'], all))
      .toEqual({ protocol: 'RESPONSES', baseUrl: responses })
  })

  // 界面上「留空则与 Chat 相同」是一句提示，这里必须是同一条规则，
  // 否则用户会看到「提示说相同，但拉取报地址为空」。
  it('协议专属地址为空时回退到 Chat 地址', () => {
    expect(resolveModelPullTarget(['MESSAGES'], baseUrls({ CHAT: chat, MESSAGES: '   ' })))
      .toEqual({ protocol: 'MESSAGES', baseUrl: chat })
    expect(resolveModelPullTarget(['RESPONSES'], baseUrls({ CHAT: chat })))
      .toEqual({ protocol: 'RESPONSES', baseUrl: chat })
  })

  // 随便挑一个地址发出去，会让「没启用协议」这个真正的问题被一个上游错误掩盖。
  it('一个都没启用时返回 null 交由调用方提示', () => {
    expect(resolveModelPullTarget([], all)).toBeNull()
  })

  it('地址两侧空白被去掉', () => {
    expect(resolveModelPullTarget(['CHAT'], baseUrls({ CHAT: `  ${chat}  ` }))?.baseUrl)
      .toBe(chat)
  })
})
