import type { ApiKeyEntry } from '@/stores/providers'

/**
 * API Key 条目的脱敏展示与三态判定。
 *
 * 一条 `ApiKeyEntry` 的状态由三个字段的组合表达，没有显式的 status 字段：
 *
 * | 状态         | keyUuid | masked | apiKey |
 * |--------------|---------|--------|--------|
 * | 已保存未修改 | 有      | 有     | 空     |
 * | 已保存待改   | 有      | 有     | 新明文 |
 * | 新增未保存   | 无      | 无     | 新明文 |
 *
 * 明文只在提交时上行，后端返回一律脱敏。判定逻辑集中在此，避免各处
 * 各写一遍 `k.keyUuid && k.apiKey?.trim()` 这类条件而出现口径分歧。
 */

/** 新增未保存条目在下拉框中的临时 value 前缀。 */
export const NEW_KEY_VALUE_PREFIX = '__new_'

/** 构造新增未保存条目的临时 value（提交前会被清成空串）。 */
export function newKeyValue(index: number): string {
  return `${NEW_KEY_VALUE_PREFIX}${index}`
}

/** 判断某个下拉 value 是否指向尚未保存的条目。 */
export function isNewKeyValue(value: string): boolean {
  return value.startsWith(NEW_KEY_VALUE_PREFIX)
}

/**
 * 脱敏显示：前 6 位 + `****` + 后 4 位。
 *
 * 长度 10 及以下不做部分暴露 —— 短 key 保留首尾会泄漏过大比例，
 * 直接返回 `****`。空串返回空串，便于模板直接插值。
 */
export function maskApiKey(key: string): string {
  if (!key || key.length <= 10) return key ? '****' : ''
  return key.substring(0, 6) + '****' + key.substring(key.length - 4)
}

/**
 * 展示某条 Key 的脱敏值。
 *
 * 优先展示用户刚输入的新明文（脱敏后），使「已保存待改」状态能立即看到变化；
 * 否则回退到后端下发的脱敏值。
 */
export function displayKey(entry: ApiKeyEntry): string {
  if (entry.apiKey && entry.apiKey.trim()) return maskApiKey(entry.apiKey.trim())
  return entry.masked || ''
}

/** 条目是否已在后端持久化。 */
export function isPersisted(entry: ApiKeyEntry): boolean {
  return !!entry.keyUuid && entry.keyUuid.length > 0
}

/** 条目是否携带了待提交的新明文。 */
export function hasPlaintext(entry: ApiKeyEntry): boolean {
  return !!entry.apiKey && entry.apiKey.trim().length > 0
}

/**
 * 过滤出值得保留的条目：已持久化的，或填了新明文的。
 *
 * 两者都不满足意味着用户点了「新增」但没填内容，属于空行，直接丢弃。
 */
export function keepMeaningfulEntries(entries: ApiKeyEntry[]): ApiKeyEntry[] {
  return entries.filter(entry => isPersisted(entry) || hasPlaintext(entry))
}

/** 提交给后端的单条 Key 载荷。仅回传必要字段，未修改时不带 apiKey。 */
export interface ApiKeyPayload {
  name: string
  keyUuid?: string
  apiKey?: string
}

/** 序列化为后端 `apiKeys` 字段所需的结构。 */
export function toApiKeyPayloads(entries: ApiKeyEntry[]): ApiKeyPayload[] {
  return entries.map(entry => {
    const payload: ApiKeyPayload = { name: entry.name || '' }
    if (entry.keyUuid) payload.keyUuid = entry.keyUuid
    if (hasPlaintext(entry)) payload.apiKey = entry.apiKey!.trim()
    return payload
  })
}

/** 拉取模型时解析出的凭据：要么明文，要么让后端按 UUID 解密。 */
export interface ResolvedPullCredential {
  apiKey?: string
  keyUuid?: string
}

/**
 * 解析「拉取模型」使用的凭据，按四级回退：
 *
 * 1. 当前选中条目里新输入的明文（覆盖新增未保存与重新输入两种场景）
 * 2. 任意一条有明文输入的条目
 * 3. 当前选中条目已持久化 → 交出 UUID 让后端解密
 * 4. 任意一条已持久化的条目 → 同上
 *
 * 全部落空返回 `null`，调用方据此提示用户先添加 Key。
 * 明文优先于 UUID 是有意的：用户刚输入的值应当立即生效，而不是等保存后才生效。
 */
export function resolvePullCredential(
  entries: ApiKeyEntry[],
  activeValue: string,
): ResolvedPullCredential | null {
  const activeEntry = entries.find(
    entry => (isPersisted(entry) && entry.keyUuid === activeValue) || newKeyValue(0) === activeValue,
  )

  if (activeEntry && hasPlaintext(activeEntry)) {
    return { apiKey: activeEntry.apiKey!.trim() }
  }

  const anyPlaintext = entries.find(hasPlaintext)
  if (anyPlaintext) {
    return { apiKey: anyPlaintext.apiKey!.trim() }
  }

  if (activeEntry && isPersisted(activeEntry)) {
    return { keyUuid: activeEntry.keyUuid }
  }

  const anyPersisted = entries.find(isPersisted)
  if (anyPersisted) {
    return { keyUuid: anyPersisted.keyUuid }
  }

  return null
}

/**
 * 删除条目后重新确定激活项。
 *
 * 原激活项仍在则保持不变；否则回落到第一条。列表空了返回空串。
 */
export function resolveActiveValue(entries: ApiKeyEntry[], currentActive: string): string {
  const stillExists = entries.some(entry => isPersisted(entry) && entry.keyUuid === currentActive)
  if (stillExists) return currentActive
  if (entries.length === 0) return ''
  return entries[0].keyUuid || newKeyValue(0)
}
