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

/** 两侧完全相等的一对元素，充当对齐锚点。 */
interface ElementAnchor {
  readonly original: number
  readonly current: number
}

/**
 * 数组元素两侧配对结果。
 *
 * 两个下标同时存在表示这两个元素互相对应（相等或被改写）；
 * 只有一侧则表示该元素被删除或新增。
 */
type AlignedPair =
  | ElementAnchor
  | { readonly original: number; readonly current: null }
  | { readonly original: null; readonly current: number }

/**
 * 走对齐算法的<strong>数组级</strong>规模上限，超过则整个数组退回按下标逐位对齐。
 *
 * <p>LCS 要 O(n×m) 的 DP 表，而 diff 在每次预览输入变化时同步重算（`computed` 里，
 * 会冻住界面）。请求体里的数组（`messages` / `tools` / `content`）实际都是十几到
 * 几十个元素，这个上限只防极端粘贴。
 *
 * <p><strong>它挡不住间隙级的开销</strong>：锚点一个都找不到时整个数组落进同一个
 * 间隙，配对次数是 n×m 而不是 n。那一级由 {@link RANGE_ALIGN_LIMIT} 单独把关 ——
 * 早先这里的注释声称「防住粘贴几千条消息」，而实测 200 条就要 547ms，
 * 因为漏算了这一级。两级各有各的规模，不能靠一个数字管住。
 */
const ARRAY_ALIGN_LIMIT = 200

/**
 * 元素的稳定指纹，用于 LCS 里的相等判定。
 *
 * 与 {@link jsonEquals} 语义一致（键序无关的深比较），但把 O(n×m) 次深比较换成
 * O(n+m) 次序列化加 O(n×m) 次字符串比较 —— 数组元素往往是带嵌套的对象，
 * 直接在 DP 内层做深比较会成为热点。
 *
 * 前提是数据来自 JSON（预览输入走 `JSON.parse`，输出是后端返回的 JSON），
 * 因此不存在 `NaN` / `Infinity` / 函数这些 `JSON.stringify` 会压成 `null` 的值。
 */
function stableFingerprint(value: unknown): string {
  if (value === null || typeof value !== 'object') {
    return JSON.stringify(value) ?? 'undefined'
  }
  if (Array.isArray(value)) {
    return `[${value.map(stableFingerprint).join(',')}]`
  }
  const object = value as Record<string, unknown>
  const entries = Object.keys(object)
    .sort()
    .map((childKey) => `${JSON.stringify(childKey)}:${stableFingerprint(object[childKey])}`)
  return `{${entries.join(',')}}`
}

/**
 * 找出两侧完全相等的元素，作为对齐的锚点（最长公共子序列）。
 *
 * 返回的下标对严格递增，因此可以直接把数组切成若干互不重叠的间隙。
 *
 * @param left 左侧元素的指纹，由调用方算好传入（见 {@link alignArrayElements}）
 * @param right 右侧元素的指纹
 */
function findEqualAnchors(left: readonly string[], right: readonly string[]): ElementAnchor[] {
  const rows = left.length
  const columns = right.length

  // lengths[i][j] = left[i..] 与 right[j..] 的 LCS 长度
  const lengths: number[][] = Array.from({ length: rows + 1 }, () =>
    new Array<number>(columns + 1).fill(0),
  )
  for (let i = rows - 1; i >= 0; i--) {
    for (let j = columns - 1; j >= 0; j--) {
      lengths[i][j] =
        left[i] === right[j]
          ? lengths[i + 1][j + 1] + 1
          : Math.max(lengths[i + 1][j], lengths[i][j + 1])
    }
  }

  const anchors: ElementAnchor[] = []
  let i = 0
  let j = 0
  while (i < rows && j < columns) {
    if (left[i] === right[j]) {
      anchors.push({ original: i, current: j })
      i++
      j++
    } else if (lengths[i + 1][j] >= lengths[i][j + 1]) {
      i++
    } else {
      j++
    }
  }
  return anchors
}

/**
 * 两个元素的相似度，值域 [0, 1]，1 表示相等。
 *
 * 只看结构骨架，不做递归展开：同为对象时算键名的 Jaccard 系数，同为数组时比长度，
 * 标量则用等值。理由是这里只需要「哪两个更可能是同一个元素」这个相对排序，
 * 而不是精确的编辑距离 —— 后者的代价与收益在预览这个场景上完全不成比例。
 *
 * <h2>指纹由调用方传入，不在这里算</h2>
 * 本函数在 O(n×m) 的双层循环内被调用。此前它每次都现算两侧指纹（即完整递归序列化
 * 两个嵌套对象），于是「无锚点」输入下 n=200 要 547ms —— 而 `buildDiffTree` 在
 * `computed` 里同步执行，那就是界面冻住半秒。改成复用 {@link alignArrayElements}
 * 已经算好的指纹后降到毫秒级，且<strong>行为完全不变</strong>（纯缓存）。
 */
