import { describe, it, expect } from 'vitest'
import {
  AUTH_HEADER_MODES,
  AUTH_HEADER_MODE_HINTS,
  AUTH_HEADER_MODE_LABELS,
  AUTH_HEADER_NAME_LABELS,
  AUTH_HEADER_NAME_OPTIONS,
  DEFAULT_AUTH_HEADER_CONFIG,
  authHeaderValueState,
  nextAuthHeaderMode,
  parseAuthHeaderConfig,
  serializeAuthHeaderConfig,
} from './authHeader'

/**
 * 后端 `AuthHeaderSetting.DEFAULT_AUTH_HEADER_JSON` 与 `schema.sql` 的 DEFAULT 的逐字形态。
 *
 * 写死字面量而非 import：它就是「三处必须一致」的那个真值，用它比对才有意义。
 * 从被测代码反向取值会让这条断言变成自证。
 */
const BACKEND_DEFAULT_JSON = '{"mode":"DOWNSTREAM","header":"AUTHORIZATION"}'

describe('DEFAULT_AUTH_HEADER_CONFIG', () => {
  /**
   * 前端默认值必须与后端默认值同语义。
   *
   * 三处分叉（`schema.sql` 的 DEFAULT、V14 迁移、后端常量）会让「新库」「升级后的库」
   * 「保存过一次的供应商」看起来是三种配置。前端这一份是第四处 ——
   * 它不同不会立刻报错，只会让新建的供应商与迁移出来的长得不一样。
   */
  it('序列化后与后端默认常量逐字一致', () => {
    expect(serializeAuthHeaderConfig(DEFAULT_AUTH_HEADER_CONFIG)).toBe(BACKEND_DEFAULT_JSON)
  })

  /** 默认取「取下游」：存量行为几乎不变（下游带了什么就还发什么）。 */
  it('默认是取下游 + Authorization', () => {
    expect(DEFAULT_AUTH_HEADER_CONFIG).toEqual({
      mode: 'downstream',
      header: 'authorization',
    })
  })
})

describe('parseAuthHeaderConfig', () => {
  it('解析后端形态的大写枚举名', () => {
    expect(parseAuthHeaderConfig('{"mode":"CONFIGURED","header":"X_API_KEY"}'))
      .toEqual({ mode: 'configured', header: 'x-api-key' })
  })

  /** 大小写不敏感：后端只会发大写，但手工改库或将来放宽都可能出现别的写法。 */
  it('模式与头名都大小写不敏感', () => {
    expect(parseAuthHeaderConfig('{"mode":"configured","header":"authorization"}'))
      .toEqual({ mode: 'configured', header: 'authorization' })
    expect(parseAuthHeaderConfig('{"mode":"Downstream","header":"x_api_key"}'))
      .toEqual({ mode: 'downstream', header: 'x-api-key' })
  })

  /**
   * 两个维度各自回退，不整体回退。
   *
   * `mode` 认不出时 `header` 仍要正确解析 —— 整体回退会把用户明确设过的另一维也丢掉。
   */
  it('两个维度各自回退而非整体回退', () => {
    expect(parseAuthHeaderConfig('{"mode":"bogus","header":"X_API_KEY"}'))
      .toEqual({ mode: 'downstream', header: 'x-api-key' })
    expect(parseAuthHeaderConfig('{"mode":"CONFIGURED","header":"bogus"}'))
      .toEqual({ mode: 'configured', header: 'authorization' })
  })

  /** 脏输入一律回默认，不抛错 —— 一行脏数据不该让整个供应商列表无法编辑。 */
  it('脏输入回默认而不抛错', () => {
    const fallback = { ...DEFAULT_AUTH_HEADER_CONFIG }

    expect(parseAuthHeaderConfig(undefined)).toEqual(fallback)
    expect(parseAuthHeaderConfig(null)).toEqual(fallback)
    expect(parseAuthHeaderConfig('')).toEqual(fallback)
    expect(parseAuthHeaderConfig('   ')).toEqual(fallback)
    expect(parseAuthHeaderConfig('{broken')).toEqual(fallback)
    // 非 JSON 的历史裸值：不猜模式，直接回默认。
    expect(parseAuthHeaderConfig('DOWNSTREAM')).toEqual(fallback)
    expect(parseAuthHeaderConfig('123')).toEqual(fallback)
    // 合法 JSON 但缺键。
    expect(parseAuthHeaderConfig('{}')).toEqual(fallback)
    expect(parseAuthHeaderConfig('{"mode":"CONFIGURED"}'))
      .toEqual({ mode: 'configured', header: 'authorization' })
  })

  /** 非字符串入参（后端误发对象/数字）同样回默认。 */
  it('非字符串入参回默认', () => {
    expect(parseAuthHeaderConfig({ mode: 'CONFIGURED', header: 'X_API_KEY' }))
      .toEqual({ ...DEFAULT_AUTH_HEADER_CONFIG })
    expect(parseAuthHeaderConfig(42)).toEqual({ ...DEFAULT_AUTH_HEADER_CONFIG })
  })

  /** 往返：序列化再解析必须回到原值，否则保存一次就会静默改变配置。 */
  it('与序列化互为逆运算', () => {
    const configs = [
      { mode: 'downstream', header: 'authorization' },
      { mode: 'downstream', header: 'x-api-key' },
      { mode: 'configured', header: 'authorization' },
      { mode: 'configured', header: 'x-api-key' },
    ] as const

    for (const config of configs) {
      expect(parseAuthHeaderConfig(serializeAuthHeaderConfig(config))).toEqual(config)
    }
  })
})

