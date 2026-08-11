import { describe, expect, it } from 'vitest'
import type { EditableModel } from './modelPayload'
import { applyPullDiff, buildPullDiff, hasChanges, revertDiffEntry } from './pullDiff'

const model = (name: string, over: Partial<EditableModel> = {}): EditableModel => ({
  modelName: name,
  enabled: true,
  contextSize: '128000',
  maxOutputTokens: '128000',
  capsTools: true,
  capsVision: false,
  reasoningEffort: 'Medium',
  ...over,
})

describe('buildPullDiff', () => {
  it('标记新增、保留与移除', () => {
    const diff = buildPullDiff([model('keep'), model('gone')], ['keep', 'fresh'])
    expect(diff.entries.map(e => [e.modelName, e.status])).toEqual([
      ['keep', 'unchanged'],
      ['fresh', 'added'],
      ['gone', 'removed'],
    ])
    expect(diff.addedCount).toBe(1)
    expect(diff.removedCount).toBe(1)
  })

  it('列表主体跟随上游顺序，移除项集中在末尾', () => {
    const diff = buildPullDiff([model('local')], ['b', 'a'])
    expect(diff.entries.map(e => e.modelName)).toEqual(['b', 'a', 'local'])
  })

  it('保留项携带原配置，供应用时复用能力位', () => {
    const diff = buildPullDiff([model('keep', { capsVision: true })], ['keep'])
    expect(diff.entries[0].existingModel?.capsVision).toBe(true)
  })

  it('完全一致时无变更', () => {
    const diff = buildPullDiff([model('a')], ['a'])
    expect(hasChanges(diff)).toBe(false)
  })

  it('本地为空时全部为新增', () => {
    const diff = buildPullDiff([], ['a', 'b'])
    expect(diff.addedCount).toBe(2)
    expect(diff.removedCount).toBe(0)
  })

  it('上游为空时全部为移除', () => {
    const diff = buildPullDiff([model('a')], [])
    expect(diff.removedCount).toBe(1)
  })
})

describe('revertDiffEntry', () => {
  it('撤销新增 → 从列表移除', () => {
    const diff = buildPullDiff([], ['a', 'b'])
    const reverted = revertDiffEntry(diff, 0)
    expect(reverted.entries.map(e => e.modelName)).toEqual(['b'])
    expect(reverted.addedCount).toBe(1)
  })

  it('撤销移除 → 恢复为未变更', () => {
    const diff = buildPullDiff([model('gone')], [])
    const reverted = revertDiffEntry(diff, 0)
    expect(reverted.entries[0].status).toBe('unchanged')
    expect(reverted.removedCount).toBe(0)
  })

  it('撤销未变更项无副作用', () => {
    const diff = buildPullDiff([model('a')], ['a'])
    expect(revertDiffEntry(diff, 0)).toBe(diff)
  })

  it('越界下标返回原对象', () => {
    const diff = buildPullDiff([model('a')], ['a'])
    expect(revertDiffEntry(diff, 99)).toBe(diff)
  })

  it('不原地修改入参', () => {
    const diff = buildPullDiff([], ['a'])
    const before = diff.entries.length
    revertDiffEntry(diff, 0)
    expect(diff.entries.length).toBe(before)
  })
})

describe('applyPullDiff', () => {
  it('丢弃移除项，保留其余', () => {
    const diff = buildPullDiff([model('keep'), model('gone')], ['keep', 'fresh'])
    expect(applyPullDiff(diff).map(m => m.modelName)).toEqual(['keep', 'fresh'])
  })

  it('未变更项保留原有能力位', () => {
    const diff = buildPullDiff([model('keep', { capsVision: true, enabled: false })], ['keep'])
    const applied = applyPullDiff(diff)
    expect(applied[0].capsVision).toBe(true)
    expect(applied[0].enabled).toBe(false)
  })

  it('新增项使用默认值', () => {
    const diff = buildPullDiff([], ['fresh'])
    const applied = applyPullDiff(diff)
    expect(applied[0]).toMatchObject({ modelName: 'fresh', capsTools: true, capsVision: false })
  })
})