function similarity(
  a: unknown,
  b: unknown,
  fingerprintA: string,
  fingerprintB: string,
): number {
  if (fingerprintA === fingerprintB) return 1

  const aIsObject = isPlainObject(a)
  const bIsObject = isPlainObject(b)
  if (aIsObject && bIsObject) {
    const keysA = Object.keys(a)
    const keysB = new Set(Object.keys(b))
    if (keysA.length === 0 && keysB.size === 0) return 1
    let shared = 0
    for (const key of keysA) {
      if (keysB.has(key)) shared++
    }
    const union = keysA.length + keysB.size - shared
    return union === 0 ? 0 : shared / union
  }

  if (Array.isArray(a) && Array.isArray(b)) {
    const longest = Math.max(a.length, b.length)
    return longest === 0 ? 1 : Math.min(a.length, b.length) / longest
  }

  // 余下的情形要么类型不同（对象 vs 标量），要么是两个不等的标量。
  // 都当作毫不相干：把它们配成「改写」只会让 diff 变成一堆无意义的逐字段增删。
  return 0
}

/**
 * 两个元素是否足够像，可以判定为「同一个元素被改写」。
 *
 * 门槛取「过半键名相同」。低于它更可能是两个不同的元素恰好落在相邻位置，
 * 配成改写会把 diff 变成一团逐字段的增删噪声，不如直接显示删除加新增。
 */
const SIMILARITY_THRESHOLD = 0.5

/**
 * 单个间隙内走相似度配对的规模上限，超过则该间隙退回按下标配对。
 *
 * <h2>为何需要一个独立于 {@link ARRAY_ALIGN_LIMIT} 的上限</h2>
 * 数组级上限挡不住这里：锚点一个都找不到时（每个元素都被改写），<strong>整个数组会
 * 落进同一个间隙</strong>，于是数组级的 200 在这里等于 200×200 = 40000 次配对。
 * 这正是「注释声称防住几千条、实测 200 条就卡半秒」的成因 —— 两级各有各的规模。
 *
 * <p>有锚点时间隙天然很小（相邻锚点之间通常只有一两个元素），所以这个上限只在
 * 病态输入上生效。取 60 是因为 60×60 = 3600 次配对在指纹已缓存后是亚毫秒级，
 * 而正常请求体的连续改写段远达不到这个长度。
 */
const RANGE_ALIGN_LIMIT = 60

/**
 * 在锚点之间的间隙内配对：按相似度贪心取最像的一对，剩下的算删除或新增。
 *
 * <p>间隙内两侧元素两两都不相等（相等的已被锚点吃掉），所以这里要回答的是
 * 「哪些是改写、哪些是纯增删」。按下标硬配会在「删除与改写并存」时错位 ——
 * 例如原始 `[drop, {mode:a}]` 变成 `[{mode:b}]`，下标 0 会把 `drop` 配给
 * `{mode:b}` 并标成改写，真正被改的那个元素反而显示为删除。
 *
 * @param fingerprints 两侧元素的指纹，由 {@link alignArrayElements} 算好传入 ——
 *                     在这个双层循环里现算会成为热点（见 {@link similarity}）
 */
function alignRange(
  original: readonly unknown[],
  current: readonly unknown[],
  fingerprints: { readonly left: readonly string[]; readonly right: readonly string[] },
  originalStart: number,
  originalEnd: number,
  currentStart: number,
  currentEnd: number,
): AlignedPair[] {
  const pendingOriginal: number[] = []
  for (let i = originalStart; i < originalEnd; i++) pendingOriginal.push(i)
  const pendingCurrent: number[] = []
  for (let j = currentStart; j < currentEnd; j++) pendingCurrent.push(j)

  // 病态间隙（无锚点时整个数组落进这里）退回下标配对：宁可高亮退化，不要卡住界面。
  if (pendingOriginal.length > RANGE_ALIGN_LIMIT || pendingCurrent.length > RANGE_ALIGN_LIMIT) {
    return alignRangeByIndex(pendingOriginal, pendingCurrent)
  }

  // 所有跨侧组合按相似度降序；打平时靠下标之差更小的优先，让配对结果稳定可预期
  const candidates: Array<{ original: number; current: number; score: number }> = []
  for (const i of pendingOriginal) {
    for (const j of pendingCurrent) {
      const score = similarity(
        original[i], current[j], fingerprints.left[i], fingerprints.right[j],
      )
      if (score >= SIMILARITY_THRESHOLD) candidates.push({ original: i, current: j, score })
    }
  }
  candidates.sort(
    (a, b) =>
      b.score - a.score ||
      Math.abs(a.original - a.current) - Math.abs(b.original - b.current) ||
      a.original - b.original,
  )

  const matchedOriginal = new Map<number, number>()
  const partnerOfCurrent = new Map<number, number>()
  for (const candidate of candidates) {
    if (matchedOriginal.has(candidate.original) || partnerOfCurrent.has(candidate.current)) continue
    matchedOriginal.set(candidate.original, candidate.current)
    partnerOfCurrent.set(candidate.current, candidate.original)
  }

  // 按 current 的顺序输出，未配对的 original 就近插入，保证读起来仍是一份 JSON
  const pairs: AlignedPair[] = []
  let originalCursor = 0
  for (const j of pendingCurrent) {
    const partner = partnerOfCurrent.get(j)
    if (partner === undefined) {
      pairs.push({ original: null, current: j })
      continue
    }
    while (originalCursor < pendingOriginal.length && pendingOriginal[originalCursor] < partner) {
      const orphan = pendingOriginal[originalCursor]
      if (!matchedOriginal.has(orphan)) pairs.push({ original: orphan, current: null })
      originalCursor++
    }
    pairs.push({ original: partner, current: j })
    if (pendingOriginal[originalCursor] === partner) originalCursor++
  }
  while (originalCursor < pendingOriginal.length) {
    const orphan = pendingOriginal[originalCursor]
    if (!matchedOriginal.has(orphan)) pairs.push({ original: orphan, current: null })
    originalCursor++
  }
  return pairs
}

