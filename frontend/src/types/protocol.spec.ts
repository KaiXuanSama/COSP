import { describe, expect, it } from 'vitest'

import {
  ALL_WIRE_PROTOCOLS,
  formatCallTypeLabel,
  formatCallTypeTitle,
  isWireProtocol,
  protocolAbbreviation,
  protocolDisplayName,
} from './protocol'

/**
 * 协议标识的展示层契约。
 *
 * <h2>为什么这个文件是后补的</h2>
 * 协议重命名时把三处重复的 `formatCallType` 收敛成共享函数，形参写作
 * `(upstream, downstream)` 却在两个调用点传成了 `(downstream, upstream)` ——
 * 于是同一条调用记录在消费者视图显示 `M→C`、在调用者视图显示 `C→M`。
 *
 * 那个缺陷穿过了全部 910 个前端用例，原因很直接：**这三个函数当时一个测试都没有**，
 * 而两个形参类型相同（都是 `WireProtocol`），编译器也无从发现。方向断言因此是本文件
 * 存在的首要理由。
 */

describe('protocolAbbreviation', () => {
  it('已知协议查表取缩写', () => {
    expect(protocolAbbreviation('CHAT')).toBe('C')
    expect(protocolAbbreviation('MESSAGES')).toBe('M')
  })

  it('大小写不敏感', () => {
    expect(protocolAbbreviation('chat')).toBe('C')
    expect(protocolAbbreviation('Messages')).toBe('M')
  })

  /**
   * 兜底取首字母，为「后端加了新协议、前端尚未同步」准备：宁可显示一个可读的字母，
   * 也不要空白或崩溃。
   */
  it('未知协议取首字母', () => {
    expect(protocolAbbreviation('RESPONSES')).toBe('R')
    expect(protocolAbbreviation('gemini')).toBe('G')
  })

  /**
   * 这个兜底在协议重命名时顺带兜过一次存量数据。迁移（服务重启）之前库里还是
   * 旧标识，查表落空走首字母，恰好得到与重命名前一致的 `O` / `A`。
   *
   * 那是巧合而非设计 —— 记在这里是为了防止有人把它当成「旧名兼容层」而依赖它。
   * 旧标识在 V12 迁移后已不存在于库中。
   */
  it('旧协议标识落到首字母兜底，恰好得到 O / A（巧合，非兼容设计）', () => {
    expect(protocolAbbreviation('OPENAI')).toBe('O')
    expect(protocolAbbreviation('ANTHROPIC')).toBe('A')
  })

  it('空值返回空串，让调用方能判断「整个标记不渲染」', () => {
    expect(protocolAbbreviation('')).toBe('')
  })
})

describe('protocolDisplayName', () => {
  it('取各自的官方叫法', () => {
    expect(protocolDisplayName('CHAT')).toBe('Chat Completions API')
    expect(protocolDisplayName('MESSAGES')).toBe('Anthropic API')
  })

  /** 未知标识原样返回：显示 `RESPONSES` 仍比显示空白有用。 */
  it('未知协议原样返回', () => {
    expect(protocolDisplayName('RESPONSES')).toBe('RESPONSES')
  })

  it('空值返回空串', () => {
    expect(protocolDisplayName('')).toBe('')
  })
})

