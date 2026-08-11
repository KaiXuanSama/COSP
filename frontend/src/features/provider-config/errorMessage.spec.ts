import { describe, expect, it } from 'vitest'
import { resolvePullModelsErrorMessage } from './errorMessage'

const err = (status?: number, data?: unknown) => ({ response: { status, data } })

describe('resolvePullModelsErrorMessage', () => {
  it('优先透出后端对象里的 error 字段', () => {
    expect(resolvePullModelsErrorMessage(err(500, { error: '上游连接超时' })))
      .toBe('模型拉取失败：上游连接超时')
  })

  it('解析 JSON 字符串响应体里的 error', () => {
    expect(resolvePullModelsErrorMessage(err(400, '{"error":"base url 非法"}')))
      .toBe('模型拉取失败：base url 非法')
  })

  it('非 JSON 字符串直接作为原因展示', () => {
    expect(resolvePullModelsErrorMessage(err(502, 'Bad Gateway')))
      .toBe('模型拉取失败：Bad Gateway')
  })

  it('去除响应体文本首尾空白', () => {
    expect(resolvePullModelsErrorMessage(err(502, '  Bad Gateway  ')))
      .toBe('模型拉取失败：Bad Gateway')
  })

  // 后端信息优先于状态码：401 若带了具体原因，应展示原因而非通用文案。
  it('后端信息优先于状态码兜底', () => {
    expect(resolvePullModelsErrorMessage(err(401, { error: '密钥已过期' })))
      .toBe('模型拉取失败：密钥已过期')
  })

  it('无后端信息时按 401 / 403 提示鉴权', () => {
    expect(resolvePullModelsErrorMessage(err(401))).toBe('模型拉取失败：API Key 无效或无权限')
    expect(resolvePullModelsErrorMessage(err(403))).toBe('模型拉取失败：API Key 无效或无权限')
  })

  it('无后端信息时按 404 提示端点不存在', () => {
    expect(resolvePullModelsErrorMessage(err(404))).toBe('模型拉取失败：模型列表端点不存在')
  })

  it('其余情况回退网络提示', () => {
    expect(resolvePullModelsErrorMessage(err(500))).toBe('拉取模型失败，请检查网络连接和 API 地址')
  })

  it('无 response 的异常（网络中断）也有兜底', () => {
    expect(resolvePullModelsErrorMessage(new Error('Network Error')))
      .toBe('拉取模型失败，请检查网络连接和 API 地址')
    expect(resolvePullModelsErrorMessage(undefined))
      .toBe('拉取模型失败，请检查网络连接和 API 地址')
  })

  it('空字符串与空 error 字段不误判为有效信息', () => {
    expect(resolvePullModelsErrorMessage(err(500, ''))).toBe('拉取模型失败，请检查网络连接和 API 地址')
    expect(resolvePullModelsErrorMessage(err(500, { error: '   ' }))).toBe('拉取模型失败，请检查网络连接和 API 地址')
  })
})
