import { describe, expect, it } from 'vitest'
import type { ApiKeyEntry } from '@/stores/providers'
import {
  displayKey,
  hasPlaintext,
  isNewKeyValue,
  isPersisted,
  keepMeaningfulEntries,
  maskApiKey,
  newKeyValue,
  resolveActiveValue,
  resolvePullCredential,
  toApiKeyPayloads,
} from './apiKey'

const persisted = (uuid: string, name = 'saved'): ApiKeyEntry => ({
  keyUuid: uuid,
  name,
  masked: 'sk-abc****wxyz',
})

const persistedEdited = (uuid: string, plaintext: string): ApiKeyEntry => ({
  keyUuid: uuid,
  name: 'saved',
  masked: 'sk-abc****wxyz',
  apiKey: plaintext,
})

const draft = (plaintext: string, name = 'draft'): ApiKeyEntry => ({ name, apiKey: plaintext })

describe('maskApiKey', () => {
  it('长 key 保留前 6 后 4', () => {
    expect(maskApiKey('sk-1234567890abcdef')).toBe('sk-123****cdef')
  })

  it('长度 10 及以下一律 ****，避免暴露过大比例', () => {
    expect(maskApiKey('sk-1234567')).toBe('****')
    expect(maskApiKey('short')).toBe('****')
  })

  it('空串返回空串', () => {
    expect(maskApiKey('')).toBe('')
  })
})

describe('displayKey', () => {
  it('有新明文时展示新明文的脱敏值', () => {
    expect(displayKey(persistedEdited('u1', 'sk-newnewnewnew1234'))).toBe('sk-new****1234')
  })

  it('无新明文时回退后端脱敏值', () => {
    expect(displayKey(persisted('u1'))).toBe('sk-abc****wxyz')
  })

  it('两者皆无时为空串', () => {
    expect(displayKey({ name: 'x' })).toBe('')
  })

  it('明文仅含空白视为未输入', () => {
    expect(displayKey({ name: 'x', apiKey: '   ', masked: 'm' })).toBe('m')
  })
})

describe('三态判定', () => {
  it('区分已持久化与草稿', () => {
    expect(isPersisted(persisted('u1'))).toBe(true)
    expect(isPersisted(draft('sk-x'))).toBe(false)
    expect(hasPlaintext(draft('sk-x'))).toBe(true)
    expect(hasPlaintext(persisted('u1'))).toBe(false)
  })

  it('空白明文不算已输入', () => {
    expect(hasPlaintext({ name: 'x', apiKey: '  ' })).toBe(false)
  })
})

describe('keepMeaningfulEntries', () => {
  it('丢弃既未持久化也没填明文的空行', () => {
    const kept = keepMeaningfulEntries([persisted('u1'), draft('sk-x'), { name: '', apiKey: '' }])
    expect(kept).toHaveLength(2)
  })
})

describe('toApiKeyPayloads', () => {
  it('未修改的已保存条目只回传 keyUuid，不带 apiKey', () => {
    expect(toApiKeyPayloads([persisted('u1', 'main')])).toEqual([{ name: 'main', keyUuid: 'u1' }])
  })

  it('已保存待改的条目同时带 keyUuid 与新明文', () => {
    expect(toApiKeyPayloads([persistedEdited('u1', ' sk-new ')])).toEqual([
      { name: 'saved', keyUuid: 'u1', apiKey: 'sk-new' },
    ])
  })

  it('新增条目只带明文', () => {
    expect(toApiKeyPayloads([draft('sk-fresh', 'n1')])).toEqual([{ name: 'n1', apiKey: 'sk-fresh' }])
  })

  it('名称缺失时回退空串而非 undefined', () => {
    expect(toApiKeyPayloads([{ name: '', apiKey: 'sk-a' }])[0].name).toBe('')
  })
})

describe('resolvePullCredential', () => {
  it('优先用选中条目的新明文', () => {
    const entries = [persistedEdited('u1', 'sk-active'), draft('sk-other')]
    expect(resolvePullCredential(entries, 'u1')).toEqual({ apiKey: 'sk-active' })
  })

  it('选中条目无明文时退到任意有明文的条目', () => {
    const entries = [persisted('u1'), draft('sk-other')]
    expect(resolvePullCredential(entries, 'u1')).toEqual({ apiKey: 'sk-other' })
  })

  it('完全无明文时交出选中条目的 UUID 让后端解密', () => {
    const entries = [persisted('u1'), persisted('u2')]
    expect(resolvePullCredential(entries, 'u2')).toEqual({ keyUuid: 'u2' })
  })

  it('选中项不存在时退到任意已持久化条目', () => {
    const entries = [persisted('u1')]
    expect(resolvePullCredential(entries, 'nonexistent')).toEqual({ keyUuid: 'u1' })
  })

  it('新增未保存条目通过临时 value 命中并交出明文', () => {
    const entries = [draft('sk-fresh')]
    expect(resolvePullCredential(entries, newKeyValue(0))).toEqual({ apiKey: 'sk-fresh' })
  })

  it('空列表返回 null 供调用方提示', () => {
    expect(resolvePullCredential([], '')).toBeNull()
  })

  it('只有空行时返回 null', () => {
    expect(resolvePullCredential([{ name: '', apiKey: '  ' }], '')).toBeNull()
  })
})

describe('resolveActiveValue', () => {
  it('激活项仍存在则保持不变', () => {
    expect(resolveActiveValue([persisted('u1'), persisted('u2')], 'u2')).toBe('u2')
  })

  it('激活项被删则回落第一条', () => {
    expect(resolveActiveValue([persisted('u1')], 'deleted')).toBe('u1')
  })

  it('列表为空返回空串', () => {
    expect(resolveActiveValue([], 'u1')).toBe('')
  })

  it('首项为未保存草稿时给出临时 value', () => {
    expect(resolveActiveValue([draft('sk-x')], 'gone')).toBe(newKeyValue(0))
  })
})

describe('isNewKeyValue', () => {
  it('识别临时 value', () => {
    expect(isNewKeyValue(newKeyValue(3))).toBe(true)
    expect(isNewKeyValue('u1')).toBe(false)
  })
})
