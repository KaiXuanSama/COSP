import { describe, expect, it } from 'vitest'
import {
  DEFAULT_MAX_OUTPUT_OVERWRITE_MODE,
  DEFAULT_MAX_OUTPUT_TOKENS,
  MAX_OUTPUT_OVERWRITE_MODES,
  MAX_OUTPUT_PRESETS,
  nextMaxOutputOverwriteMode,
  parseMaxOutputConfig,
  serializeMaxOutputConfig,
} from './maxOutput'

describe('parseMaxOutputConfig', () => {
  it('解析 V9 JSON 形态', () => {
    expect(parseMaxOutputConfig('{"max_output_tokens":32000,"overwrite_mode":"override"}'))
      .toEqual({ maxOutputTokens: 32000, mode: 'override' })
  })

  // 兜底是 V9 之前的唯一行为（这个值压根没进过请求体），所以旧的裸整数升级后行为不变。
  it('旧的裸整数按兜底模式解析', () => {
    expect(parseMaxOutputConfig('128000'))
      .toEqual({ maxOutputTokens: 128000, mode: 'fallback' })
  })

  it('数字入参也能解析，便于直接吃后端的 int 字段', () => {
    expect(parseMaxOutputConfig(64000))
      .toEqual({ maxOutputTokens: 64000, mode: 'fallback' })
  })

  /**
   * 解析**不**做档位重映射。
   *
   * 「128K 降为 4K」是 V9 迁移的一次性语义调整，只该发生在迁移里。若读取路径也做同样的映射，
   * 任何一个手工设成 128000 的值都会在每次读取时变成 4000 —— 用户看到的界面值与自己填的不一致,
   * 而这个偏差没有任何提示。
   */
  it('不对旧预设值做迁移那套重映射', () => {
    expect(parseMaxOutputConfig('128000').maxOutputTokens).toBe(128000)
    expect(parseMaxOutputConfig('256000').maxOutputTokens).toBe(256000)
    expect(parseMaxOutputConfig('512000').maxOutputTokens).toBe(512000)
  })

  // 0 在 V9 之前是合法的（列约束只要求 >= 0），语义是「未配置」；该语义已由模式承担。
  it('0 与负数回退默认上限', () => {
    expect(parseMaxOutputConfig('0').maxOutputTokens).toBe(DEFAULT_MAX_OUTPUT_TOKENS)
    expect(parseMaxOutputConfig('-1').maxOutputTokens).toBe(DEFAULT_MAX_OUTPUT_TOKENS)
    expect(parseMaxOutputConfig(0).maxOutputTokens).toBe(DEFAULT_MAX_OUTPUT_TOKENS)
  })

  it('空值与非字符串回退默认', () => {
    const fallback = {
      maxOutputTokens: DEFAULT_MAX_OUTPUT_TOKENS,
      mode: DEFAULT_MAX_OUTPUT_OVERWRITE_MODE,
    }
    expect(parseMaxOutputConfig('')).toEqual(fallback)
    expect(parseMaxOutputConfig('   ')).toEqual(fallback)
    expect(parseMaxOutputConfig(undefined)).toEqual(fallback)
    expect(parseMaxOutputConfig(null)).toEqual(fallback)
  })

  // 字段来自数据库，一行脏数据不该让整个模型列表无法编辑。
  it('残缺 JSON 与非数字回退默认而不抛错', () => {
    expect(parseMaxOutputConfig('{"max_output_tokens":').maxOutputTokens)
      .toBe(DEFAULT_MAX_OUTPUT_TOKENS)
    expect(parseMaxOutputConfig('not-a-number').maxOutputTokens)
      .toBe(DEFAULT_MAX_OUTPUT_TOKENS)
  })

  it('JSON 里未知模式回退默认模式，但上限仍生效', () => {
    expect(parseMaxOutputConfig('{"max_output_tokens":16000,"overwrite_mode":"bogus"}'))
      .toEqual({ maxOutputTokens: 16000, mode: DEFAULT_MAX_OUTPUT_OVERWRITE_MODE })
  })

  it('模式名大小写与空白不敏感', () => {
    expect(parseMaxOutputConfig('{"max_output_tokens":8000,"overwrite_mode":" OVERRIDE "}').mode)
      .toBe('override')
  })
})