describe('serializeAuthHeaderConfig', () => {
  /** 落库用大写枚举名而非头名字面量：头名大小写不敏感且有拼写变体。 */
  it('落库写枚举名，不写头名字面量', () => {
    expect(serializeAuthHeaderConfig({ mode: 'configured', header: 'x-api-key' }))
      .toBe('{"mode":"CONFIGURED","header":"X_API_KEY"}')
    // 头名写的是 `x-api-key`，序列化后不该出现这个字面量。
    expect(serializeAuthHeaderConfig({ mode: 'configured', header: 'x-api-key' }))
      .not.toContain('x-api-key')
  })

  /** 键序固定，让默认值可与后端常量逐字比对。 */
  it('键序固定为 mode 在前', () => {
    expect(serializeAuthHeaderConfig({ mode: 'downstream', header: 'x-api-key' }))
      .toBe('{"mode":"DOWNSTREAM","header":"X_API_KEY"}')
  })
})

describe('authHeaderValueState', () => {
  /**
   * 两档都不得返回 `unused`。
   *
   * 这是与思考深度／最大输出最本质的差别：那两者有「透传」「删除」档，配置的值真的
   * 不会被发送；而鉴权头**永远要发一个**，`downstream` 只是把它的适用场合缩小到
   * 「下游带 0 或 2 个头」这一异常输入。
   *
   * <p>返回 `unused` 会让值区灰显，而灰显在既有控件里表示「这个值不会生效」——
   * 用户会以为配了没用，实际它是兜底值。
   */
  it('两档都不返回 unused', () => {
    for (const mode of AUTH_HEADER_MODES) {
      expect(authHeaderValueState(mode), mode).not.toBe('unused')
    }
  })

  it('取设置是常态使用，取下游是兜底使用', () => {
    expect(authHeaderValueState('configured')).toBe('used')
    expect(authHeaderValueState('downstream')).toBe('fallback')
  })
})

describe('nextAuthHeaderMode', () => {
  /** 点击轮转要能穷尽所有模式并回到起点，否则会有模式永远点不到。 */
  it('按清单顺序循环', () => {
    let mode = AUTH_HEADER_MODES[0]
    const visited = [mode]
    for (let i = 1; i < AUTH_HEADER_MODES.length; i++) {
      mode = nextAuthHeaderMode(mode)
      visited.push(mode)
    }
    expect(visited).toEqual([...AUTH_HEADER_MODES])

    // 再走一格回到起点。
    expect(nextAuthHeaderMode(mode)).toBe(AUTH_HEADER_MODES[0])
  })

  /** 认不出的值从头开始，而不是原地不动 —— 后者会让按钮看起来是坏的。 */
  it('认不出的值回到首项', () => {
    expect(nextAuthHeaderMode('bogus' as never)).toBe(AUTH_HEADER_MODES[0])
  })
})

describe('标签与选项', () => {
  /** 每个模式都要有标签与说明，否则控件上会出现空按钮、悬停无内容。 */
  it('每个模式都有标签与悬停说明', () => {
    for (const mode of AUTH_HEADER_MODES) {
      expect(AUTH_HEADER_MODE_LABELS[mode], mode).toBeTruthy()
      expect(AUTH_HEADER_MODE_HINTS[mode], mode).toBeTruthy()
    }
  })

  /**
   * 「取下游」的说明必须点明兜底语义。
   *
   * 只说「沿用下游的选择」会让用户以为右侧的头名用不上 —— 而它在下游带 0 或 2 个头时
   * 正是出站的那一个。这条文案是方案 A（不灰显 + 靠文案表达）的唯一落点。
   */
  it('取下游的说明点明兜底', () => {
    expect(AUTH_HEADER_MODE_HINTS.downstream).toContain('兜底')
    expect(AUTH_HEADER_MODE_HINTS.configured).toContain('始终')
  })

  /** 选项的标签取自标签表，两处不会漂移。 */
  it('选项标签与标签表同源', () => {
    expect(AUTH_HEADER_NAME_OPTIONS.map(o => o.label))
      .toEqual([AUTH_HEADER_NAME_LABELS.authorization, AUTH_HEADER_NAME_LABELS['x-api-key']])
  })

  /** `x-api-key` 的展示文本保持小写 —— 它就是那个头在 HTTP 上的常见写法。 */
  it('头名展示文本符合 HTTP 惯例', () => {
    expect(AUTH_HEADER_NAME_LABELS.authorization).toBe('Authorization')
    expect(AUTH_HEADER_NAME_LABELS['x-api-key']).toBe('x-api-key')
  })
})