/** 超过数组级规模上限时的退路：整个数组按下标逐位配对。 */
function alignByIndex(original: readonly unknown[], current: readonly unknown[]): AlignedPair[] {
  const pairs: AlignedPair[] = []
  const span = Math.max(original.length, current.length)
  for (let i = 0; i < span; i++) {
    if (i >= current.length) pairs.push({ original: i, current: null })
    else if (i >= original.length) pairs.push({ original: null, current: i })
    else pairs.push({ original: i, current: i })
  }
  return pairs
}

/**
 * 超过间隙级规模上限时的退路：把这一段内的下标按位次配对。
 *
 * <p>与 {@link alignByIndex} 的区别是它作用在<strong>一段区间</strong>上，配的是
 * 传入的两串下标（可能不从 0 开始），而不是整个数组。
 */
function alignRangeByIndex(
  pendingOriginal: readonly number[],
  pendingCurrent: readonly number[],
): AlignedPair[] {
  const pairs: AlignedPair[] = []
  const span = Math.max(pendingOriginal.length, pendingCurrent.length)
  for (let offset = 0; offset < span; offset++) {
    if (offset >= pendingCurrent.length) {
      pairs.push({ original: pendingOriginal[offset], current: null })
    } else if (offset >= pendingOriginal.length) {
      pairs.push({ original: null, current: pendingCurrent[offset] })
    } else {
      pairs.push({ original: pendingOriginal[offset], current: pendingCurrent[offset] })
    }
  }
  return pairs
}

/**
 * 配对两个数组的元素：先用相等元素锚定，间隙内再按相似度配对。
 *
 * <h2>为什么不能只按下标</h2>
 * 删掉数组中间或开头的一个元素后，它之后的所有元素都会左移一位。纯下标对齐会把
 * 「删了 1 个元素」渲染成「每个元素都被改写 + 末尾元素整体删除」—— 规则本身没错，
 * 但预览会指向完全错误的位置。锚定相等元素能把位移消化掉。
 *
 * <p>反过来也不能只用 LCS：LCS 只认「相等 / 不相等」，一个元素被改写会被拆成
 * 「删一个 + 加一个」，丢掉「同一个元素变了」这层信息。两者结合才两种形态都对。
 *
 * <h2>指纹只算一次，贯穿两级</h2>
 * 两级对齐都需要「这两个元素是否相等」。指纹在这里算一遍后同时喂给
 * {@link findEqualAnchors} 与 {@link alignRange} —— 后者在 O(n×m) 循环里用它，
 * 现算会让「无锚点」输入卡到半秒（实测 n=200 从 547ms 降到毫秒级）。
 */
function alignArrayElements(original: readonly unknown[], current: readonly unknown[]): AlignedPair[] {
  if (original.length > ARRAY_ALIGN_LIMIT || current.length > ARRAY_ALIGN_LIMIT) {
    return alignByIndex(original, current)
  }

  // 每个元素只序列化一次，两级对齐共用。
  const fingerprints = {
    left: original.map(stableFingerprint),
    right: current.map(stableFingerprint),
  }

  const pairs: AlignedPair[] = []
  let originalCursor = 0
  let currentCursor = 0
  for (const anchor of findEqualAnchors(fingerprints.left, fingerprints.right)) {
    pairs.push(...alignRange(
      original, current, fingerprints,
      originalCursor, anchor.original, currentCursor, anchor.current,
    ))
    pairs.push(anchor)
    originalCursor = anchor.original + 1
    currentCursor = anchor.current + 1
  }
  pairs.push(...alignRange(
    original, current, fingerprints,
    originalCursor, original.length, currentCursor, current.length,
  ))
  return pairs
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
    // 数组元素不展示键名，避免出现 "0": / "1": 序号前缀
    const children = alignArrayElements(original, current).map((pair) => {
      if (pair.current === null) {
        return wrapAsDeleted(null, original[pair.original])
      }
      if (pair.original === null) {
        return wrapAsStatus(null, current[pair.current], 'added')
      }
      return buildDiffTree(original[pair.original], current[pair.current], null)
    })
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
