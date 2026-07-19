/**
 * 相对路径解析器 — 用于条件执行时的字段定位。
 *
 * 路径以 ./ 开头，表示当前规则作用域。
 * 支持：
 *   ./field          — 当前对象的直接子字段
 *   ./nested/field   — 当前对象下的嵌套字段
 *   ./items[*]/field — 当前对象的数组字段中任意元素的字段
 *
 * 不支持父路径 ../（V1 暂不实现）。
 */

/** 路径解析后的单步。 */
interface PathStep {
  /** 字段名 */
  field: string
  /** 是否为数组通配符 */
  arrayWildcard: boolean
}

/**
 * 解析相对路径为步骤列表。
 *
 * @param path 相对路径，如 ./content[*]/image_url
 * @returns 步骤列表，解析失败时返回 null
 */
export function parsePath(path: string): PathStep[] | null {
  if (!path || typeof path !== 'string') return null
  const trimmed = path.trim()
  if (!trimmed.startsWith('./')) return null

  const body = trimmed.slice(2)
  if (!body) return null

  // 按斜杠分割，每段可能是 field 或 field[*]
  const segments = body.split('/')
  const steps: PathStep[] = []
  for (const seg of segments) {
    if (!seg) return null
    const arrayMatch = seg.match(/^(.+?)\[\*\]$/)
    if (arrayMatch) {
      steps.push({ field: arrayMatch[1], arrayWildcard: true })
    } else {
      steps.push({ field: seg, arrayWildcard: false })
    }
  }
  return steps.length > 0 ? steps : null
}

/**
 * 判断路径在给定对象中是否存在。
 *
 * 数组通配符采用 existential semantics：任意元素满足即为 true。
 * 字段值即使是 null、false、0 或空字符串，仍然视为存在。
 *
 * @param current 当前作用域对象
 * @param path 相对路径
 * @returns 路径能解析且字段存在时返回 true
 */
export function pathExists(current: unknown, path: string): boolean {
  const steps = parsePath(path)
  if (!steps) return false
  return resolveExists(current, steps, 0)
}

/**
 * 获取路径对应的值，用于 equals 比较。
 *
 * 数组通配符返回第一个匹配元素的值。
 * 路径不存在时返回 undefined。
 */
export function pathValue(current: unknown, path: string): unknown {
  const steps = parsePath(path)
  if (!steps) return undefined
  return resolveValue(current, steps, 0)
}

function resolveExists(current: unknown, steps: PathStep[], index: number): boolean {
  if (index >= steps.length) {
    // 路径已走完，current 本身存在
    return true
  }
  if (current == null || typeof current !== 'object') {
    return false
  }

  const step = steps[index]
  if (step.arrayWildcard) {
    // 数组通配符：任意元素满足即为 true
    const arr = (current as Record<string, unknown>)[step.field]
    if (!Array.isArray(arr)) return false
    for (const item of arr) {
      if (resolveExists(item, steps, index + 1)) return true
    }
    return false
  } else {
    const obj = current as Record<string, unknown>
    if (!(step.field in obj)) return false
    return resolveExists(obj[step.field], steps, index + 1)
  }
}

function resolveValue(current: unknown, steps: PathStep[], index: number): unknown {
  if (index >= steps.length) {
    return current
  }
  if (current == null || typeof current !== 'object') {
    return undefined
  }

  const step = steps[index]
  if (step.arrayWildcard) {
    const arr = (current as Record<string, unknown>)[step.field]
    if (!Array.isArray(arr)) return undefined
    for (const item of arr) {
      const val = resolveValue(item, steps, index + 1)
      if (val !== undefined) return val
    }
    return undefined
  } else {
    const obj = current as Record<string, unknown>
    if (!(step.field in obj)) return undefined
    return resolveValue(obj[step.field], steps, index + 1)
  }
}

/**
 * 深度比较两个 JSON 值是否相等。
 * 支持原始类型、对象和数组的递归比较。
 */
export function jsonEquals(a: unknown, b: unknown): boolean {
  if (a === b) return true
  if (a == null || b == null) return a === b
  if (typeof a !== typeof b) return false
  if (typeof a !== 'object') return a === b

  const arrA = Array.isArray(a)
  const arrB = Array.isArray(b)
  if (arrA !== arrB) return false

  if (arrA && arrB) {
    if (a.length !== b.length) return false
    for (let i = 0; i < a.length; i++) {
      if (!jsonEquals(a[i], b[i])) return false
    }
    return true
  }

  const objA = a as Record<string, unknown>
  const objB = b as Record<string, unknown>
  const keysA = Object.keys(objA)
  const keysB = Object.keys(objB)
  if (keysA.length !== keysB.length) return false
  for (const key of keysA) {
    if (!(key in objB)) return false
    if (!jsonEquals(objA[key], objB[key])) return false
  }
  return true
}
