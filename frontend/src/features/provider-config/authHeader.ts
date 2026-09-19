/**
 * 出站鉴权头的装配方式：模式 + 承载头。
 *
 * <h2>为何不是「选哪个头」一个值的事</h2>
 * 出站鉴权头曾经由代码**按上游协议**决定（Messages 发 `x-api-key`，Chat / Responses 发
 * `Authorization: Bearer`）。那条映射建立在「Anthropic 官方用 x-api-key」之上，实测不成立：
 *
 * - Claude CLI 的头名由**凭据环境变量**决定，与协议无关 —— `ANTHROPIC_API_KEY` 走
 *   `x-api-key`，`ANTHROPIC_AUTH_TOKEN` 走 `Authorization: Bearer`，而 cc-switch 默认走后者；
 * - 部分中转站只认 `Authorization`，收到 `x-api-key` 会直接拒绝。
 *
 * 根因不是「选错了那一档」，而是**这个选择本身不该由代码替用户做** —— 单个值无法表达
 * 「下游自己做了选择怎么办」。
 *
 * <h2>两个维度</h2>
 * | 模式 | 含义 |
 * | --- | --- |
 * | `downstream`（取下游） | 下游恰好带了一种鉴权头就沿用那一种 |
 * | `configured`（取设置） | 始终用配置的头名 |
 *
 * ```
 *              下游恰好带 1 个       下游带 0 个或 2 个
 * downstream   认下游选的那个头     兜底：用配置的头名
 * configured   用配置的头名         用配置的头名
 * ```
 *
 * `downstream` 下配置的头名**不是**「用不上」，而是「异常时的兜底」——
 * 语义与「不生效」完全不同，因此界面上**不灰显**（见 {@link authHeaderValueState}）。
 *
 * <h2>持久化形态</h2>
 * 存在 `provider_config.auth_header` 一列里：
 * `{"mode":"DOWNSTREAM","header":"AUTHORIZATION"}`。
 *
 * <p>**落库用大写枚举名，界面用小写字面量**，两者靠 {@link MODE_TO_WIRE} /
 * {@link HEADER_TO_WIRE} 互译。落库之所以不存头名字面量：头名大小写不敏感且有拼写变体
 * （`X-Api-Key` / `x-api-key`），存字面量等于把「第几处写了哪种拼写」变成配置的一部分；
 * 界面之所以不直接用枚举名：`X_API_KEY` 是标识符不是给人看的，展示文本由
 * {@link AUTH_HEADER_NAME_OPTIONS} 决定。
 */

import { nextOverwriteMode } from './overwriteMode'

/** 鉴权头的选择模式。 */
export type AuthHeaderMode = 'downstream' | 'configured'

/**
 * 承载供应商 key 的出站头名。
 *
 * <p>用小写连字符形态（`x-api-key`）而非落库的枚举名，理由见文件头 ——
 * 它同时是 HTTP 头名，与落库标识是两件事。
 */
export type AuthHeaderName = 'authorization' | 'x-api-key'

export interface AuthHeaderConfig {
  mode: AuthHeaderMode
  header: AuthHeaderName
}

/** 轮转顺序即界面点击与滚轮的步进顺序。 */
export const AUTH_HEADER_MODES: readonly AuthHeaderMode[] = ['downstream', 'configured']

export const AUTH_HEADER_MODE_LABELS: Record<AuthHeaderMode, string> = {
  downstream: '取下游',
  configured: '取设置',
}

/**
 * 模式的中文说明。
 *
 * `downstream` 那条必须点明**兜底**语义：只说「沿用下游的选择」会让用户以为右侧的头名
 * 用不上，而它在下游带 0 或 2 个头时正是出站的那一个。
 */
export const AUTH_HEADER_MODE_HINTS: Record<AuthHeaderMode, string> = {
  downstream: '下游恰好带了一种鉴权头就沿用那一种；带了 0 个或 2 个时，用右侧配置的头作为兜底',
  configured: '始终用右侧配置的头，忽略下游带的是哪一种',
}

/**
 * 头名的展示文本。
 *
 * 单列一份而非从 options 反推：反推要么用 `Object.fromEntries` 加一次断言，
 * 要么让 options 的声明顺序决定映射 —— 而这两者都不如显式表清楚。
 * `x-api-key` 保持小写是刻意的，它就是那个头在 HTTP 上的常见写法。
 */
export const AUTH_HEADER_NAME_LABELS: Record<AuthHeaderName, string> = {
  authorization: 'Authorization',
  'x-api-key': 'x-api-key',
}

/** 头名下拉的选项。标签取自 {@link AUTH_HEADER_NAME_LABELS}，避免两处文案漂移。 */
export const AUTH_HEADER_NAME_OPTIONS: readonly { label: string, value: AuthHeaderName }[] = [
  { label: AUTH_HEADER_NAME_LABELS.authorization, value: 'authorization' },
  { label: AUTH_HEADER_NAME_LABELS['x-api-key'], value: 'x-api-key' },
]