describe('formatCallTypeLabel', () => {
  /**
   * <strong>本文件最重要的一组断言。</strong>
   *
   * 日志的箭头是「响应翻译」方向，即 <strong>上游 → 下游</strong>，与调用 Toast 的
   * `protocolPathLabel`（请求翻译，下游 → 上游）刻意相反。两者不统一是有意的：
   * 日志面向<strong>执行者</strong>（COSP 自己就是翻译者，它记录「上游给了什么、
   * 我译成什么交给下游」），Toast 面向<strong>调用者</strong>（「我的请求被译成什么
   * 发出去了」）。
   *
   * 因此形参顺序是 `源 → 目标`，与产出的箭头同序。掉个头就是那个已修复的缺陷。
   */
  it('跨协议时箭头是上游→下游（响应翻译方向）', () => {
    // 下游 Chat + 上游 Anthropic 的那条链（C2M 去程 / M2C 回程）：
    // 日志记录的是回程，因此显示 M→C。
    expect(formatCallTypeLabel('MESSAGES', 'CHAT', true)).toBe('流式: M→C')
    expect(formatCallTypeLabel('CHAT', 'MESSAGES', true)).toBe('流式: C→M')
  })

  /** 同协议只需回答「说的哪种话」，展示名比缩写好读，且没有翻译发生。 */
  it('同协议直连给展示名而非箭头', () => {
    expect(formatCallTypeLabel('CHAT', 'CHAT', true)).toBe('流式: Chat Completions API')
    expect(formatCallTypeLabel('MESSAGES', 'MESSAGES', false)).toBe('非流: Anthropic API')
  })

  it('流式标记随 isStream，数字与布尔都接受', () => {
    expect(formatCallTypeLabel('CHAT', 'CHAT', 1)).toContain('流式')
    expect(formatCallTypeLabel('CHAT', 'CHAT', 0)).toContain('非流')
    expect(formatCallTypeLabel('CHAT', 'CHAT', true)).toContain('流式')
    expect(formatCallTypeLabel('CHAT', 'CHAT', false)).toContain('非流')
  })

  /** 后端加第三种协议时，前端在同步改动之前也要给出可读结果。 */
  it('未知协议走缩写兜底', () => {
    expect(formatCallTypeLabel('RESPONSES', 'CHAT', true)).toBe('流式: R→C')
    expect(formatCallTypeLabel('RESPONSES', 'RESPONSES', true)).toBe('流式: RESPONSES')
  })
})

describe('formatCallTypeTitle', () => {
  /**
   * 气泡存在的唯一理由：缩写箭头 `M→C` 无法自解释，而它与 Toast 上的 `C→M` 方向
   * 相反。开头点明「响应翻译」，读者不必记住哪个界面用哪个方向。
   */
  it('跨协议写明是响应翻译并给出两侧全名', () => {
    expect(formatCallTypeTitle('MESSAGES', 'CHAT'))
      .toBe('响应翻译 Anthropic API → Chat Completions API（上游 → 下游）')
  })

  /**
   * 直连时不写箭头也不提翻译：那次调用根本没有翻译发生，写成「响应翻译 X → X」
   * 会凭空暗示有一层转换。
   */
  it('同协议说明直连，不出现箭头与「翻译」字样', () => {
    const title = formatCallTypeTitle('CHAT', 'CHAT')
    expect(title).toBe('上游与下游同为 Chat Completions API，直连无需翻译')
    expect(title).not.toContain('→')
    expect(title).not.toContain('翻译 ')
  })

  it('上游未知时按直连处理，不出现半截箭头', () => {
    expect(formatCallTypeTitle('CHAT', '')).not.toContain('→')
  })

  it('源协议缺失时返回空串（整个 title 不渲染）', () => {
    expect(formatCallTypeTitle('', 'CHAT')).toBe('')
    expect(formatCallTypeTitle('', '')).toBe('')
  })
})

describe('isWireProtocol', () => {
  it('只认当前的两个标识', () => {
    expect(isWireProtocol('CHAT')).toBe(true)
    expect(isWireProtocol('MESSAGES')).toBe(true)
  })

  /**
   * 旧标识必须判为非法。它们在 V12 迁移后已不存在于库中，若这里放行，
   * 「读不懂就回退全集」那条兜底就永远发现不了脏数据。
   */
  it('旧标识与未知值一律非法', () => {
    expect(isWireProtocol('OPENAI')).toBe(false)
    expect(isWireProtocol('ANTHROPIC')).toBe(false)
    expect(isWireProtocol('RESPONSES')).toBe(false)
    expect(isWireProtocol('chat')).toBe(false)
    expect(isWireProtocol(null)).toBe(false)
    expect(isWireProtocol(42)).toBe(false)
  })
})

describe('ALL_WIRE_PROTOCOLS', () => {
  /**
   * 顺序即界面顺序（协议多选、地址行排列）。固定下来是因为多处按它排序，
   * 顺序变了会让界面上两行输入框互换位置。
   */
  it('顺序固定为 CHAT 在前', () => {
    expect(ALL_WIRE_PROTOCOLS).toEqual(['CHAT', 'MESSAGES'])
  })

  it('每个成员都能通过类型守卫，且都有缩写与展示名', () => {
    for (const protocol of ALL_WIRE_PROTOCOLS) {
      expect(isWireProtocol(protocol)).toBe(true)
      expect(protocolAbbreviation(protocol)).toHaveLength(1)
      expect(protocolDisplayName(protocol)).not.toBe(protocol)
    }
  })
})
