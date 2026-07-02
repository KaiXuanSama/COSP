/**
 * 复制文本到剪贴板，兼容老浏览器。
 *
 * @param text 要复制的文本
 * @returns Promise<boolean> true=成功，false=失败
 */
export async function copyToClipboard(text: string): Promise<boolean> {
  // 优先使用现代 API
  if (navigator.clipboard && window.isSecureContext) {
    try {
      await navigator.clipboard.writeText(text)
      return true
    } catch {
      // 降级到 textarea 方案
    }
  }
  // 降级方案：创建临时 textarea + execCommand
  try {
    const ta = document.createElement('textarea')
    ta.value = text
    ta.setAttribute('readonly', '')
    ta.style.position = 'fixed'
    ta.style.top = '0'
    ta.style.left = '0'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.focus()
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}
