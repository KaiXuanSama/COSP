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
    expect(presetRuleSetV2(MIMO_EXAMPLE_RULESET).groups[0].protocols).toEqual(['OPENAI'])
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

describe('toPresetFormValues', () => {
  it('展开名称、地址与请求头', () => {
    const values = toPresetFormValues(findPreset('MiMo')!)
    expect(values.displayName).toBe('MiMo')
    expect(values.baseUrl).toBe('https://api.xiaomimimo.com/v1')
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
    expect(state.rules.groups[0].protocols).toEqual(['OPENAI'])
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
