<script setup lang="ts" generic="M extends string">
/**
 * 「注入模式 | 值」双段控件的外壳。
 *
 * <h2>解决的问题</h2>
 * 凡是要回答「下游自己带了这个东西怎么办」的设置，都是 `{值, 模式}` 二元组。
 * 模式必须在**折叠状态**可见 —— 它决定旁边那个值到底会不会发出去，藏进下拉菜单的
 * header 就看不到了；做成独立控件又要再占一份横向空间，而这些字段通常只有行宽的三分之一。
 *
 * <p>于是有了这个形状：左边模式标签、竖线、右边值。
 *
 * ```
 * ┌──────────────────────────┐
 * │ 兜底 │ Medium          ⌄ │
 * └──────────────────────────┘
 *   ↑模式  ↑值（默认插槽）
 * ```
 *
 * <h2>为何泛型而非绑死注入模式</h2>
 * 本控件只做三件事：显示模式标签、按清单轮转模式、按状态决定值区的存在感。
 * 三件都与「模式集合里有哪些值」无关，因此用 `M extends string` 泛型化 ——
 * 鉴权头的 `downstream` / `configured` 不是注入模式的子集，却同样需要这个外壳。
 *
 * <p>标签与「值是否生效」因此都由调用方给出，而非从模块常量算出：
 * 它们是模式集合的**领域事实**，各字段不同，控件不该替它们决定。
 *
 * <h2>为何连边框一起接管</h2>
 * 值编辑器的形态因字段而异（枚举下拉、可手填输入框、带范围校验的输入框），
 * 若各自带框，同一行里就会出现两种边框来源 —— 思考深度自绘、最大输出用 `n-input` 的，
 * 结果 focus 态行为不一致（前者压根没有）。这里统一提供边框与 `:focus-within` 高亮，
 * 插进来的 `n-input` 由样式抹掉自己的框。
 *
 * <h2>不负责什么</h2>
 * 不管值的类型、不管值怎么编辑、不管持久化 —— 那些在调用方与各自的领域模块里。
 * 这里只有布局与模式轮转。
 *
 * <p>也不管数字字体与那 1px 的视觉居中补偿：上下文那一栏是纯数字但没有模式，
 * 补偿因此属于「数值输入框」这个正交维度，留在调用方的 `--numeric` 类里。
 */
import { computed, ref } from 'vue'
import { NTooltip } from 'naive-ui'

import {
  directionFromWheel,
  nextOverwriteMode,
  stepInSequence,
  type StepDirection,
} from '@/features/provider-config'

import SlidingValue from './SlidingValue.vue'

const props = defineProps<{
    /** 该字段支持的模式子集，顺序即点击轮转与滚轮步进的顺序。 */
    modes: readonly M[]
    /**
     * 各模式的显示标签。
     *
     * 由调用方给出而非控件内置：标签是模式集合的领域事实，各字段不同
     * （注入模式是「覆写 / 兜底 / 透传 / 删除」，鉴权头是「取下游 / 取设置」）。
     */
    labels: Record<M, string>
    /** 各模式的悬停说明。文案随字段变化（「此处配置的档位 / 上限」），故由调用方给出。 */
    hints: Record<M, string>
    /**
     * 此处配置的值在当前模式下怎么出站。
     *
     * - `used` —— 会出站，值区外观如常
     * - `fallback` —— 只在异常输入时才作为兜底出站
     * - `unused` —— 本次不会出站（如注入模式选了「透传」「删除」）
     *
     * <p>`fallback` 与 `used` **当前渲染一致**：值区都不灰显。这是刻意的 ——
     * 灰显在既有控件里表示「这个值不会生效」，而兜底态的值**是会生效的**，
     * 只是适用场合更窄。把它标成灰会让用户以为配了没用。
     * 兜底语义由模式段的 {@link hints} 文案承担，见 `authHeader.ts` 的
     * `AUTH_HEADER_MODE_HINTS`。
     *
     * <p>保留三态而非布尔是因为「不生效」与「仅在异常时生效」是两件事，
     * 混成一个布尔会让调用方无从表达后者 —— 而鉴权头恰好就是后者。
     */
    valueState: 'used' | 'fallback' | 'unused'
    /**
     * 整个字段不可用（含模式段）。
     *
     * 与 `unused` 不同：`unused` 是「本次不会出站但仍可编辑」，
     * 而这个是「在当前上文里根本无法表达任何意图」—— 模式也不应该能改，
     * 否则用户会在一个不生效的字段上转不同的注入模式。
     */
    disabled?: boolean
}>()

const mode = defineModel<M>('mode', { required: true })

const label = computed(() => props.labels[mode.value])
const hint = computed(() => props.hints[mode.value])
const inert = computed(() => props.valueState === 'unused')

/**
 * 最近一次模式变化的方向，供滑动动画决定往哪边滑。
 *
 * 点击轮转时置 `null` —— 点击没有方向语义（它是「换一个」），
 * 硬给一个方向会让同一个操作每次都朝同一边滑，看起来像滚了一下。
 */
const modeDirection = ref<StepDirection | null>(null)

/** 点击轮转。循环，与滚轮的「端点停住」刻意不同 —— 见 `wheelStep.ts`。 */
function cycle() {
    if (props.disabled) return
    modeDirection.value = null
    mode.value = nextOverwriteMode(mode.value, props.modes)
}

/**
 * 滚轮步进模式。
 *
 * `preventDefault` 是必须的：这些控件在抽屉里，不拦截的话滚轮会同时滚动抽屉，
 * 值变了但控件已经移出视野。事件在模板上用 `.prevent` 声明，
 * 因此这里只处理逻辑。
 *
 * <p>到端点时不写回：`stepInSequence` 返回原值，赋值虽无害但会让
 * `SlidingValue` 的 watch 空跑一次；这里显式判等，语义更清楚。
 */
