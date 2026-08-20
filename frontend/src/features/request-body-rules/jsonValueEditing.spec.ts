import { describe, expect, it } from 'vitest'
import {
  defaultJsonValueText,
  formatJsonValueText,
  inferJsonValueType,
  parseJsonValue,
  sanitizeNumberInput,
} from './jsonValueEditing'

describe('设置字段值的类型推断', () => {
  it('按 JSON 值本身反推类型', () => {
    expect(inferJsonValueType(null)).toBe('null')
    expect(inferJsonValueType(true)).toBe('boolean')
    expect(inferJsonValueType(false)).toBe('boolean')
    expect(inferJsonValueType(0)).toBe('number')
    expect(inferJsonValueType(-2.5)).toBe('number')
    expect(inferJsonValueType('abc')).toBe('string')
    expect(inferJsonValueType('')).toBe('string')
    expect(inferJsonValueType([1, 2])).toBe('list')
  })

  /** 对象没有对应档位，退到列表让用户至少能看到并编辑原文。 */
  it('对象值退化为列表档位', () => {
    expect(inferJsonValueType({ a: 1 })).toBe('list')
  })
})

describe('设置字段值的文本渲染', () => {
  it('字符串档位不加引号', () => {
    expect(formatJsonValueText('null', 'string')).toBe('null')
    expect(formatJsonValueText('"null"', 'string')).toBe('"null"')
    expect(formatJsonValueText('', 'string')).toBe('')
  })

  it('其余档位渲染 JSON 字面量', () => {
    expect(formatJsonValueText(12.38, 'number')).toBe('12.38')
    expect(formatJsonValueText(true, 'boolean')).toBe('true')
    expect(formatJsonValueText(false, 'boolean')).toBe('false')
    expect(formatJsonValueText(null, 'null')).toBe('null')
    expect(formatJsonValueText([1, 'a', null], 'list')).toBe('[1,"a",null]')
  })

  it('切换类型时给出可用的初值', () => {
    expect(defaultJsonValueText('list')).toBe('[]')
    expect(defaultJsonValueText('boolean')).toBe('true')
    expect(defaultJsonValueText('null')).toBe('null')
    expect(defaultJsonValueText('string')).toBe('')
    expect(defaultJsonValueText('number')).toBe('')
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
    expect(parseJsonValue('null', 'string')).toEqual({ value: 'null', error: '' })
    expect(parseJsonValue('"null"', 'string')).toEqual({ value: '"null"', error: '' })
    expect(parseJsonValue('123', 'string')).toEqual({ value: '123', error: '' })
    expect(parseJsonValue('', 'string')).toEqual({ value: '', error: '' })
  })

  it('数值档位产出数字', () => {
    expect(parseJsonValue('12.38', 'number')).toEqual({ value: 12.38, error: '' })
    expect(parseJsonValue('-2', 'number')).toEqual({ value: -2, error: '' })
    expect(parseJsonValue('0', 'number')).toEqual({ value: 0, error: '' })
  })

  /** 空文本不能变成 0：0 是有意义的值，不该由「还没输入」产生。 */
  it('数值档位空文本报错而非落 0', () => {
    expect(parseJsonValue('', 'number').error).toBe('请输入数值')
    expect(parseJsonValue('-', 'number').error).toBe('请输入数值')
    expect(parseJsonValue('.', 'number').error).toBe('请输入数值')
  })

  it('布尔档位只有两个取值', () => {
    expect(parseJsonValue('true', 'boolean')).toEqual({ value: true, error: '' })
    expect(parseJsonValue('false', 'boolean')).toEqual({ value: false, error: '' })
  })

  it('null 档位恒为 null 且忽略文本', () => {
    expect(parseJsonValue('null', 'null')).toEqual({ value: null, error: '' })
    expect(parseJsonValue('随便写', 'null')).toEqual({ value: null, error: '' })
  })

  it('列表档位解析 JSON 数组并保留元素类型', () => {
    expect(parseJsonValue('[12.38, false, "hello world", null]', 'list')).toEqual({
      value: [12.38, false, 'hello world', null],
      error: '',
    })
    expect(parseJsonValue('[]', 'list')).toEqual({ value: [], error: '' })
  })

  it('列表档位拒绝非数组与语法错误', () => {
    expect(parseJsonValue('{"a":1}', 'list').error).toBe('必须是 JSON 数组，以 [ 开头、] 结尾')
    expect(parseJsonValue('"abc"', 'list').error).toBe('必须是 JSON 数组，以 [ 开头、] 结尾')
    expect(parseJsonValue('[1,', 'list').error).toContain('列表 JSON 语法错误')
    expect(parseJsonValue('', 'list').error).toContain('请输入列表')
  })

  /** 渲染与解析在每个档位上互为逆操作。 */
  it('格式化后再解析得到原值', () => {
    const cases: Array<[unknown, ReturnType<typeof inferJsonValueType>]> = [
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
      const text = formatJsonValueText(value, type)
      expect(parseJsonValue(text, type), `${type}: ${text}`).toEqual({ value, error: '' })
    }
  })
})
