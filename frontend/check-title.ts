import { formatCallTypeLabel, formatCallTypeTitle } from "./src/types/protocol"

const rows = [
  { u: "MESSAGES", d: "CHAT",     s: 1, note: "跨协议 C2M 链（本对话就是这条）" },
  { u: "CHAT",     d: "CHAT",     s: 1, note: "Chat 直连" },
  { u: "MESSAGES", d: "MESSAGES", s: 0, note: "Anthropic 直连非流式" },
  { u: "OPENAI",   d: "OPENAI",   s: 1, note: "迁移前存量行（首字母兜底）" },
  { u: "RESPONSES",d: "CHAT",     s: 1, note: "第二步的 R2C（前端未同步也可读）" },
]
for (const r of rows) {
  console.log(`[${r.note}]`)
  console.log(`  标记: ${formatCallTypeLabel(r.u, r.d, r.s)}`)
  console.log(`  气泡: ${formatCallTypeTitle(r.u, r.d)}`)
}
