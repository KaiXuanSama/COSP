<script setup lang="ts">
/**
 * 按方向滑动切换的单值显示。
 *
 * <h2>要解决的问题</h2>
 * 滚轮把值推到相邻一档时，直接换文字会让人分不清「刚才滚了没」以及「往哪个方向走了」——
 * 尤其在连续滚动时，两个相邻档位的文案长度往往接近（`64K` 与 `32K`），
 * 瞬间替换看起来像闪了一下。
 *
 * <p>让旧值朝滚动方向滑出、新值从反方向滑入，方向就成了动画本身的一部分：
 * 向下滚，内容整体上移，与「列表往下走」的直觉一致。
 *
 * ```
 *   向下滚                 向上滚
 *   ┌────────┐            ┌────────┐
 *   │  64K ↑ │ 旧值上移    │  16K ↓ │ 旧值下移
 *   │  32K   │ 新值跟上    │  32K   │ 新值跟上
 *   └────────┘            └────────┘
 * ```
 *
 * <h2>为何是自己实现而不用 n-number-animation</h2>
 * Naive UI 那个是数字滚动到目标值（逐帧插值），这里要的是**离散换页**：
 * 档位可能是 `Medium` 这样的字符串，而数值档位之间差 2 倍，插值过程中出现的
 * 中间数字全是无意义的非法值。
 *
 * <h2>不用 transition-group</h2>
 * 同一时刻只有一个值，`<Transition>` 足够。用 `mode` 留空（默认同时进出）而非
 * `out-in`：后者要等旧值完全离开才开始进入，连续滚动时会积压成一串排队动画，
 * 手感明显滞后。两者重叠反而更像一个整体在滑动。
 */
import { computed, ref, watch } from 'vue'

const props = defineProps<{
    /** 当前要显示的值。变化时触发滑动。 */
    value: string | number
    /**
     * 上一次变化的方向，取 `wheelStep` 的下标增量语义。
     *
     * `1`（向下滚 / 后一项）→ 内容上移；`-1` → 内容下移。
     * `null` 表示这次变化不是滚动引起的（如手填、点击预设），不播放方向动画。
     */
    direction?: 1 | -1 | null
}>()

/**
 * 动画方向锁存在自己的 ref 里，而不是直接用 props。
 *
 * `direction` 由调用方在滚动时写入，但值也可能因手填而变化 —— 那时
 * `direction` 还留着上次滚动的值。这里在**值真正变化的那一刻**取一次方向快照，
 * 保证一次变化对应一个确定的动画，不会被后续的方向更新改写。
 */
const activeDirection = ref<1 | -1 | null>(null)

watch(() => props.value, () => {
    activeDirection.value = props.direction ?? null
})

/**
 * 过渡名随方向切换。
 *
 * 没有方向时用 `slide-none` —— 那个名字下没有定义任何 transition 样式，
 * 于是 Vue 走一遍空过渡、内容瞬间替换。这比 `:css="false"` 简单：
 * 后者要接管全部钩子。
 */
const transitionName = computed(() => {
    if (activeDirection.value === 1) return 'slide-up'
    if (activeDirection.value === -1) return 'slide-down'
    return 'slide-none'
})
</script>

<template>
    <span class="sliding-value">
        <Transition :name="transitionName">
            <!--
                key 绑在值上，值一变就触发进出过渡。
                position: absolute 只加在离开的那一份上（见样式），
                否则两份内容会把容器撑成两行高。
            -->
            <span :key="value" class="sliding-value__item">{{ value }}</span>
        </Transition>
    </span>
</template>

<style scoped lang="scss">
/**
 * 裁剪容器。
 *
 * `overflow: hidden` 是这个效果成立的前提 —— 滑出的内容必须被切掉，
 * 否则它会跑到相邻控件上面去。
 *
 * <p>`display: inline-grid` 而非 `inline-block`：进出的两份内容都放进同一个
 * grid 单元格，容器宽高由较大的那份决定，切换时不会塌成 0 再弹回。
 */
.sliding-value {
    display: inline-grid;
    overflow: hidden;
    // 行高等于容器高，滑动距离因此正好是一行 —— 用 em 而非固定 px，
    // 换字号时不用同步改动画距离。
    line-height: 1.4;
}

/**
 * 进出的两份内容重叠在同一个格子里。
 *
 * 都放在 `grid-area: 1 / 1` 而不是给离开的那份加 `position: absolute` ——
 * 绝对定位会脱离文档流、宽度塌成内容宽，而这里需要两份都参与尺寸计算，
 * 否则容器宽度会在切换瞬间跳一下。
 */
.sliding-value__item {
    grid-area: 1 / 1;
    white-space: nowrap;
}

/**
 * 向下滚：新值从下方进入，旧值向上离开。
 *
 * 用 `translateY(100%)` 而非固定像素：百分比相对元素自身高度，
 * 因此不同字号下滑动距离自动匹配一行。
 */
.slide-up-enter-from {
    transform: translateY(100%);
    opacity: 0;
}

.slide-up-leave-to {
    transform: translateY(-100%);
    opacity: 0;
}

/** 向上滚：方向相反。 */
.slide-down-enter-from {
    transform: translateY(-100%);
    opacity: 0;
}

.slide-down-leave-to {
    transform: translateY(100%);
    opacity: 0;
}

/**
 * 进出共用同一条曲线与时长。
 *
 * 160ms 是「看得见但不用等」的区间：连续滚动时每格都能看清方向，
 * 而快速滚三格也不会觉得卡。曲线用 ease-out —— 入场快、收尾缓，
 * 匹配「被推了一下然后停住」的物理感。
 *
 * <p>透明度一起动：纯位移在两个长度接近的档位之间（`64K` / `32K`）
 * 看起来像文字在抖，加上淡入淡出才能读出「换了一个」。
 */
.slide-up-enter-active,
.slide-up-leave-active,
.slide-down-enter-active,
.slide-down-leave-active {
    transition: transform 0.16s ease-out, opacity 0.16s ease-out;
}

/**
 * 尊重系统的「减少动态效果」偏好。
 *
 * 这个动画是纯装饰性的信息增强，关掉它不影响任何功能 ——
 * 对前庭功能障碍用户，滑动是实际的不适来源。
 */
@media (prefers-reduced-motion: reduce) {

    .slide-up-enter-active,
    .slide-up-leave-active,
    .slide-down-enter-active,
    .slide-down-leave-active {
        transition: none;
    }

    .slide-up-enter-from,
    .slide-up-leave-to,
    .slide-down-enter-from,
    .slide-down-leave-to {
        transform: none;
    }
}
</style>
