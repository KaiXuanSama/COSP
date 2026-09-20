import { describe, expect, it } from 'vitest'

import { countRules, createDefaultRequestBodyEditorState, createRuleGroup } from './editorState'
import { RULE_ENGINE_WIRE_PROTOCOLS, isWireProtocol } from '@/types/protocol'

/**
 * 请求体规则编辑器的初始状态契约。
 *
 * <p>这里钉的是「新建出来的东西能被后端接受」—— 那是一个跨前后端的约束，
 * 而它唯一的失败形态是用户新建一个规则组、什么都没改就保存失败。
 */

describe('createRuleGroup', () => {
  /**
   * 新组默认适用于规则引擎支持的全部协议。
   *
   * <p>断言比对 `RULE_ENGINE_WIRE_PROTOCOLS` 而非写死字面量：默认值的语义就是
   * 「那个集合的全部」，而该集合受后端白名单约束、会随功能推进变化。
   */
  it('默认协议集等于规则引擎支持的全部协议', () => {
    expect(createRuleGroup(0).protocols).toEqual([...RULE_ENGINE_WIRE_PROTOCOLS])
  })

  /**
   * 默认协议里必须含 `RESPONSES`。
   *
   * <p>单独钉这一条是因为它是最新加入的，且加入过程中前后端白名单曾短暂不同步 ——
   * 那时新建规则组会在保存时收到 400。这条断言让「后端放开了但前端漏改」立刻可见。
   */
  it('默认协议含 Responses', () => {
    expect(createRuleGroup(0).protocols).toContain('RESPONSES')
  })

  /** 每一项都是合法协议标识，否则后端会以「不支持的线路协议」拒掉保存。 */
  it('默认协议每一项都合法', () => {
    for (const protocol of createRuleGroup(0).protocols) {
      expect(isWireProtocol(protocol)).toBe(true)
    }
  })

  /** 组 ID 必须互不相同：前端用它做列表 key，后端也会拒绝重复 ID。 */
  it('每次创建的组 ID 都不同', () => {
    expect(createRuleGroup(0).id).not.toBe(createRuleGroup(0).id)
  })

  /** order 取自入参，名称按序号编号（1 起，面向用户）。 */
  it('order 与默认名称跟随入参序号', () => {
    const second = createRuleGroup(1)
    expect(second.order).toBe(1)
    expect(second.name).toBe('规则组 2')
  })

  /** 新组默认启用且不含规则 —— 建完就是一个「什么都不做」的空组。 */
  it('新组默认启用且无规则', () => {
    const created = createRuleGroup(0)
    expect(created.enabled).toBe(true)
    expect(created.rules).toEqual([])
  })
})

describe('createDefaultRequestBodyEditorState', () => {
  /**
   * 默认状态不含任何规则组。
   *
   * <p>新供应商多数无需请求体改造，凭空给一个空组会让「已配置 0 条规则」变成
   * 「已配置 1 个组 0 条规则」，反而要求用户先去删掉它。
   */
  it('默认不含任何规则组', () => {
    const state = createDefaultRequestBodyEditorState()
    expect(state.rules.version).toBe(2)
    expect(state.rules.groups).toEqual([])
    expect(countRules(state.rules)).toBe(0)
  })
})
