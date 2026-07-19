/**
 * 请求体转换结果 diff 工具。
 *
 * 对比原始 JSON 与转换后 JSON，生成带状态的树节点，
 * 供右侧预览按状态着色显示。
 */

export type DiffStatus = 'same' | 'changed' | 'added' | 'deleted'

export interface DiffNode {
  /** 对象键名；数组元素或根节点为空 */
  key: string | null
  /** 节点状态 */
  status: DiffStatus
  /** 是否为对象/数组容器 */
  kind: 'object' | 'array' | 'primitive'
  /** 展示用的值：deleted 用原始值，其余用当前值 */
  value: unknown
  /** 子节点（object/array） */
  children?: DiffNode[]
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return value != null && typeof value === 'object' && !Array.isArray(value)
}

function jsonEquals(a: unknown, b: unknown): boolean {
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

/**
 * 对比 original 与 current，生成 DiffNode 树。
 *
 * 删除字段会以 status=deleted 保留在树中，便于右侧预览显示删除线。
 */
export function buildDiffTree(original: unknown, current: unknown, key: string | null = null): DiffNode {
  if (original !== undefined && current === undefined) {
    return wrapAsDeleted(key, original)
  }
  if (original === undefined && current !== undefined) {
    return wrapAsStatus(key, current, 'added')
  }

  if (Array.isArray(original) && Array.isArray(current)) {
    const children: DiffNode[] = []
    const maxLen = Math.max(original.length, current.length)
    for (let i = 0; i < maxLen; i++) {
      const hasOriginal = i < original.length
      const hasCurrent = i < current.length
      // 数组元素不展示键名，避免出现 "0": / "1": 序号前缀
      if (hasOriginal && !hasCurrent) {
        children.push(wrapAsDeleted(null, original[i]))
      } else if (!hasOriginal && hasCurrent) {
        children.push(wrapAsStatus(null, current[i], 'added'))
      } else {
        children.push(buildDiffTree(original[i], current[i], null))
      }
    }
    const status: DiffStatus = children.some((c) => c.status !== 'same') ? 'changed' : 'same'
    return { key, status, kind: 'array', value: current, children }
  }

  if (isPlainObject(original) && isPlainObject(current)) {
    const keys: string[] = []
    const seen = new Set<string>()
    for (const k of Object.keys(original)) {
      keys.push(k)
      seen.add(k)
    }
    for (const k of Object.keys(current)) {
      if (!seen.has(k)) keys.push(k)
    }

    const children = keys.map((childKey) => {
      const hasOriginal = childKey in original
      const hasCurrent = childKey in current
      if (hasOriginal && !hasCurrent) {
        return wrapAsDeleted(childKey, original[childKey])
      }
      if (!hasOriginal && hasCurrent) {
        return wrapAsStatus(childKey, current[childKey], 'added')
      }
      return buildDiffTree(original[childKey], current[childKey], childKey)
    })

    const status: DiffStatus = children.some((c) => c.status !== 'same') ? 'changed' : 'same'
    return { key, status, kind: 'object', value: current, children }
  }

  if (jsonEquals(original, current)) {
    return wrapAsStatus(key, current, 'same')
  }
  return wrapAsStatus(key, current, 'changed')
}

function wrapAsStatus(key: string | null, value: unknown, status: DiffStatus): DiffNode {
  if (Array.isArray(value)) {
    return {
      key,
      status,
      kind: 'array',
      value,
      children: value.map((item) => wrapAsStatus(null, item, status)),
    }
  }
  if (isPlainObject(value)) {
    return {
      key,
      status,
      kind: 'object',
      value,
      children: Object.entries(value).map(([k, v]) => wrapAsStatus(k, v, status)),
    }
  }
  return { key, status, kind: 'primitive', value }
}

function wrapAsDeleted(key: string | null, value: unknown): DiffNode {
  if (Array.isArray(value)) {
    return {
      key,
      status: 'deleted',
      kind: 'array',
      value,
      children: value.map((item) => wrapAsDeleted(null, item)),
    }
  }
  if (isPlainObject(value)) {
    return {
      key,
      status: 'deleted',
      kind: 'object',
      value,
      children: Object.entries(value).map(([k, v]) => wrapAsDeleted(k, v)),
    }
  }
  return { key, status: 'deleted', kind: 'primitive', value }
}

/** 将原始值格式化为 JSON 文本片段（用于显示） */
export function formatPrimitive(value: unknown): string {
  if (value === null) return 'null'
  if (typeof value === 'string') return JSON.stringify(value)
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return JSON.stringify(value)
}