describe('serializeMaxOutputConfig', () => {
  // 迁移与界面都用 json_extract 直接取整数，写成字符串会让那些查询取到 null。
  it('上限序列化为 JSON 数字而非字符串', () => {
    expect(serializeMaxOutputConfig({ maxOutputTokens: 4000, mode: 'fallback' }))
      .toBe('{"max_output_tokens":4000,"overwrite_mode":"fallback"}')
  })

  it('往返一致', () => {
    for (const preset of MAX_OUTPUT_PRESETS) {
      for (const mode of MAX_OUTPUT_OVERWRITE_MODES) {
        const original = { maxOutputTokens: preset.value, mode }
        expect(parseMaxOutputConfig(serializeMaxOutputConfig(original))).toEqual(original)
      }
    }
  })

  it('非法上限在序列化时也收敛为默认值', () => {
    expect(serializeMaxOutputConfig({ maxOutputTokens: 0, mode: 'override' }))
      .toBe('{"max_output_tokens":64000,"overwrite_mode":"override"}')
  })
})

describe('nextMaxOutputOverwriteMode', () => {
  it('两档之间来回切换', () => {
    expect(nextMaxOutputOverwriteMode('override')).toBe('fallback')
    expect(nextMaxOutputOverwriteMode('fallback')).toBe('override')
  })

  // 原地不动会让按钮看起来是坏的。
  it('认不出的值从头开始而非原地不动', () => {
    expect(nextMaxOutputOverwriteMode('bogus' as never)).toBe(MAX_OUTPUT_OVERWRITE_MODES[0])
  })
})

describe('MAX_OUTPUT_OVERWRITE_MODES', () => {
  /**
   * 只有两档。
   *
   * 没有 passthrough / delete 不是简化：Anthropic 侧 `max_tokens` 缺失会直接 400,
   * 「不补」或「剥离」在那条线路上等于必然失败。这条断言把这个决定钉在测试里 ——
   * 将来若有人为了与思考深度「对齐」而补齐四档，会先撞上它。
   */
  it('恰好两种模式', () => {
    expect(MAX_OUTPUT_OVERWRITE_MODES).toEqual(['override', 'fallback'])
  })
})

describe('MAX_OUTPUT_PRESETS', () => {
  // K 一律十进制，与上下文预设的既有口径一致（128K = 128000，而非 131072）。
  it('K 按十进制换算', () => {
    expect(MAX_OUTPUT_PRESETS.find(p => p.label === '4K')?.value).toBe(4000)
    expect(MAX_OUTPUT_PRESETS.find(p => p.label === '128K')?.value).toBe(128000)
  })

  // V9 删掉了这两档：它们远超任何模型的实际单次回复长度。
  it('不含 256K 与 512K', () => {
    expect(MAX_OUTPUT_PRESETS.map(p => p.label)).not.toContain('256K')
    expect(MAX_OUTPUT_PRESETS.map(p => p.label)).not.toContain('512K')
  })

  it('新增的四档都在', () => {
    expect(MAX_OUTPUT_PRESETS.map(p => p.label)).toContain('4K')
    expect(MAX_OUTPUT_PRESETS.map(p => p.label)).toContain('8K')
    expect(MAX_OUTPUT_PRESETS.map(p => p.label)).toContain('16K')
    expect(MAX_OUTPUT_PRESETS.map(p => p.label)).toContain('32K')
  })

  it('默认上限是预设里的一档', () => {
    expect(MAX_OUTPUT_PRESETS.map(p => p.value)).toContain(DEFAULT_MAX_OUTPUT_TOKENS)
  })
})
