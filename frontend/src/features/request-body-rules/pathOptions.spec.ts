import { describe, it, expect } from 'vitest'
import {
  firstObjectElement,
  generatePathOptions,
  resolveConditionScope,
} from './pathOptions'
import type { FieldRule } from './types'

/** 造一条规则；只指定关心的字段，其余给出无副作用的值。 */
function rule(patch: Partial<FieldRule>): FieldRule {
  return {
    id: 'r',
    order: 0,
    field: '',
    array: false,
    conditional: true,
    conditionMode: 'all',
    conditions: [],
    operations: [],
    ...patch,
  }
}

describe('firstObjectElement', () => {
  it('取第一个对象元素', () => {
    expect(firstObjectElement([{ a: 1 }, { b: 2 }])).toEqual({ a: 1 })
  })

  /**
   * 首个是标量时继续往后找。
   *
   * 上游的数组常混有字符串与对象（如 `content: [{type:"text"}, "plain"]`），
   * 首个是标量不代表没有可用的形状。
   */
  it('跳过标量元素继续找', () => {
    expect(firstObjectElement(['plain', { a: 1 }])).toEqual({ a: 1 })
  })

  /** 嵌套数组不是对象元素 —— `[[1]]` 里的 `[1]` 不能当作用域。 */
  it('跳过嵌套数组', () => {
    expect(firstObjectElement([[1], { a: 2 }])).toEqual({ a: 2 })
  })

  it('非数组与全标量返回 null', () => {
    expect(firstObjectElement({ a: 1 })).toBeNull()
    expect(firstObjectElement(['a', 'b'])).toBeNull()
    expect(firstObjectElement([])).toBeNull()
    expect(firstObjectElement(null)).toBeNull()
  })
})

/**
 * 条件作用域。
 *
 * <h2>为什么这组用例是本次修复的核心</h2>
 * 编辑器此前一律从 `scopeObject` 生成条件路径，而引擎在数组模式下以**元素**为作用域
 * 求值 —— 于是下拉给出 `./tools[*]/type`，引擎解析时第一步找 `tools` 字段就找不到，
 * 直接返回 null。规则**永不命中且零告警**，症状是「配了规则但看起来完全没生效」，
 * 与「字段本来就不存在」这一正常情形无法区分。
 *
 * <p>这四条分支（顶层/内层 × 数组/标量）此前零测试覆盖：逻辑长在 SFC 里，
 * 而前端只对 `features/**` 下的纯模块做单测。抽出来才有地方钉住它们。
 */
describe('resolveConditionScope', () => {
  /** 顶层 + 标量：作用域是整个请求体，路径形如 `./text/format/type`。 */
  it('顶层标量规则的作用域是请求体本身', () => {
    const body = { text: { format: { type: 'json_schema' } }, model: 'x' }
    const scope = resolveConditionScope(body, rule({ field: 'text', array: false }))

    // 作用域是 text 的**值**而非整个请求体 —— 条件判断发生在「已进入 text 之后」。
    expect(scope).toEqual({ format: { type: 'json_schema' } })
    expect(generatePathOptions(scope!).map(o => o.value)).toContain('./format/type')
  })

  /**
   * 顶层 + 数组：作用域是**元素**，不是数组所在的对象。
   *
   * 这条是本缺陷的正身。作用域必须是 `{type:'web_search', ...}`，
   * 生成的路径才是 `./type`。
   */
  it('顶层数组规则的作用域是元素而非数组', () => {
    const body = {
      tools: [
        { type: 'web_search', external_web_access: false },
        { type: 'function', name: 'shell' },
      ],
    }
    const scope = resolveConditionScope(body, rule({ field: 'tools', array: true }))

    expect(scope).toEqual({ type: 'web_search', external_web_access: false })
    expect(generatePathOptions(scope!).map(o => o.value)).toEqual(['./type', './external_web_access'])
  })

  /**
   * 关键回归：数组模式下**不得**生成 `./tools[*]/type`。
   *
   * 那个路径是引擎解析不了的形态（`parsePath` 只认段尾带 `[*]` 的段，
   * 而它在中间），命中不了且零告警。用一个反向断言钉死它不再出现。
   */
  it('数组模式下不生成含 [*] 的根相对路径', () => {
    const body = { tools: [{ type: 'web_search' }] }
    const scope = resolveConditionScope(body, rule({ field: 'tools', array: true }))
    const paths = generatePathOptions(scope!).map(o => o.value)

    expect(paths).not.toContain('./tools[*]/type')
    expect(paths.some(p => p.startsWith('./tools'))).toBe(false)
    expect(paths).toContain('./type')
  })

  /** 内层 + 标量：作用域是外层字段的值对象，路径形如 `./format/type`。 */
  it('edit_object 内层标量规则的作用域是外层字段的值', () => {
    // 内层规则拿到的 scopeObject 已经是 text 的值。
    const scope = resolveConditionScope(
      { format: { type: 'json_schema' }, verbosity: 'medium' },
      rule({ field: 'format', array: false }),
    )

    expect(scope).toEqual({ type: 'json_schema' })
    expect(generatePathOptions(scope!).map(o => o.value)).toEqual(['./type'])
  })

  /** 内层 + 数组：作用域同样是元素。 */
  it('edit_object 内层数组规则的作用域是元素', () => {
    const scope = resolveConditionScope(
      { content: [{ type: 'text', text: 'hi' }] },
      rule({ field: 'content', array: true }),
    )

    expect(scope).toEqual({ type: 'text', text: 'hi' })
  })

  /** 数组为空、元素全是标量、字段不存在时都返回 null（调用方据此提示手工填写）。 */
  it('无法推断时返回 null', () => {
    const body = { tools: [], tags: ['a'], model: 'x' }

    // vitest 的 expect 没有 Jest 的 `.as()` —— 用 `message` 参数标注各分支。
    expect(resolveConditionScope(body, rule({ field: 'tools', array: true })), '空数组')
      .toBeNull()
    expect(resolveConditionScope(body, rule({ field: 'tags', array: true })), '元素全是标量')
      .toBeNull()
    expect(resolveConditionScope(body, rule({ field: 'missing', array: true })), '字段不存在')
      .toBeNull()
    expect(resolveConditionScope(body, rule({ field: 'model', array: false })), '标量模式下字段是标量')
      .toBeNull()
  })

  /** 没有样本或没有字段名时无从推断 —— 这是「预览样本为空」的常态而非错误。 */
  it('缺少样本或字段名时返回 null', () => {
    expect(resolveConditionScope(null, rule({ field: 'tools', array: true }))).toBeNull()
    expect(resolveConditionScope({}, rule({ field: '', array: true }))).toBeNull()
  })
})

