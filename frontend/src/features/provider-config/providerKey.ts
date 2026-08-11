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