/**
 * 默认取「取下游 + Authorization」。
 *
 * 它让存量行为几乎不变 —— 下游带了什么就还发什么：
 * 原 Messages 供应商在「下游带 x-api-key」时行为完全一致；只有「下游一个头都没带」的
 * 原 Messages 供应商从 `x-api-key` 变为 `Authorization`，而那正是本次要修的场景。
 *
 * <p>必须与后端 `AuthHeaderSetting.DEFAULT_AUTH_HEADER_JSON`、`schema.sql` 的 DEFAULT
 * 同语义（三处分叉会让「新库」「升级后的库」「保存过一次的供应商」看起来是三种配置）。
 */
export const DEFAULT_AUTH_HEADER_CONFIG: AuthHeaderConfig = {
  mode: 'downstream',
  header: 'authorization',
}

/** 模式 → 落库枚举名。 */
const MODE_TO_WIRE: Record<AuthHeaderMode, string> = {
  downstream: 'DOWNSTREAM',
  configured: 'CONFIGURED',
}

/** 头名 → 落库枚举名。 */
const HEADER_TO_WIRE: Record<AuthHeaderName, string> = {
  authorization: 'AUTHORIZATION',
  'x-api-key': 'X_API_KEY',
}

/**
 * 该模式下配置的头名会在什么场合出站，供 `ModeScopedField` 决定值区的存在感。
 *
 * <p><strong>两档都不返回 `unused`</strong>，这是与思考深度／最大输出的本质差别：
 * 那两者有「透传」「删除」档，配置的值真的不会被发送；而鉴权头**永远要发一个**，
 * `downstream` 只是把它的适用场合缩小到「异常输入」。
 *
 * <p>因此值区不灰显 —— 灰显在既有控件里表示「这个值不会生效」，用在这里会误导。
 * 兜底语义由 {@link AUTH_HEADER_MODE_HINTS} 的文案承担。
 */
export function authHeaderValueState(mode: AuthHeaderMode): 'used' | 'fallback' {
  return mode === 'configured' ? 'used' : 'fallback'
}

/** 取轮转顺序里的下一个模式，供控件的点击轮转。 */
export function nextAuthHeaderMode(current: AuthHeaderMode): AuthHeaderMode {
  return nextOverwriteMode(current, AUTH_HEADER_MODES)
}

/**
 * 解析落库的 JSON 原文，宽容到「认不出就回默认」。
 *
 * 宽容的理由与其它 `{值, 模式}` 字段一致：值来自数据库，一行脏数据不该让整个供应商
 * 列表无法编辑。写入侧的严格校验在 `Settings.vue` 的提交路径上（只可能写出合法值）。
 *
 * <p>两个维度**各自回退**而非整体回退：`mode` 认不出不影响 `header` 的正确解析，
 * 反之亦然。整体回退会把用户明确设过的另一维也一起丢掉。
 */
export function parseAuthHeaderConfig(raw: unknown): AuthHeaderConfig {
  if (typeof raw !== 'string' || !raw.trim()) {
    return { ...DEFAULT_AUTH_HEADER_CONFIG }
  }
  const trimmed = raw.trim()
  // 非 JSON 形态（历史裸值、被截断的行）一律回默认，不做「猜一个模式」的尝试。
  if (!trimmed.startsWith('{')) {
    return { ...DEFAULT_AUTH_HEADER_CONFIG }
  }
  try {
    const parsed = JSON.parse(trimmed) as Record<string, unknown>
    return {
      mode: parseMode(parsed.mode),
      header: parseHeader(parsed.header),
    }
  } catch {
    return { ...DEFAULT_AUTH_HEADER_CONFIG }
  }
}

/**
 * 认不出的模式回退 `downstream`。
 *
 * 回退到「取下游」而非「取设置」：前者在多数情况下与存量的按协议分派行为等价
 * （下游带了什么就发什么），是更保守的一侧。
 */
function parseMode(value: unknown): AuthHeaderMode {
  const raw = typeof value === 'string' ? value.trim().toLowerCase() : ''
  return raw === 'configured' ? 'configured' : 'downstream'
}

/**
 * 认不出的头名回退 `authorization`。
 *
 * 下划线转连字符是必须的：落库写的是 `X_API_KEY`，直接小写会得到 `x_api_key`，
 * 与界面字面量 `x-api-key` 不等。
 */
function parseHeader(value: unknown): AuthHeaderName {
  const raw = typeof value === 'string' ? value.trim().toLowerCase().replace(/_/g, '-') : ''
  return raw === 'x-api-key' ? 'x-api-key' : 'authorization'
}

/**
 * 序列化成提交用的 JSON 字符串。
 *
 * 键序固定为 `mode` 在 `header` 前 —— 后端按键名解析，键序不影响正确性，但固定它让
 * 「默认配置序列化的结果」与后端默认常量可以逐字比对（测试钉住这一点）。
 */
export function serializeAuthHeaderConfig(config: AuthHeaderConfig): string {
  const mode = MODE_TO_WIRE[config.mode] ?? MODE_TO_WIRE.downstream
  const header = HEADER_TO_WIRE[config.header] ?? HEADER_TO_WIRE.authorization
  return JSON.stringify({ mode, header })
}