describe('generatePathOptions', () => {
  it('标量字段直接成为选项', () => {
    expect(generatePathOptions({ a: 1, b: 'x' }).map(o => o.value)).toEqual(['./a', './b'])
  })

  it('数组字段给出 [*] 后缀并展开元素的子字段', () => {
    const paths = generatePathOptions({ messages: [{ role: 'user', content: [{ text: 'hi' }] }] })
      .map(o => o.value)

    expect(paths).toContain('./messages[*]')
    expect(paths).toContain('./messages[*]/role')
    // 元素内部的数组同样展开。
    expect(paths).toContain('./messages[*]/content[*]')
    expect(paths).toContain('./messages[*]/content[*]/text')
  })

  it('对象字段给出路径并递归展开', () => {
    const paths = generatePathOptions({ text: { format: { type: 'x' } } }).map(o => o.value)

    expect(paths).toEqual(['./text', './text/format', './text/format/type'])
  })

  /**
   * 深度上限防止深层嵌套把下拉撑爆。
   *
   * 上限是 2，因此 `./a/b/c/d` 这一层不再展开。
   */
  it('超过深度上限后不再下钻', () => {
    const paths = generatePathOptions({ a: { b: { c: { d: 1 } } } }).map(o => o.value)

    expect(paths).toEqual(['./a', './a/b', './a/b/c'])
    expect(paths).not.toContain('./a/b/c/d')
  })

  /**
   * 同名路径只出现一次。
   *
   * 数组的多个元素会展开出相同路径（`./messages[*]/role` 来自每一个元素），
   * 不去重会在下拉里堆出一长串重复项。
   */
  it('数组多元素展开出的同名路径去重', () => {
    const paths = generatePathOptions({
      messages: [{ role: 'user' }, { role: 'assistant' }, { role: 'tool' }],
    }).map(o => o.value)

    expect(paths.filter(p => p === './messages[*]/role')).toHaveLength(1)
  })

  /** 空对象给出空选项，不抛错。 */
  it('空对象返回空数组', () => {
    expect(generatePathOptions({})).toEqual([])
  })

  /** 前缀由递归传入，生成的路径始终以 `./` 开头（引擎的解析前提）。 */
  it('所有路径都以 ./ 开头', () => {
    const paths = generatePathOptions({
      a: { b: [1] },
      c: [{ d: { e: 1 } }],
    }).map(o => o.value)

    expect(paths.length).toBeGreaterThan(0)
    expect(paths.every(p => p.startsWith('./'))).toBe(true)
  })
})
