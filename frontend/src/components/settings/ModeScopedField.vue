<script setup lang="ts">
/**
 * 「注入模式 | 值」双段控件的外壳。
 *
 * <h2>解决的问题</h2>
 * 凡是会进入发往上游请求体的模型参数，都需要回答「下游自己带了这个字段怎么办」，
 * 于是每一个都是 `{值, 模式}` 二元组。模式必须在**折叠状态**可见 —— 它决定旁边那个值
 * 到底会不会发出去，藏进下拉菜单的 header 就看不到了；做成独立控件又要再占一份
 * 横向空间，而这些字段通常只有行宽的三分之一。
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
 * <h2>为何连边框一起接管</h2>
 * 值编辑器的形态因字段而异（枚举下拉、可手填输入框、带范围校验的输入框），
 * 若各自带框，同一行里就会出现两种边框来源 —— 思考深度自绘、最大输出用 `n-input` 的，
 * 结果 focus 态行为不一致（前者压根没有）。这里统一提供边框与 `:focus-within` 高亮，
 * 插进来的 `n-input` 由样式抹掉自己的框。
 *
 * <h2>不负责什么</h2>
 * 不管值的类型、不管值怎么编辑、不管持久化 —— 那些在调用方与
 * `features/provider-config/overwriteMode.ts` 里。这里只有布局与模式轮转。
 *
 * <p>也不管数字字体与那 1px 的视觉居中补偿：上下文那一栏是纯数字但没有模式，
 * 补偿因此属于「数值输入框」这个正交维度，留在调用方的 `--numeric` 类里。
 */
import { computed } from 'vue'

import {
  OVERWRITE_MODE_LABELS,
  modeUsesConfiguredValue,
  nextOverwriteMode,
  type OverwriteMode,
} from '@/features/provider-config'

const props = defineProps<{
    /** 该字段支持的模式子集，顺序即点击轮转的顺序。 */
    modes: readonly OverwriteMode[]
    /** 各模式的悬停说明。文案随字段变化（「此处配置的档位 / 上限」），故由调用方给出。 */
    hints: Record<OverwriteMode, string>
}>()

const mode = defineModel<OverwriteMode>('mode', { required: true })

/**
 * 标签与「值是否生效」都从模式算出，不作为 props。
 *
 * 传进来只会多两个可能与 `mode` 不一致的入口 —— 标签全局统一、
 * inert 判定是模式的固有属性，两者都没有按字段定制的余地。
 */
const label = computed(() => OVERWRITE_MODE_LABELS[mode.value])
const hint = computed(() => props.hints[mode.value])
const inert = computed(() => !modeUsesConfiguredValue(mode.value))

function cycle() {
    mode.value = nextOverwriteMode(mode.value, props.modes)
}
</script>

<template>
    <div class="mode-scoped-field" :class="{ 'mode-scoped-field--inert': inert }">
        <button type="button" class="mode-scoped-field__mode" :title="hint" @click="cycle">
            {{ label }}
        </button>
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
