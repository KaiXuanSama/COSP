import { inject, onBeforeUnmount, onMounted, provide, shallowRef, type InjectionKey, type Ref } from 'vue'

const ruleSelectMenuTargetKey: InjectionKey<Ref<HTMLElement | undefined>> = Symbol('rule-select-menu-target')

/** 规则菜单与触发区使用相同 zoom，但挂在弹窗内容滚动区之外，避免被横向滚动裁切。 */
export function provideRuleSelectMenuTarget(area: Ref<HTMLElement | null>) {
  const target = shallowRef<HTMLElement>()
  provide(ruleSelectMenuTargetKey, target)
  let observer: ResizeObserver | undefined

  onMounted(() => {
    const element = area.value
    if (!element) return
    const doc = element.ownerDocument
    const container = element.closest<HTMLElement>('.n-modal') ?? doc.body
    const host = doc.createElement('div')
    host.className = 'rule-select-menu-host'
    Object.assign(host.style, {
      position: 'absolute', left: '0', right: '0', top: '0', height: '0',
      pointerEvents: 'none', overflow: 'visible',
    })

    function syncZoom() {
      let zoom = 1
      for (let current: HTMLElement | null = element; current && current !== container; current = current.parentElement) {
        zoom *= Number.parseFloat(getComputedStyle(current).zoom) || 1
      }
      host.style.zoom = String(zoom)
    }

    syncZoom()
    container.append(host)
    target.value = host
    // CSS zoom / 容器尺寸变化时重读比例，保留 --group-rules-zoom 的宿主覆盖能力。
    observer = new ResizeObserver(syncZoom)
    observer.observe(element)
  })

  onBeforeUnmount(() => {
    observer?.disconnect()
    target.value?.remove()
    target.value = undefined
  })
}

/** 独立规则或帮助示例没有缩放宿主时，保持 Naive UI 默认挂载行为。 */
export function useRuleSelectMenuTarget() {
  return inject(ruleSelectMenuTargetKey, shallowRef<HTMLElement>())
}