function onWheel(event: WheelEvent) {
    if (props.disabled) return
    const direction = directionFromWheel(event.deltaY)
    if (direction === null) return
    const next = stepInSequence(mode.value, props.modes, direction)
    if (next === mode.value) return
    modeDirection.value = direction
    mode.value = next
}
</script>

<template>
    <div class="mode-scoped-field"
        :class="{ 'mode-scoped-field--inert': inert, 'mode-scoped-field--disabled': disabled }">
        <n-tooltip placement="top">
            <template #trigger>
                <button type="button" class="mode-scoped-field__mode" :disabled="disabled" @click="cycle"
                    @wheel.prevent="onWheel">
                    <sliding-value :value="label" :direction="modeDirection" />
                </button>
            </template>
            {{ hint }}
        </n-tooltip>
        <div class="mode-scoped-field__value">
            <slot />
        </div>
    </div>
</template>

<style scoped lang="scss">
@use '@/styles/variables' as *;

/**
 * 边框容器。
 *
 * 高度固定 28px 与同行的其它小号控件对齐。`:focus-within` 而非 `:focus` ——
 * 真正获得焦点的是插槽里的输入框，容器本身不可聚焦。
 */
.mode-scoped-field {
    display: flex;
    align-items: center;
    width: 100%;
    min-width: 0;
    height: 28px;
    padding: 0 8px;
    border: 1px solid $border;
    border-radius: $radius;
    background: $surface;
    color: $text-body;
    font-family: $font-body;
    font-size: 13px;
    white-space: nowrap;
    transition: border-color 0.15s ease, color 0.15s ease;

    &:hover,
    &:focus-within {
        border-color: $accent;
    }

    /**
     * 透传与删除两档不使用此处配置的值，整体降低存在感。
     *
     * 不用 `disabled`：值仍然可编辑 —— 它是切回其它模式时的备选值，
     * 用户改完再切模式是正常操作。这里表达的是「暂时不生效」而非「不可用」。
     */
    &--inert {
        color: $text-muted;
    }

    /**
     * 整个字段在当前上文里无意义（如思考深度已表达不思考）。
     *
     * 与 Naive UI 的禁用态对齐：添底色 + 降低对比，而非仅仅变灰 ——
     * 插槽里的 `n-input` 会自己变成禁用底色，外壳不跟上会看起来像只禁了一半。
     * hover 不再变色，因此鼠标移上来不会暗示可交互。
     */
    &--disabled {
        border-color: $border-light;
        background: $bg;
        color: $text-muted;
        cursor: not-allowed;

        &:hover,
        &:focus-within {
            border-color: $border-light;
        }

        .mode-scoped-field__mode {
            cursor: not-allowed;

            &:hover {
                color: $text-muted;
            }
        }
    }
}

/**
 * 模式标签。
 *
 * 右侧竖线把它与值分开 —— 没有分隔时它看起来像值的前半段。
 * 字号比正文小一号：它是标签而非可编辑内容，视觉上要能一眼区分。
 *
 * <p>字体用正文而非 mono：标签是中文，mono 字族里没有中文字形，声明了也只会回退。
 */
.mode-scoped-field__mode {
    display: flex;
    align-items: center;
    flex-shrink: 0;
    padding: 0 6px 0 0;
    margin-right: 6px;
    border: none;
    border-right: 1px solid $border;
    background: transparent;
    color: $text-muted;
    font-family: $font-body;
    font-size: 11px;
    line-height: 1.4;
    white-space: nowrap;
    cursor: pointer;
    transition: color 0.15s ease;

    &:hover {
        color: $accent;
    }

    /**
     * 标签宽度随内容走。
     *
     * <p>不能写死 em 数：覆写四档恰好都是两个汉字（「覆写」「兜底」「透传」「删除」），
     * 原先按此写死 `2em` 正好够用；鉴权头的两档是三个汉字（「取下游」「取设置」），
     * 于是被 `SlidingValue` 的 `overflow: hidden` **静默裁成两个字**（「取下」「取设」）——
     * 裁切不报错，看起来只像是文案怪异。
     *
     * <p>`max-content` 在这里不会引起过渡抖动：进出两份内容重叠在同一个 grid 单元格里，
     * 容器宽度是二者的并集，而同一字段内的标签是等宽的（覆写四档都两个汉字，
     * 鉴权头两档都三个），并集等于任一份，切换时宽度不变。
     *
     * <p>前提是**同一字段内标签等宽**。若将来给某个字段配上长度不一的标签
     * （如「取下游」配「跟随」），过渡的两帧里宽度会跳一次；届时应改成按该字段
     * 最长标签固定宽度，而不是退回写死 em。
     */
    :deep(.sliding-value) {
        width: max-content;
        justify-items: start;
    }
}

/**
 * 值区。
 *
 * 占满剩余宽度，`min-width: 0` 让内部的截断生效（缺了它 flex 子项会以内容宽为下界）。
 */
.mode-scoped-field__value {
    display: flex;
    align-items: center;
    flex: 1;
    min-width: 0;
    height: 100%;

    /**
     * 插进来的 n-input 交出边框与内边距，由外层容器统一提供。
     *
     * 边框有两层：`__border` 是静态的、`__state-border` 是 focus/hover 态的，
     * 只抹一层会在聚焦时冒出一个内嵌的圆角框。
     */
    :deep(.n-input) {
        --n-height: 100%;
        background: transparent;
    }

    :deep(.n-input__border),
    :deep(.n-input__state-border) {
        display: none;
    }

    :deep(.n-input .n-input-wrapper) {
        padding: 0;
    }
}
</style>
