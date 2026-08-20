import { describe, expect, it } from 'vitest'
import {
  defaultSetValueText,
  formatSetValueText,
  inferSetValueType,
  parseSetValue,
  sanitizeNumberInput,
} from './setValueEditing'

describe('设置字段值的类型推断', () => {
  it('按 JSON 值本身反推类型', () => {
    expect(inferSetValueType(null)).toBe('null')
    expect(inferSetValueType(true)).toBe('boolean')
    expect(inferSetValueType(false)).toBe('boolean')
    expect(inferSetValueType(0)).toBe('number')
    expect(inferSetValueType(-2.5)).toBe('number')
    expect(inferSetValueType('abc')).toBe('string')
    expect(inferSetValueType('')).toBe('string')
    expect(inferSetValueType([1, 2])).toBe('list')
  })

  /** 对象没有对应档位，退到列表让用户至少能看到并编辑原文。 */
  it('对象值退化为列表档位', () => {
    expect(inferSetValueType({ a: 1 })).toBe('list')
  })
})

describe('设置字段值的文本渲染', () => {
  it('字符串档位不加引号', () => {
    expect(formatSetValueText('null', 'string')).toBe('null')
    expect(formatSetValueText('"null"', 'string')).toBe('"null"')
    expect(formatSetValueText('', 'string')).toBe('')
  })

  it('其余档位渲染 JSON 字面量', () => {
    expect(formatSetValueText(12.38, 'number')).toBe('12.38')
    expect(formatSetValueText(true, 'boolean')).toBe('true')
    expect(formatSetValueText(false, 'boolean')).toBe('false')
    expect(formatSetValueText(null, 'null')).toBe('null')
    expect(formatSetValueText([1, 'a', null], 'list')).toBe('[1,"a",null]')
  })

  it('切换类型时给出可用的初值', () => {
    expect(defaultSetValueText('list')).toBe('[]')
    expect(defaultSetValueText('boolean')).toBe('true')
    expect(defaultSetValueText('null')).toBe('null')
    expect(defaultSetValueText('string')).toBe('')
    expect(defaultSetValueText('number')).toBe('')
  })
})

describe('数值输入过滤', () => {
  it('丢弃非数字字符', () => {
    expect(sanitizeNumberInput('12abc.38')).toBe('12.38')
    expect(sanitizeNumberInput('1e5')).toBe('15')
  })

  it('只保留第一个小数点', () => {
    expect(sanitizeNumberInput('1.2.3')).toBe('1.23')
    expect(sanitizeNumberInput('...')).toBe('.')
  })

  /** 负号是 presence_penalty 这类字段的必需项，只在开头保留。 */
  it('保留开头的负号并丢弃其余位置的', () => {
    expect(sanitizeNumberInput('-2.0')).toBe('-2.0')
    expect(sanitizeNumberInput('1-2')).toBe('12')
  })
})

describe('设置字段值的解析', () => {
  it('字符串档位原文即值，不做任何修正', () => {
    expect(parseSetValue('null', 'string')).toEqual({ value: 'null', error: '' })
    expect(parseSetValue('"null"', 'string')).toEqual({ value: '"null"', error: '' })
    expect(parseSetValue('123', 'string')).toEqual({ value: '123', error: '' })
    expect(parseSetValue('', 'string')).toEqual({ value: '', error: '' })
  })

  it('数值档位产出数字', () => {
    expect(parseSetValue('12.38', 'number')).toEqual({ value: 12.38, error: '' })
    expect(parseSetValue('-2', 'number')).toEqual({ value: -2, error: '' })
    expect(parseSetValue('0', 'number')).toEqual({ value: 0, error: '' })
  })

  /** 空文本不能变成 0：0 是有意义的值，不该由「还没输入」产生。 */
  it('数值档位空文本报错而非落 0', () => {
    expect(parseSetValue('', 'number').error).toBe('请输入数值')
    expect(parseSetValue('-', 'number').error).toBe('请输入数值')
    expect(parseSetValue('.', 'number').error).toBe('请输入数值')
  })

  it('布尔档位只有两个取值', () => {
    expect(parseSetValue('true', 'boolean')).toEqual({ value: true, error: '' })
    expect(parseSetValue('false', 'boolean')).toEqual({ value: false, error: '' })
  })

  it('null 档位恒为 null 且忽略文本', () => {
    expect(parseSetValue('null', 'null')).toEqual({ value: null, error: '' })
    expect(parseSetValue('随便写', 'null')).toEqual({ value: null, error: '' })
  })

  it('列表档位解析 JSON 数组并保留元素类型', () => {
    expect(parseSetValue('[12.38, false, "hello world", null]', 'list')).toEqual({
      value: [12.38, false, 'hello world', null],
      error: '',
    })
    expect(parseSetValue('[]', 'list')).toEqual({ value: [], error: '' })
  })

  it('列表档位拒绝非数组与语法错误', () => {
    expect(parseSetValue('{"a":1}', 'list').error).toBe('必须是 JSON 数组，以 [ 开头、] 结尾')
    expect(parseSetValue('"abc"', 'list').error).toBe('必须是 JSON 数组，以 [ 开头、] 结尾')
    expect(parseSetValue('[1,', 'list').error).toContain('列表 JSON 语法错误')
    expect(parseSetValue('', 'list').error).toContain('请输入列表')
  })

  /** 渲染与解析在每个档位上互为逆操作。 */
  it('格式化后再解析得到原值', () => {
    const cases: Array<[unknown, ReturnType<typeof inferSetValueType>]> = [
      ['null', 'string'],
      ['', 'string'],
      [12.38, 'number'],
      [-2, 'number'],
      [true, 'boolean'],
      [false, 'boolean'],
      [null, 'null'],
      [[1, 'a', null, false], 'list'],
    ]
    for (const [value, type] of cases) {
      const text = formatSetValueText(value, type)
      expect(parseSetValue(text, type), `${type}: ${text}`).toEqual({ value, error: '' })
    }
  })
})
