import { buildEditableModel, type EditableModel } from './modelPayload'

/**
 * 拉取模型的差异计算与撤销。
 *
 * 拉取不直接覆盖模型列表，而是先算出三态差异让用户确认，并允许逐条撤销。
 * 这里只做纯数据变换，不涉及请求与 UI。
 */

export type PullDiffStatus = 'added' | 'removed' | 'unchanged'

export interface PullDiffEntry {
  modelName: string
  status: PullDiffStatus
  /** 已存在模型的原始配置，应用时用于保留能力位等已有设置。 */
  existingModel?: EditableModel
}

export interface PullDiff {
  entries: PullDiffEntry[]
  addedCount: number
  removedCount: number
}

/** 统计各状态数量。撤销后需要重算，故独立成函数。 */
function countByStatus(entries: PullDiffEntry[]): Pick<PullDiff, 'addedCount' | 'removedCount'> {
  return {
    addedCount: entries.filter(entry => entry.status === 'added').length,
    removedCount: entries.filter(entry => entry.status === 'removed').length,
  }
}

/**
 * 计算当前模型列表与上游拉取结果的差异。
 *
 * 顺序有意为之：先按**上游顺序**列出保留与新增，再把仅存在于本地的追加为移除。
 * 这样列表主体跟随上游，被删掉的集中在末尾，用户更容易看清将要发生什么。
 */
export function buildPullDiff(current: EditableModel[], pulledNames: string[]): PullDiff {
  const currentNames = new Set(current.map(model => model.modelName))
  const pulledSet = new Set(pulledNames)

  const entries: PullDiffEntry[] = []

  for (const name of pulledNames) {
    const existing = current.find(model => model.modelName === name)
    entries.push({
      modelName: name,
      status: currentNames.has(name) ? 'unchanged' : 'added',
      existingModel: existing,
    })
  }

  for (const model of current) {
    if (!pulledSet.has(model.modelName)) {
      entries.push({ modelName: model.modelName, status: 'removed', existingModel: model })
    }
  }

  return { entries, ...countByStatus(entries) }
}

/**
 * 撤销某一条变更。
 *
 * 新增的撤销 = 从列表移除（本来就不该出现）；
 * 移除的撤销 = 恢复为未变更（保留它）。
 * `unchanged` 无可撤销，原样返回。
 */
export function revertDiffEntry(diff: PullDiff, index: number): PullDiff {
  const target = diff.entries[index]
  if (!target || target.status === 'unchanged') return diff

  const entries = [...diff.entries]
  if (target.status === 'added') {
    entries.splice(index, 1)
  } else {
    entries[index] = { ...target, status: 'unchanged' }
  }

  return { entries, ...countByStatus(entries) }
}

/**
 * 应用差异，得到新的模型列表。
 *
 * 只丢弃 `removed`；`unchanged` 通过 `existingModel` 保留原有能力位配置，
 * `added` 走默认值。
 */
export function applyPullDiff(diff: PullDiff): EditableModel[] {
  return diff.entries
    .filter(entry => entry.status !== 'removed')
    .map(entry => buildEditableModel(entry.modelName, entry.existingModel ?? {}))
}

/** 是否存在实际变更，用于决定「应用」按钮可否点击。 */
export function hasChanges(diff: PullDiff): boolean {
  return diff.addedCount > 0 || diff.removedCount > 0
}
