/**
 * 展示名 → provider-key 的派生规则。
 *
 * 与后端 `ProviderAdminService#toProviderKey` 是**同语义的两份实现**：
 *
 *   displayName.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "")
 *
 * 之所以前端也要有一份：改名后 providerKey 会随之变化，前端需要在请求返回前
 * 就知道新 key，用于迁移本地的展示元数据。两份实现必须同步，`providerKey.spec.ts`
 * 用后端口径的用例锁死契约 —— 改任一侧都要让另一侧的断言仍然通过。
 */

/**
 * 把供应商展示名转换为路由用的 provider-key。
 *
 * 注意 `[^a-z0-9]+` 是在 `toLowerCase()` **之后**匹配的，所以任何非 ASCII
 * 字母数字（中文、日文、emoji）都会被整段折叠成单个 `-`。纯非 ASCII 名称
 * 因此会得到空串，调用方需自行处理这种退化情况。
 */
export function toProviderKey(displayName: string): string {
  return displayName
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')
}

/**
 * 路由标识的派生状态。
 *
 * - `blank` —— 还没输入名称，不展示任何提示
 * - `ok` —— 标识完整反映了名称
 * - `lossy` —— 能生成标识，但名称里有含义的字符被丢弃了（如 `智谱AI` → `ai`）
 * - `unavailable` —— 名称里没有任何 ASCII 字母数字，标识为空，必须改名
 * - `conflict` —— 标识已被其它供应商占用
 */
export type ProviderKeyStatus = 'blank' | 'ok' | 'lossy' | 'unavailable' | 'conflict'

export interface ProviderKeyDescription {
  /** 派生出的路由标识。`unavailable` 时为空串。 */
  key: string
  status: ProviderKeyStatus
  /**
   * 被丢弃的「有含义」字符，去重且保持出现顺序。
   *
   * 只统计 Unicode 字母与数字 —— 括号、空格这类分隔符本就该被折叠成 `-`，
   * 把它们算作损失会让绝大多数正常名称（如 `Kimi (CodePlan)`）被误报。
   */
  droppedChars: string[]
  /** 编辑模式下改名时的原标识；新建或未改名时为 null。 */
  previousKey: string | null
  /** 改名是否会导致 Copilot 侧的模型前缀变化。 */
  renamed: boolean
  /** 冲突时占用该标识的供应商展示名。 */
  conflictWith: string | null
  /** 是否允许提交。`unavailable` 与 `conflict` 为 false。 */
  submittable: boolean
}

export interface DescribeProviderKeyOptions {
  /** 现有供应商的 `标识 → 展示名` 映射，用于本地重名检测。 */
  existing?: Record<string, string>
  /** 编辑模式下正在编辑的供应商标识，重名检测时排除自身。 */
  currentKey?: string | null
}

/** 判断某字符是否「有含义」却无法进入标识：是 Unicode 字母/数字，但不是 ASCII 字母数字。 */
function isDroppedMeaningfulChar(char: string): boolean {
  if (/[A-Za-z0-9]/.test(char)) return false
  return /[\p{L}\p{N}]/u.test(char)
}

/** 收集名称中被丢弃的有含义字符，去重并保持出现顺序。 */
function collectDroppedChars(displayName: string): string[] {
  const dropped = new Set<string>()
  for (const char of displayName) {
    if (isDroppedMeaningfulChar(char)) dropped.add(char)
  }
  return Array.from(dropped)
}

/**
 * 描述某个展示名会派生出什么样的路由标识，以及是否可以提交。
 *
 * 供界面在用户输入时就把「后端会怎么转换」摊开来讲，避免两类静默问题：
 * 纯中文名派生出空标识却仍能入库，以及 `MiMo中转` 与 `中文 MiMo` 这类
 * 展示名不同但标识相同的冲突 —— 后者若交给后端，只会得到一句
 * 「该供应商名称已存在」，与用户看到的两个不同名称对不上。
 *
 * 状态优先级：`blank` > `unavailable` > `conflict` > `lossy` > `ok`。
 * 冲突排在有损之前，因为它直接阻止提交。
 */
export function describeProviderKey(
  displayName: string,
  options: DescribeProviderKeyOptions = {},
): ProviderKeyDescription {
  const { existing = {}, currentKey = null } = options
  const trimmed = displayName.trim()
  const key = toProviderKey(trimmed)
  const droppedChars = collectDroppedChars(trimmed)
  const renamed = !!currentKey && key !== '' && key !== currentKey
  const previousKey = renamed ? currentKey : null

  const base = { key, droppedChars, previousKey, renamed, conflictWith: null as string | null }

  if (!trimmed) {
    return { ...base, status: 'blank', submittable: false }
  }
  if (!key) {
    return { ...base, status: 'unavailable', submittable: false }
  }

  const occupiedBy = key !== currentKey ? existing[key] : undefined
  if (occupiedBy !== undefined) {
    return { ...base, status: 'conflict', conflictWith: occupiedBy || key, submittable: false }
  }

  if (droppedChars.length > 0) {
    return { ...base, status: 'lossy', submittable: true }
  }
  return { ...base, status: 'ok', submittable: true }
}
