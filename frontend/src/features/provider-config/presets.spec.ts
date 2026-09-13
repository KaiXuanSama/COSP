import { describe, expect, it } from 'vitest'
import { MIMO_EXAMPLE_RULESET } from '@/features/request-body-rules/defaultRequestBody'
import {
  aggregatorPresets,
  allPresets,
  createProviderDefaultEditorState,
  findPreset,
  officialPresets,
  presetRuleSetV2,
  relayPresets,
  toPresetFormValues,
} from './presets'
import { toProviderKey } from './providerKey'

describe('预设清单', () => {
  it('三类合并为全集', () => {
    expect(allPresets).toHaveLength(
      officialPresets.length + aggregatorPresets.length + relayPresets.length,
    )
  })

  it('标签唯一，避免 findPreset 命中歧义', () => {
    const labels = allPresets.map(p => p.label)
    expect(new Set(labels).size).toBe(labels.length)
  })

  it('派生出的 providerKey 也唯一', () => {
    // 两个预设若派生出相同 key，后端会以「名称已存在」拒绝第二个。
    const keys = allPresets.map(p => toProviderKey(p.label))
    expect(new Set(keys).size).toBe(keys.length)
  })

  it('每个预设都有非空 baseUrl 且为 http(s)', () => {
    for (const preset of allPresets) {
      expect(preset.baseUrl, preset.label).toMatch(/^https?:\/\//)
    }
  })

  it('AgentRouter 保留删除 Accept-Encoding 的规则', () => {
    // 该站对 SSE 做 Brotli 压缩，不删这个头会导致解码失败。
    const agentRouter = findPreset('AgentRouter')
    expect(agentRouter?.headers).toEqual(
      expect.arrayContaining([{ key: 'Accept-Encoding', value: '/del/' }]),
    )
  })
})

describe('findPreset', () => {
  it('按标签命中', () => {
    expect(findPreset('MiMo')?.baseUrl).toBe('https://api.xiaomimimo.com/v1')
  })

  it('未命中返回 undefined', () => {
    expect(findPreset('NotExist')).toBeUndefined()
  })
})

describe('presetRuleSetV2', () => {
  it('把 V1 规则集升为单组 V2', () => {
    const ruleSet = presetRuleSetV2(MIMO_EXAMPLE_RULESET)
    expect(ruleSet.version).toBe(2)
    expect(ruleSet.groups).toHaveLength(1)
    expect(ruleSet.groups[0].rules).toHaveLength(MIMO_EXAMPLE_RULESET.rules.length)
  })

  // 预设规则的字段路径是照 OpenAI 请求体写的，作用在 Anthropic 上多数匹配不到。
  // 只勾 OPENAI 是刻意的：静默失效比不执行更难排查。
  it('规则组只适用 OpenAI 线路', () => {
    expect(presetRuleSetV2(MIMO_EXAMPLE_RULESET).groups[0].protocols).toEqual(['CHAT'])
  })

  it('组内带上图片兼容模板与对应预览样本', () => {
    const group = presetRuleSetV2(MIMO_EXAMPLE_RULESET).groups[0]
    expect(group.templateKeys).toContain('message-tool-image')
    expect(Object.keys(group.previewBody).length).toBeGreaterThan(0)
  })

  it('不修改传入的 V1 规则集', () => {
    const before = MIMO_EXAMPLE_RULESET.rules.length
    const ruleSet = presetRuleSetV2(MIMO_EXAMPLE_RULESET)
    ruleSet.groups[0].rules.push({} as never)
    expect(MIMO_EXAMPLE_RULESET.rules.length).toBe(before)
  })
})

/**
 * MiMo 系预设的三组规则。
 *
 * 后两组来自 2026-09-13 对 MiMo 网关的实测：它在 Responses 协议上拒收托管
 * `web_search`（hard 400）与 `text.format: json_schema`。cc-switch 对前者有
 * 同源黑名单，但那条路按主机名/模型名前缀匹配，经过 COSP 后两者都失配
 * （localhost + `[mimo-tokenplan] mimo-v2.5`），只能在 COSP 侧摘掉。
 */
describe('MiMo 预设的完整规则集', () => {
  const mimoLabels = ['MiMo', 'Mimo (TokenPlan)']

  it('官方直连与 TokenPlan 都带三个规则组', () => {
    for (const label of mimoLabels) {
      const rules = findPreset(label)!.createRules!()
      expect(rules.groups, label).toHaveLength(3)
    }
  })

  it('图片兼容组仍只适用 Chat，两个新组只适用 Responses', () => {
    const groups = findPreset('Mimo (TokenPlan)')!.createRules!().groups
    expect(groups[0].protocols).toEqual(['CHAT'])
    expect(groups[1].protocols).toEqual(['RESPONSES'])
    expect(groups[2].protocols).toEqual(['RESPONSES'])
  })

  /** 运行时按 order 排序执行、界面按数组渲染，两者不一致会让顺序分叉。 */
  it('order 与数组下标严格一致', () => {
    const groups = findPreset('Mimo (TokenPlan)')!.createRules!().groups
    groups.forEach((group, index) => {
      expect(group.order, group.name).toBe(index)
    })
  })

  it('组 ID 互不重复', () => {
    const ids = findPreset('Mimo (TokenPlan)')!.createRules!().groups.map(g => g.id)
    expect(new Set(ids).size).toBe(ids.length)
  })

  /**
   * web_search 组必须删元素而不是整个 `tools` 字段。
   *
   * Codex 同时发十来个工具（shell、apply_patch 等），删掉整个字段会毁掉编辑能力。
   * 判据就是 `array: true` —— 数组模式下 `delete` 作用于匹配到的元素。
   */
  it('web_search 组按元素删除且条件以元素为作用域', () => {
    const group = findPreset('Mimo (TokenPlan)')!.createRules!().groups[1]
    const rule = group.rules[0]
    expect(rule.field).toBe('tools')
    expect(rule.array).toBe(true)
    expect(rule.operations[0].type).toBe('delete')
    // 数组模式下条件以元素为作用域；写成 ./tools[*]/type 会恒不命中且不告警。
    expect(rule.conditions[0].path).toBe('./type')
    expect(rule.conditions[0].value).toBe('web_search')
  })

  /**
   * text.format 组必须整体替换 `format` 对象。
   *
   * 官方 `json_object` 形态里**只有 `type`**，而 `json_schema` 还带
   * `name` / `schema` / `strict` —— 只改 type 会留下矛盾的多余字段。
   */
  it('text_format 组整体替换 format 为 json_object', () => {
    const group = findPreset('Mimo (TokenPlan)')!.createRules!().groups[2]
    const rule = group.rules[0]
    expect(rule.field).toBe('text')
    expect(rule.array).toBe(false)
    // 标量模式下条件从根写起 —— 与数组模式的 ./type 恰好相反。
    expect(rule.conditions[0].path).toBe('./text/format/type')
    expect(rule.conditions[0].value).toBe('json_schema')

    const nested = rule.operations[0].rules![0]
    expect(nested.field).toBe('format')
    expect(nested.operations[0].type).toBe('set_value')
    expect(nested.operations[0].value).toEqual({ type: 'json_object' })
  })

  /**
   * 两次取用互不共享引用。
   *
   * 预设是模块级常量，少一次深拷贝就会让用户在编辑器里的改动原地污染它，
   * 进而影响之后所有套用同一预设的供应商。这是本文件最容易踩的污染点。
   */
  it('每次取用都是全新对象', () => {
    const first = findPreset('Mimo (TokenPlan)')!.createRules!()
    const second = findPreset('Mimo (TokenPlan)')!.createRules!()

    first.groups[1].rules[0].field = '__polluted__'
    first.groups[2].rules.push({} as never)
    ;(first.groups[1].previewBody as { tools: unknown[] }).tools.push({})

    expect(second.groups[1].rules[0].field).toBe('tools')
    expect(second.groups[2].rules).toHaveLength(1)
    expect((second.groups[1].previewBody as { tools: unknown[] }).tools).toHaveLength(2)
  })

  /**
   * 每组都要有非空调试样本。
   *
   * `custom` 模板键自身不产生内容，所以样本必须显式给。留空对象会让预览两栏
   * 都是 `{}` —— 规则改成什么样都看不出效果，编辑器等于瞎的。
   */
  it('每个组都有非空 previewBody', () => {
    for (const group of findPreset('Mimo (TokenPlan)')!.createRules!().groups) {
      expect(Object.keys(group.previewBody).length, group.name).toBeGreaterThan(0)
    }
  })

  /**
   * 样本必须真能触发本组规则，否则预览是「无变化」。
   *
   * 这两条断言比「非空」更严：样本里得有规则实际针对的字段与取值。
   * web_search 组还要求样本里**同时**有一个非 web_search 工具 ——
   * 只有这样才能在预览里看出「只删匹配项、其余保留」，而非
   * 「删掉整个 tools」（两者在只含 web_search 的样本上结果相同）。
   */
  it('web_search 组的样本含一个待删工具与一个保留工具', () => {
    const preview = findPreset('Mimo (TokenPlan)')!.createRules!().groups[1].previewBody
    const tools = (preview as { tools: Array<{ type: string }> }).tools
    expect(tools.map(t => t.type)).toContain('web_search')
    expect(tools.some(t => t.type !== 'web_search')).toBe(true)
  })

  /** text.format 组的样本必须带完整 json_schema 形态，才能显出「整体替换」。 */
  it('text_format 组的样本含完整 json_schema 形态', () => {
    const preview = findPreset('Mimo (TokenPlan)')!.createRules!().groups[2].previewBody
    const format = (preview as { text: { format: Record<string, unknown> } }).text.format
    expect(format.type).toBe('json_schema')
    // 这三个字段是「只改 type 不够」的证据 —— 它们会随整体替换一起消失。
    expect(Object.keys(format)).toEqual(expect.arrayContaining(['name', 'schema', 'strict']))
  })

  /**
   * 只给 MiMo，不铺给其它供应商。
   *
   * 后两条规则针对的是 MiMo 网关的能力子集 —— 实测事实而非通用缺陷。
   * LongCat / MiniMax 虽同被 cc-switch 列入 web_search 黑名单，但未经 COSP 实测，
   * 铺开等于把猜测写成预设。
   */
  it('其它带图片兼容的预设仍是单组', () => {
    for (const label of ['LongCat', 'Kimi', 'AgentRouter']) {
      const rules = findPreset(label)!.createRules!()
      expect(rules.groups, label).toHaveLength(1)
      expect(rules.groups[0].protocols, label).toEqual(['CHAT'])
    }
  })
})

describe('toPresetFormValues', () => {
  it('展开名称、地址与请求头', () => {
    const values = toPresetFormValues(findPreset('MiMo')!)
    expect(values.displayName).toBe('MiMo')
    expect(values.baseUrl).toBe('https://api.xiaomimimo.com/v1')
  })

  /**
   * 两个协议专属地址在预设未声明时都回退为 Chat 地址。
   *
   * 回退而不留空是因为空字符串会触发输入框联动 —— 那会让用户选了预设后一碰
   * Chat 地址框就把那一格重写掉，看上去像预设没生效。
   *
   * Responses 也照抄，尽管多数中转站没有这个端点：这里填的是「用哪个地址」，
   * 而「用不用」由协议勾选决定。
   */
  it('预设未单独声明协议地址时与 Chat 同源', () => {
    const values = toPresetFormValues(findPreset('MiMo')!)
    expect(values.anthropicBaseUrl).toBe('https://api.xiaomimimo.com/v1')
    expect(values.responsesBaseUrl).toBe('https://api.xiaomimimo.com/v1')
  })

  it('预设显式声明的协议地址优先', () => {
    const values = toPresetFormValues({
      label: 'Split',
      baseUrl: 'https://chat.example/v1',
      anthropicBaseUrl: 'https://anthropic.example',
      responsesBaseUrl: 'https://responses.example',
      headers: [],
    })
    expect(values.baseUrl).toBe('https://chat.example/v1')
    expect(values.anthropicBaseUrl).toBe('https://anthropic.example')
    expect(values.responsesBaseUrl).toBe('https://responses.example')
  })

  /** 一个声明、一个未声明时各按各自的规则，不会互相影响。 */
  it('只声明其中一个时另一个仍回退到 Chat', () => {
    const values = toPresetFormValues({
      label: 'Partial',
      baseUrl: 'https://chat.example/v1',
      responsesBaseUrl: 'https://responses.example',
      headers: [],
    })
    expect(values.anthropicBaseUrl).toBe('https://chat.example/v1')
    expect(values.responsesBaseUrl).toBe('https://responses.example')
  })

  it('请求头为拷贝，编辑不回写预设常量', () => {
    const preset = findPreset('AgentRouter')!
    const values = toPresetFormValues(preset)
    values.headers[0].value = 'mutated'
    expect(preset.headers[0].value).not.toBe('mutated')
  })

  // 全部带规则的预设都由同一个工厂产出，而工厂内部读的是同一份
  // MIMO_EXAMPLE_RULESET。这是最容易踩的污染点：少一次拷贝，
  // 编辑一个供应商的规则就会影响之后所有使用该预设的供应商。
  it('规则集为新对象，编辑不污染共享常量', () => {
    const before = MIMO_EXAMPLE_RULESET.rules.length
    const values = toPresetFormValues(findPreset('MiMo')!)
    values.editorState.rules.groups[0].rules.push({} as never)
    expect(MIMO_EXAMPLE_RULESET.rules.length).toBe(before)
  })

  it('两次展开互不共享引用', () => {
    const a = toPresetFormValues(findPreset('MiMo')!)
    const b = toPresetFormValues(findPreset('MiMo')!)
    expect(a.editorState.rules).not.toBe(b.editorState.rules)
    expect(a.editorState.rules.groups[0]).not.toBe(b.editorState.rules.groups[0])
  })

  // 无规则的预设不该凭空得到一个空规则组，否则界面会显示
  // 「已配置 1 个组 0 条规则」，用户还得先去删掉它。
  it('未配置规则的预设回退为不含任何规则组', () => {
    expect(toPresetFormValues(findPreset('DeepSeek')!).editorState.rules.groups).toEqual([])
  })

  it('配置了规则的预设带上图片兼容模板', () => {
    const values = toPresetFormValues(findPreset('MiMo')!)
    expect(values.editorState.rules.groups[0].templateKeys).toContain('message-tool-image')
  })
})

describe('createProviderDefaultEditorState', () => {
  it('默认带一个仅适用 OpenAI 的图片兼容规则组', () => {
    const state = createProviderDefaultEditorState()
    expect(state.rules.groups).toHaveLength(1)
    expect(state.rules.groups[0].protocols).toEqual(['CHAT'])
    expect(state.rules.groups[0].templateKeys).toContain('message-tool-image')
  })

  it('每次调用产出独立对象，不共享可变规则集', () => {
    const a = createProviderDefaultEditorState()
    const b = createProviderDefaultEditorState()
    expect(a.rules).not.toBe(b.rules)
    a.rules.groups[0].rules.push({} as never)
    expect(b.rules.groups[0].rules.length).not.toBe(a.rules.groups[0].rules.length)
  })
})
