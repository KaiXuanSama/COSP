import { describe, expect, it } from 'vitest'
import { describeProviderKey, toProviderKey } from './providerKey'

/**
 * 这些期望值不是推导出来的，而是**实际运行后端表达式**取得的：
 *
 *   n.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "")
 *
 * 若后端 `ProviderAdminService#toProviderKey` 有任何改动，本文件应当先失败。
 */
describe('toProviderKey', () => {
  it('小写化并保留纯字母数字名称', () => {
    expect(toProviderKey('MiMo')).toBe('mimo')
    expect(toProviderKey('a')).toBe('a')
  })

  it('把括号与空格折叠成单个连字符', () => {
    expect(toProviderKey('Kimi (CodePlan)')).toBe('kimi-codeplan')
    expect(toProviderKey('Mimo (TokenPlan)')).toBe('mimo-tokenplan')
  })

  it('连续分隔符折叠为一个，并去掉首尾连字符', () => {
    expect(toProviderKey('  Spaced  Name  ')).toBe('spaced-name')
    expect(toProviderKey('Agent__Router')).toBe('agent-router')
    expect(toProviderKey('Zhipu-AI_v2')).toBe('zhipu-ai-v2')
  })

  it('全为分隔符时得到空串', () => {
    expect(toProviderKey('---')).toBe('')
  })

  // 非 ASCII 在 toLowerCase 之后不属于 [a-z0-9]，会被整段折叠。
  // 纯中文名因此退化为空串 —— 调用方必须处理，否则会拿空 key 去请求。
  it('非 ASCII 字符被折叠，纯中文名退化为空串', () => {
    expect(toProviderKey('已有中文')).toBe('')
    expect(toProviderKey('MiMo自定义')).toBe('mimo')
  })
})

describe('describeProviderKey', () => {
  describe('blank', () => {
    it('空名称与纯空白都不给提示，也不可提交', () => {
      expect(describeProviderKey('')).toMatchObject({ status: 'blank', key: '', submittable: false })
      expect(describeProviderKey('   ')).toMatchObject({ status: 'blank', submittable: false })
    })
  })

  describe('ok', () => {
    it('纯 ASCII 名称无损失', () => {
      expect(describeProviderKey('MiMo')).toMatchObject({
        status: 'ok',
        key: 'mimo',
        droppedChars: [],
        submittable: true,
      })
    })

    // 括号与空格是分隔符，本就该折叠成 -，不该报有损，
    // 否则内置预设里的 Kimi (CodePlan) 这类名称会被误报。
    it('括号与空格等分隔符不算损失', () => {
      expect(describeProviderKey('Kimi (CodePlan)')).toMatchObject({
        status: 'ok',
        key: 'kimi-codeplan',
        droppedChars: [],
      })
      expect(describeProviderKey('Zhipu-AI_v2')).toMatchObject({ status: 'ok', key: 'zhipu-ai-v2' })
    })
  })

  describe('unavailable', () => {
    it('纯中文名无法生成标识', () => {
      expect(describeProviderKey('深度求索')).toMatchObject({
        status: 'unavailable',
        key: '',
        submittable: false,
      })
    })

    it('全角字母同样无法生成标识', () => {
      expect(describeProviderKey('ＭｉＭｏ')).toMatchObject({ status: 'unavailable', submittable: false })
    })

    it('纯分隔符无法生成标识', () => {
      expect(describeProviderKey('---')).toMatchObject({ status: 'unavailable', submittable: false })
    })

    it('列出被丢弃的字符供界面提示', () => {
      expect(describeProviderKey('深度求索').droppedChars).toEqual(['深', '度', '求', '索'])
    })
  })

  describe('lossy', () => {
    it('中英混合时标识只保留 ASCII 部分', () => {
      expect(describeProviderKey('智谱AI')).toMatchObject({
        status: 'lossy',
        key: 'ai',
        droppedChars: ['智', '谱'],
        submittable: true,
      })
    })

    it('尾部中文被剪掉也算有损', () => {
      expect(describeProviderKey('MiMo中转')).toMatchObject({ status: 'lossy', key: 'mimo' })
    })

    it('数字保留时同样提示有损', () => {
      expect(describeProviderKey('供应商 2')).toMatchObject({ status: 'lossy', key: '2' })
    })

    it('丢弃字符去重且保持出现顺序', () => {
      expect(describeProviderKey('中中文文AB').droppedChars).toEqual(['中', '文'])
    })

    it('重音字母被丢弃', () => {
      expect(describeProviderKey('café')).toMatchObject({ status: 'lossy', key: 'caf', droppedChars: ['é'] })
    })
  })

  describe('conflict', () => {
    const existing = { mimo: 'MiMo', deepseek: 'DeepSeek' }

    it('标识被占用时拒绝提交并给出占用方', () => {
      // 大小写差异不影响标识，故 'mimo' 与已有的 'MiMo' 冲突
      expect(describeProviderKey('mimo', { existing })).toMatchObject({
        status: 'conflict',
        key: 'mimo',
        conflictWith: 'MiMo',
        submittable: false,
      })
    })

    // 这是后端那句「该供应商名称已存在」最容易让人困惑的场景：
    // 两个展示名完全不同，却派生出同一个标识。
    it('展示名不同但标识相同也算冲突', () => {
      expect(describeProviderKey('中文 MiMo', { existing })).toMatchObject({
        status: 'conflict',
        key: 'mimo',
        conflictWith: 'MiMo',
      })
    })

    it('编辑模式下不与自身冲突', () => {
      expect(describeProviderKey('MiMo', { existing, currentKey: 'mimo' })).toMatchObject({
        status: 'ok',
        key: 'mimo',
        submittable: true,
      })
    })

    it('编辑模式下改成别人的标识仍算冲突', () => {
      expect(describeProviderKey('DeepSeek', { existing, currentKey: 'mimo' })).toMatchObject({
        status: 'conflict',
        conflictWith: 'DeepSeek',
        submittable: false,
      })
    })

    it('占用方展示名为空时回退到标识本身', () => {
      expect(describeProviderKey('X', { existing: { x: '' } }).conflictWith).toBe('x')
    })

    // unavailable 优先于 conflict：空标识本身就不合法，
    // 报「已被占用」会把用户引向错误的修改方向。
    it('无法生成标识时优先报 unavailable', () => {
      expect(describeProviderKey('纯中文', { existing: { '': '空标识供应商' } }).status)
        .toBe('unavailable')
    })
  })

  describe('改名提示', () => {
    it('编辑模式下改名会标记 renamed 并保留原标识', () => {
      expect(describeProviderKey('Xiaomi MiMo', { currentKey: 'mimo' })).toMatchObject({
        key: 'xiaomi-mimo',
        previousKey: 'mimo',
        renamed: true,
      })
    })

    it('未改名时不标记', () => {
      expect(describeProviderKey('MiMo', { currentKey: 'mimo' })).toMatchObject({
        renamed: false,
        previousKey: null,
      })
    })

    it('仅大小写或分隔符变化不算改名，因为标识不变', () => {
      expect(describeProviderKey('mi-mo', { currentKey: 'mimo' }).renamed).toBe(true)
      expect(describeProviderKey('MIMO', { currentKey: 'mimo' }).renamed).toBe(false)
    })

    it('新建模式不标记改名', () => {
      expect(describeProviderKey('MiMo')).toMatchObject({ renamed: false, previousKey: null })
    })

    it('标识退化为空时不误报改名', () => {
      expect(describeProviderKey('纯中文', { currentKey: 'mimo' })).toMatchObject({
        renamed: false,
        previousKey: null,
      })
    })
  })
})
