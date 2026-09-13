/**
 * 请求体规则的路径与作用域工具。
 *
 * <h2>为什么这个文件存在</h2>
 * 这些逻辑原先长在 `RequestBodyRuleItem.vue` 里。前端只对 `features/**` 下的纯模块做单测
 * （43 个 spec 文件里没有一个测 SFC），所以困在组件内部的那份实现零覆盖 ——
 * 而它正是「数组模式下条件路径永远命中不了」这个缺陷的所在地。
 *
 * 抽成纯函数后，作用域规则可以被断言钉住，而不是靠读组件源码推断。
 */
import type { FieldRule } from './types'

/** 路径下拉选项。 */
export interface PathOption {
  label: string
  value: string
  [key: string]: unknown
}

/** 路径展开的最大深度，超过则不再往下钻。 */
const MAX_PATH_DEPTH = 2

/** 值是否为「普通对象」（排除 null 与数组）。 */
function isPlainObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

/**
 * 从数组里取第一个对象元素。
 *
 * <h2>为什么是「第一个」</h2>
 * 数组模式下条件的作用域是<strong>逐个元素</strong>——引擎对每个元素单独求值条件。
 * 编辑器只能展示一份候选路径，取第一个对象元素是「用样本代表全体」：
 * 同构的数组元素字段名相同，取哪个都一样。
 *
 * <p>遇到非对象元素继续往后找，而不是直接放弃：上游的数组常混有字符串与对象
 * （如 `content: [{type:"text"}, "plain"]`），首个是标量不代表没有可用的形状。
 */
export function firstObjectElement(value: unknown): Record<string, unknown> | null {
  if (!Array.isArray(value)) return null
  for (const item of value) {
    if (isPlainObject(item)) return item
  }
  return null
}

/**
 * 求条件表达式所在的<strong>作用域对象</strong>。
 *
 * <h2>作用域由「引擎在哪里求值」决定，不由「数据在哪」决定</h2>
 * 后端 `RequestBodyRuleEngine` 两个分支的求值对象不同：
 * <ul>
 *   <li>数组模式（`array: true`）—— `conditionsMatch(element, rule)`，
 *       条件是相对<strong>元素</strong>求值的，正确写法是 `./type`；</li>
 *   <li>标量模式（`array: false`）—— `conditionsMatch(scope, rule)`，
 *       条件是相对<strong>当前作用域对象</strong>求值的。</li>
 * </ul>
 *
 * <p>编辑器此前一律从当前作用域对象生成路径，于是数组模式下给出
 * `./tools[*]/type` —— 引擎拿它当元素相对路径解析，第一步找 `tools` 字段就找不到，
 * 直接返回 null。<strong>规则永不命中且零告警</strong>（「未匹配不告警」是引擎的既定
 * 决策，因为防御性规则本就该在上游没带那个字段时静默放行）。
 *
 * <p>症状因此是「配了规则但看起来完全没生效」，而预览面板显示的是
 * 「输入与输出一致」—— 与「字段本来就不存在」这一正常情形无法区分。
 *
 * <h2>`scopeObject` 与 `rule.field` 各承担一半</h2>
 * `scopeObject` 是<strong>上一层</strong>的作用域（顶层规则拿到整个请求体，
 * `edit_object` 内层规则拿到外层字段的值对象）；`rule.field` 是本规则要操作的目标字段。
 * 条件判断发生在「已经进入 `field` 之后」，所以作用域是
 * `scopeObject[field]`（或它的第一个对象元素）而不是 `scopeObject`。
 *
 * @param scopeObject 上一层的作用域对象；null 表示无法推断
 * @param rule 当前规则
 * @return 条件表达式的求值作用域；null 表示无法从样本推断（调用方应提示用户手工填写）
 */
export function resolveConditionScope(
  scopeObject: Record<string, unknown> | null,
  rule: FieldRule,
): Record<string, unknown> | null {
  if (!scopeObject || !rule.field) return null
  const value = scopeObject[rule.field]
  if (rule.array) {
    // 数组模式：条件是相对元素求值的（见方法注释）。
    return firstObjectElement(value)
  }
  // 标量模式：条件相对该字段的值对象求值。
  return isPlainObject(value) ? value : null
}

/**
 * 从对象生成条件路径选项。
 *
 * 生成的路径全部以 `./` 开头，即「相对本作用域」—— 与引擎的解析口径一致。
 *
 * @param obj 作用域对象
 * @param prefix 递归时积累的路径前缀
 * @param depth 当前深度，达到 {@link MAX_PATH_DEPTH} 后停止下钻
 * @param seenPaths 跨分支去重（同名路径可能来自数组的不同元素）
 */
export function generatePathOptions(
  obj: Record<string, unknown>,
  prefix = '',
  depth = 0,
  seenPaths = new Set<string>(),
): PathOption[] {
  if (depth > MAX_PATH_DEPTH) return []
  const options: PathOption[] = []
  const addOption = (path: string) => {
    if (seenPaths.has(path)) return
    seenPaths.add(path)
    options.push({ label: path, value: path })
  }
  for (const [key, value] of Object.entries(obj)) {
    const path = prefix ? `${prefix}/${key}` : `./${key}`
    if (Array.isArray(value)) {
      const arrayPath = `${path}[*]`
      addOption(arrayPath)
      // 继续展开数组元素的子字段 —— `[*]` 作为末端后缀是合法的写法
      // （引擎的 parsePath 只认段尾带 `[*]` 的段）。
      for (const item of value) {
        if (isPlainObject(item)) {
          options.push(...generatePathOptions(item, arrayPath, depth + 1, seenPaths))
        }
      }
    } else if (isPlainObject(value)) {
      addOption(path)
      options.push(...generatePathOptions(value, path, depth + 1, seenPaths))
    } else {
      addOption(path)
    }
  }
  return options
}
