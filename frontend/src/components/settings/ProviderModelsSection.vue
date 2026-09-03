<script setup lang="ts">
import { NButton, NCheckbox, NForm, NFormItem, NInput, NPopselect, NSwitch } from 'naive-ui'
import { ref } from 'vue'
import {
  MAX_OUTPUT_OVERWRITE_MODES,
  MAX_OUTPUT_OVERWRITE_MODE_HINTS,
  MAX_OUTPUT_PRESETS,
  REASONING_EFFORT_OPTIONS,
  REASONING_OVERWRITE_MODES,
  REASONING_OVERWRITE_MODE_HINTS,
  directionFromWheel,
  parseMaxOutputConfig,
  parseReasoningEffortConfig,
  serializeMaxOutputConfig,
  serializeReasoningEffortConfig,
  stepInSequence,
  type MaxOutputOverwriteMode,
  type OverwriteMode,
  type ReasoningOverwriteMode,
  type StepDirection,
} from '@/features/provider-config'

import ModeScopedField from './ModeScopedField.vue'
import SlidingValue from './SlidingValue.vue'
import { useWheelStep } from './useWheelStep'

/**
 * 数值栏位移动画的时长（毫秒）。
 *
 * 必须与 SCSS 里 `numeric-nudge-*` 的 animation-duration 一致 ——
 * 标记提前过期会把动画在中途打断。
 */
const NUDGE_MS = 180

type EditableModel = Record<string, any>

defineProps<{
    pullingModels: boolean
    compact?: boolean
}>()

const models = defineModel<EditableModel[]>('models', { required: true })

const emit = defineEmits<{
    (e: 'pull-models'): void
    (e: 'add-model'): void
    (e: 'remove-model', index: number): void
}>()

/**
 * 上下文预设。
 *
 * `value` 是字符串：这一栏直接双向绑定 `model.contextSize`，而那是字符串
 * （表单原样提交，没有 JSON 包装）。与最大输出的数字预设不同，见下方注释。
 */
const contextPresets = [
    { label: '1M', value: '1000000' },
    { label: '512K', value: '512000' },
    { label: '256K', value: '256000' },
    { label: '128K', value: '128000' },
    { label: '64K', value: '64000' },
]

/** 滚轮步进只关心数值，从展示用的预设里取一份数字清单。 */
const contextPresetValues = contextPresets.map(preset => Number(preset.value))

/**
 * 最大输出预设。
 *
 * `value` 是数字而非字符串（与上下文预设不同）：这一栏的值要进
 * `MaxOutputConfig.maxOutputTokens`，而那里是 number。清单定义在
 * `features/provider-config/maxOutput.ts`，与迁移的档位重映射表共用一套口径。
 */
const maxOutputPresets = MAX_OUTPUT_PRESETS.map(preset => ({ ...preset }))

/** 滚轮步进只关心数值，从展示用的预设里取一份数字清单。 */
const maxOutputPresetValues = MAX_OUTPUT_PRESETS.map(preset => preset.value)

const effortOptions = REASONING_EFFORT_OPTIONS.map(option => ({ label: option, value: option }))

/**
 * 思考深度配置的读写。
 *
 * 模型行里 `reasoningEffort` 存的是序列化后的 JSON，而界面要分别编辑「档位」与
 * 「注入模式」两个维度，所以每次读取都解一次、每次写入都序列化回去。
 * 不在组件里缓存解析结果：模型数组可能被拉取模型整体替换，缓存需要跟着失效，
 * 而这个解析是纯字符串操作，代价远低于维护一份同步状态。
 */
function effortConfigOf(model: EditableModel) {
    return parseReasoningEffortConfig(model.reasoningEffort)
}

function setEffort(model: EditableModel, effort: string) {
    model.reasoningEffort = serializeReasoningEffortConfig({ ...effortConfigOf(model), effort })
}

/**
 * 档位段最近一次滚动的方向，按模型下标分别记。
 *
 * 每行各自独立：在 A 行滚了档位，B 行的动画不该跟着有方向。
 *
 * <h2>为何不用 useWheelStep</h2>
 * 那个是给**数值**栏用的（预设按大小吸附，反馈是短暂的 CSS 位移）。
 * 档位是枚举而非数值，且它的值段是**只读按钮**，因此可以用
 * `SlidingValue` 做完整的进出过渡 —— 方向也因此可以长期存在，
 * 它描述的是「当前显示的这个值从哪个方向来」，而不是「刚刚滚了一下」。
 */
const effortDirections = ref<Record<number, StepDirection | null>>({})

/**
 * 两个数值栏的滚轮步进。
 *
 * 各自一份实例：预设清单不同（上下文到 1M，最大输出到 128K），
 * 而动画标记也必须分开 —— 否则在上下文栏滚动会让同一行的最大输出也抖一下。
 */
const contextWheel = useWheelStep(contextPresetValues, NUDGE_MS)
const maxOutputWheel = useWheelStep(maxOutputPresetValues, NUDGE_MS)

/**
 * 哪些模型卡片已展开第二层，按下标记。
 *
 * <h2>为何不存在 model 对象里</h2>
 * 展开与否是纯界面状态，而 `models` 里的对象会被提交给后端 ——
 * 挂在那上面会多一个需要在序列化时剔除的字段。
 *
 * <p>用对象而非 `Set`：模板里直接读 `expandedRows[index]`，
 * Vue 的深响应式会跟踪属性访问；`Set` 需要 `.has()`，模板里更啰唷。
 *
 * <p>模型列表被拉取整体替换时这份状态不跟着重置（下标复用）——
 * 那是可接受的：展开的是第几行而非哪个模型，与滚轮方向那两份状态同一口径。
 */
const expandedRows = ref<Record<number, boolean>>({})

function toggleRow(index: number) {
    expandedRows.value[index] = !expandedRows.value[index]
}

/**
 * 滚轮步进思考深度档位。
 *
 * 档位清单是升序（`Off → Max`），而方向是下标增量，因此向上滚
 * （`direction === -1`）会走到更低的档位。这与最大输出那一栏相反 ——
 * 那边预设按降序声明，向上滚得到更大的值。
 *
 * <p>三栏方向语义不一致会很别扭，所以这里把档位清单**反转**后再步进：
 * 反转后首项是 `Max`，向上滚（取前一项）因此走向更高的档位，与数值段一致。
 * `Off` 在升序清单里排首位，因此向下滚到底就是它 —— 不思考确实是强度最低的一档。
 */
function onEffortWheel(model: EditableModel, index: number, event: WheelEvent) {
    const direction = directionFromWheel(event.deltaY)
    if (direction === null) return
    const current = effortConfigOf(model).effort
    const next = stepInSequence(current, descendingEffortOptions, direction)
    if (next === current) return
    effortDirections.value[index] = direction
    setEffort(model, next)
}

/**
 * 档位清单的降序副本，供滚轮使用。
 *
 * `REASONING_EFFORT_OPTIONS` 是升序的（下拉菜单里从 Off 排到 Max，符合阅读习惯），
 * 但滚轮需要「向上滚 = 更高档位」，即清单前面是高档位。反转一份而非改原清单：
 * 下拉的展示顺序不该被滚轮的方向约定牵动。
 */
const descendingEffortOptions = [...REASONING_EFFORT_OPTIONS].reverse()

/** 滚轮步进最大输出上限。接线细节在 `useWheelStep`。 */
function onMaxOutputWheel(model: EditableModel, index: number, event: WheelEvent) {
    maxOutputWheel.onWheel(
        index,
        event,
        () => maxOutputConfigOf(model).maxOutputTokens,
        next => setMaxOutputTokens(model, String(next)),
    )
}

/**
 * 滚轮步进上下文窗口。
 *
 * 这一栏直接绑定 `model.contextSize`（字符串），没有 JSON 包装也没有注入模式 ——
 * 它只向 Ollama 发现接口上报能力，不进上游请求体，因此不需要「下游带了怎么办」那一维。
 * 也正因为没有模式，它不套 `ModeScopedField`，仅复用滚轮行为。
 *
 * <p>手填的非标值（如 650000）同样按方向吸附到最近预设。
 */
function onContextWheel(model: EditableModel, index: number, event: WheelEvent) {
    contextWheel.onWheel(
        index,
        event,
        // 解析失败当作 0：那样向上滚会进入清单最小档，而不是原地不动。
        () => Number.parseInt(String(model.contextSize ?? '').trim(), 10) || 0,
        next => { model.contextSize = String(next) },
    )
}

/**
 * 写入注入模式。
 *
 * 壳层已经算好了下一档（轮转顺序由传给它的模式清单决定），这里只管落库形态。
 * 断言回 `ReasoningOverwriteMode`：壳层的模型是全集类型，而传进去的清单就是这个
 * 字段的子集，取出来的值必然在子集内。
 */
function setEffortMode(model: EditableModel, mode: OverwriteMode) {
    model.reasoningEffort = serializeReasoningEffortConfig({
        ...effortConfigOf(model),
        mode: mode as ReasoningOverwriteMode,
    })
}

/**
 * 最大输出配置的读写，与思考深度同构。
 *
 * 模型行里 `maxOutputTokens` 存的是序列化后的 V9 JSON，界面要分别编辑「token 上限」
 * 与「注入模式」，所以每次读取都解一次、写入都序列化回去。
 */
function maxOutputConfigOf(model: EditableModel) {
    return parseMaxOutputConfig(model.maxOutputTokens)
}

/**
 * 写入 token 上限。
 *
 * 入参是字符串（输入框绑定），交给 `parseMaxOutputConfig` 做归一化 ——
 * 空值、0、负数都会落到默认值，而非写进一个列约束不接受的形态。
 */
function setMaxOutputTokens(model: EditableModel, raw: string) {
    const current = maxOutputConfigOf(model)
    model.maxOutputTokens = serializeMaxOutputConfig({
        ...current,
        maxOutputTokens: parseMaxOutputConfig(raw).maxOutputTokens,
    })
}

/**
 * 手填或点击预设时清掉滚动方向。
 *
 * 这两种操作没有方向语义。不清的话，上次滚动留下的方向会让新值朝那一边滑进来 ——
 * 用户明明是打字，画面却像是滚了一下。
 */
function clearMaxOutputDirection(index: number) {
    maxOutputWheel.clear(index)
}

function clearEffortDirection(index: number) {
    effortDirections.value[index] = null
}

/** 写入注入模式。与思考深度同构，只是这个字段的清单只有两档。 */
function setMaxOutputMode(model: EditableModel, mode: OverwriteMode) {
    model.maxOutputTokens = serializeMaxOutputConfig({
        ...maxOutputConfigOf(model),
        mode: mode as MaxOutputOverwriteMode,
    })
}
</script>

<template>
    <section class="provider-models">
        <div class="field-label-row">
            <label class="field-label">模型列表</label>
            <div class="field-label-actions">
                <n-button text size="tiny" :loading="pullingModels" @click="emit('pull-models')"
                    class="pull-models-btn">
                    <template #icon>
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                            stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M12 3v12" />
                            <path d="m7 10 5 5 5-5" />
                            <path d="M5 21h14" />
                        </svg>
                    </template>
                    拉取模型
                </n-button>
                <n-button text size="tiny" @click="emit('add-model')" class="add-model-btn">
                    <template #icon>
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                            stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <line x1="12" y1="5" x2="12" y2="19" />
                            <line x1="5" y1="12" x2="19" y2="12" />
                        </svg>
                    </template>
                    添加模型
                </n-button>
            </div>
        </div>

        <div v-for="(model, index) in models" :key="index" class="model-card">
            <div class="model-card-inner">
                <div class="model-checkbox-col">
                    <n-checkbox v-model:checked="model.enabled" />
                </div>
                <div class="model-divider"></div>
                <div class="model-content-col">
                    <n-form :model="model" label-placement="left" :show-feedback="false" size="small">
                        <!--
                            中部按「能力归属」分成两组，而非按控件类型堆在一行：
                            上下文与思考深度直接影响对上游的请求，最大输出 / 工具 / 视觉
                            则只是向 Ollama 发现接口（/api/tags）声明能力，两者的语义完全不同。
                            上下文虽然同样只在发现接口上报，但它是最常调整的一项，归入基础组。
                        -->
                        <div class="model-capability-groups"
                            :class="{ 'model-capability-groups--compact': compact }">
                            <div class="model-capability-group model-capability-group--basic">
                                <div class="model-form-row">
                                    <n-form-item label="模型id" class="model-name-item">
                                        <n-input v-model:value="model.modelName" placeholder="模型名称" />
                                    </n-form-item>
                                </div>
                                <div class="model-form-row model-form-row--details">
                                    <n-form-item label="上下文"
                                        class="model-detail-item model-detail-item--context model-detail-item--numeric">
                                        <!--
                                            这一栏是裸 n-input，没有套 ModeScopedField ——
                                            上下文只向发现接口上报能力、不进上游请求体，因此没有注入模式
                                            那一维。滚轮步进与位移动画是与模式无关的能力，两者共用
                                            useWheelStep，但 DOM 结构各自保持最简形态。
                                        -->
                                        <n-input :value="model.contextSize" placeholder="4096"
                                            class="numeric-nudge" :class="{
                                                'numeric-nudge--from-below': contextWheel.nudges.value[index] === 1,
                                                'numeric-nudge--from-above': contextWheel.nudges.value[index] === -1,
                                            }" @wheel.prevent="onContextWheel(model, index, $event)"
                                            @update:value="(value: string) => {
                                                contextWheel.clear(index)
                                                model.contextSize = value
                                            }">
                                            <template #suffix>
                                                <n-popselect :options="contextPresets" size="small" trigger="click"
                                                    :value="model.contextSize"
                                                    @update:value="(value: string) => {
                                                        contextWheel.clear(index)
                                                        model.contextSize = value
                                                    }">
                                                    <span class="context-preset-trigger">
                                                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
                                                            stroke="currentColor" stroke-width="2"
                                                            stroke-linecap="round" stroke-linejoin="round">
                                                            <polyline points="6 9 12 15 18 9" />
                                                        </svg>
                                                    </span>
                                                </n-popselect>
                                            </template>
                                        </n-input>
                                    </n-form-item>
                                    <n-form-item class="model-effort-item">
                                        <!--
                                            档位是枚举，所以值区用 popselect；最大输出那一栏的值要能手填，
                                            用的是 n-input。两者的「模式 | 值」外壳是同一个组件。
                                        -->
                                        <mode-scoped-field :modes="REASONING_OVERWRITE_MODES"
                                            :hints="REASONING_OVERWRITE_MODE_HINTS"
                                            :mode="effortConfigOf(model).mode"
                                            @update:mode="(value: OverwriteMode) => setEffortMode(model, value)">
                                            <n-popselect :options="effortOptions" size="small" trigger="click"
                                                :value="effortConfigOf(model).effort"
                                                @update:value="(value: string) => {
                                                    clearEffortDirection(index)
                                                    setEffort(model, value)
                                                }">
                                                <!--
                                                    滚轮绑在这个按钮上而非整个控件：值段与模式段各自响应
                                                    自己位置上的滚动，鼠标在哪一段就推哪一段。
                                                -->
                                                <button type="button" class="effort-value"
                                                    @wheel.prevent="onEffortWheel(model, index, $event)">
                                                    <sliding-value class="effort-value__text"
                                                        :value="effortConfigOf(model).effort"
                                                        :direction="effortDirections[index]" />
                                                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none"
                                                        stroke="currentColor" stroke-width="2" stroke-linecap="round"
                                                        stroke-linejoin="round" aria-hidden="true">
                                                        <polyline points="6 9 12 15 18 9" />
                                                    </svg>
                                                </button>
                                            </n-popselect>
                                        </mode-scoped-field>
                                    </n-form-item>
                                </div>
                            </div>
                            <div class="model-capability-divider"></div>
                            <div class="model-capability-group model-capability-group--ollama">
                                <div class="model-form-row">
                                    <n-form-item label="最大输出"
                                        class="model-detail-item model-detail-item--half model-detail-item--numeric">
                                        <!--
                                            值区保留可手填的输入框而非只给下拉：上游文档里的 4096、8192
                                            那类二进制值不在预设中，而迁移把它们当作「非标值」保留，
                                            界面也得能填进去。预设收在后缀的下拉里。
                                        -->
                                        <mode-scoped-field :modes="MAX_OUTPUT_OVERWRITE_MODES"
                                            :hints="MAX_OUTPUT_OVERWRITE_MODE_HINTS"
                                            :mode="maxOutputConfigOf(model).mode"
                                            @update:mode="(value: OverwriteMode) => setMaxOutputMode(model, value)">
                                            <!--
                                                值段是可编辑输入框，套不了滑动动画（那会遮住光标与选区），
                                                所以只接滚轮步进。手填时清掉方向，见 setMaxOutputTokens 旁的注释。
                                            -->
                                            <n-input :value="String(maxOutputConfigOf(model).maxOutputTokens)"
                                                placeholder="4000" class="numeric-nudge"
                                                :class="{
                                                    'numeric-nudge--from-below': maxOutputWheel.nudges.value[index] === 1,
                                                    'numeric-nudge--from-above': maxOutputWheel.nudges.value[index] === -1,
                                                }"
                                                @wheel.prevent="onMaxOutputWheel(model, index, $event)"
                                                @update:value="(value: string) => {
                                                    clearMaxOutputDirection(index)
                                                    setMaxOutputTokens(model, value)
                                                }">
                                                <template #suffix>
                                                    <n-popselect :options="maxOutputPresets" size="small"
                                                        trigger="click"
                                                        :value="maxOutputConfigOf(model).maxOutputTokens"
                                                        @update:value="(value: number) => {
                                                            clearMaxOutputDirection(index)
                                                            setMaxOutputTokens(model, String(value))
                                                        }">
                                                        <span class="context-preset-trigger">
                                                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
                                                                stroke="currentColor" stroke-width="2"
                                                                stroke-linecap="round" stroke-linejoin="round">
                                                                <polyline points="6 9 12 15 18 9" />
                                                            </svg>
                                                        </span>
                                                    </n-popselect>
                                                </template>
                                            </n-input>
                                        </mode-scoped-field>
                                    </n-form-item>
                                </div>
                                <div class="model-form-row model-form-row--details">
                                    <n-form-item label="工具" class="model-detail-item model-detail-item--switch">
                                        <n-switch v-model:value="model.capsTools" size="small" />
                                    </n-form-item>
                                    <n-form-item label="视觉" class="model-detail-item model-detail-item--switch">
                                        <n-switch v-model:value="model.capsVision" size="small" />
                                    </n-form-item>
                                </div>
                            </div>
                        </div>
                    </n-form>

                    <!--
                        第二层在中间列内部，与主体上下相邻 —— 左右两侧的复选框与操作按钮
                        因此能被拉伸后的卡片高度撑满（它们是 model-card-inner 的 flex 子项，
                        默认 align-items: stretch）。

                        默认收纳，靠 grid-template-rows 从 0fr 过渡到 1fr 展开；
                        元素始终在文档流里（不用 v-if / v-show），否则过渡起点会丢失。
                        见样式里对「为何不用 height」的说明。
                    -->
                    <div class="model-card-secondary"
                        :class="{ 'model-card-secondary--open': expandedRows[index] }">
                        <div class="model-card-secondary__inner">
                            <div class="model-secondary-divider"></div>
                            <div class="model-secondary-placeholder">
                                更多配置（待实现）
                            </div>
                        </div>
                    </div>
                </div>
                <div class="model-divider"></div>
                <div class="model-action-col">
                    <n-button tertiary circle size="small" @click="emit('remove-model', index)" class="model-remove-btn"
                        title="删除此模型">
                        <template #icon>
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                                <polyline points="3 6 5 6 21 6" />
                                <path
                                    d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2" />
                            </svg>
                        </template>
                    </n-button>
                    <!--
                        展开第二层。图标是静态的，靠 CSS 旋转表达状态 ——
                        换图标会让过渡无从下手（两个不同的 path 无法插值）。
                    -->
                    <n-button tertiary circle size="small" class="model-expand-btn"
                        :class="{ 'model-expand-btn--open': expandedRows[index] }"
                        :title="expandedRows[index] ? '收起更多配置' : '展开更多配置'"
                        :aria-expanded="!!expandedRows[index]" @click="toggleRow(index)">
                        <template #icon>
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                                stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
                                <polyline points="6 9 12 15 18 9" />
                            </svg>
                        </template>
                    </n-button>
                </div>
            </div>
        </div>
    </section>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.provider-models {
    margin-bottom: $space-md;
}

.field-label-row {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 6px;
}

.field-label-actions {
    display: flex;
    align-items: center;
    gap: $space-xs;
}

.field-label {
    display: block;
    font-family: $font-mono;
    font-size: 11px;
    font-weight: 500;
    letter-spacing: 0.15em;
    text-transform: uppercase;
    color: $text-muted;
}

.model-card {
    background: $bg;
    border: 1px solid $border;
    border-radius: $radius;
    padding: $space-sm;
    margin-bottom: $space-sm;
}

.model-card-inner {
    display: flex;
    gap: $space-sm;
}

/**
 * 卡片第二层，默认收纳。
 *
 * <h2>为何用 grid-template-rows 而不是 height</h2>
 * `height: auto` 不是一个可插值的值，所以 `transition: height` 在
 * `0 → auto` 之间不会播放任何动画 —— 内容会直接跳出来。
 *
 * <p>常见的绕法是写死一个 `max-height`，但那要求预先知道内容高度：写小了会截断、
 * 写大了收起时前半段过渡是空跑（从一个不存在的高度开始降），手感变成「延迟一下才动」。
 * 第二层将来要放多少配置项还不确定，写死高度必然会踩这个坑。
 *
 * <p>`grid-template-rows: 0fr → 1fr` 没有这个问题：`fr` 是可插值的，而 `1fr`
 * 由内容自然高度决定。代价是需要一层额外的 `__inner` 承担 `overflow: hidden`
 * （grid 轨道本身不裁剪内容）。
 *
 * <p>不用 `v-show`：那会在展开瞬间把 `display` 从 `none` 切回来，过渡起点丢失。
 * 收纳态靠 `0fr` + `overflow: hidden` 实现，元素始终在文档流里。
 */
.model-card-secondary {
    display: grid;
    grid-template-rows: 0fr;
    transition: grid-template-rows 0.25s cubic-bezier(0.4, 0, 0.2, 1);

    &--open {
        grid-template-rows: 1fr;
    }
}

/**
 * 裁剪层。
 *
 * `min-height: 0` 是必需的：grid 子项的默认 `min-height: auto` 会以内容高度为下界，
 * 那样 `0fr` 压根压不下去 —— 收纳态会露出全部内容。
 */
.model-card-secondary__inner {
    min-height: 0;
    overflow: hidden;
}

/**
 * 一层与二层之间的视觉分割线。
 *
 * 放在裁剪层内部而非外层：外层在收纳态必须能压到 0，
 * 任何在那上面的边框或间距都会留下一条永久可见的缝。
 * 写在这里它会跟着内容一起被裁掉，展开时才浮现。
 *
 * <p>用 $border-light 与左右两条 model-divider 保持同一视觉层级：它们都是
 * 同一张卡片内的分隔，而非卡片边界。横线面积大但高度只有 1px，
 * 即使比 $border 浅也足够作为层次提示，不会把两层看成两张独立卡片。
 */
.model-secondary-divider {
    height: 1px;
    margin: $space-sm 0;
    background: $border-light;
}

/** 占位容器，等真实配置项进来后替换。 */
.model-secondary-placeholder {
    padding: $space-sm;
    border: 1px dashed $border;
    border-radius: $radius;
    color: $text-muted;
    font-size: 13px;
    text-align: center;
}

/**
 * 左侧启用复选框列。
 *
 * 第二层展开后本列会被拉伸（flex 默认 align-items: stretch），
 * 复选框作为整张模型卡片的启用开关，应当在拉伸后的高度里垂直居中 ——
 * 它不属于某一层，而是作用于两层整体。
 */
.model-checkbox-col {
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
    width: 20px;
}

.model-divider {
    width: 1px;
    flex-shrink: 0;
    background: $border-light;
    align-self: stretch;
    margin: 2px 0;
}

.model-content-col {
    flex: 1;
    min-width: 0;
}

/**
 * 右侧操作列：删除 + 展开第二层。
 *
 * 本列会被第二层拉伸（flex 默认 align-items: stretch），两个按钮作为整张卡片的
 * 操作（删除、展开），应当在拉伸后的完整高度里垂直居中。
 *
 * <p>竖排按钮自身的 4px 间距不变；只把列的主轴分配从 `flex-start` 改成 `center`，
 * 因而收纳态仍然自然居中、展开态也不会偏在第一层顶部。
 */
.model-action-col {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 4px;
    flex-shrink: 0;
    width: 28px;
}

/**
 * 中部的两个能力分组：基础能力与 Ollama 能力，宽度 2:1。
 *
 * 用 flex 而非 grid：两组之间还夹了一条分隔线，grid 需要为它单独占一列，
 * 而 `2fr auto 1fr` 会让「2:1」这个比例在分隔线宽度上打折扣。
 */
.model-capability-groups {
    display: flex;
    align-items: stretch;
    gap: $space-sm;
}

.model-capability-group {
    min-width: 0;
    display: flex;
    flex-direction: column;
    justify-content: center;

    &--basic {
        flex: 2 1 0;
    }

    &--ollama {
        flex: 1 1 0;
    }
}

/** 两组之间的竖线，与卡片外层的 `.model-divider` 同一视觉语言。 */
.model-capability-divider {
    width: 1px;
    flex-shrink: 0;
    background: $border-light;
    align-self: stretch;
    margin: 2px 0;
}

.model-form-row {
    display: flex;
    align-items: center;
    gap: $space-sm;

    &--details {
        margin-top: 2px;
    }
}

.model-name-item {
    flex: 1;
    margin-bottom: 0 !important;

    :deep(.n-form-item-label) {
        font-family: $font-mono;
        font-size: 10px;
        font-weight: 500;
        letter-spacing: 0.1em;
        text-transform: uppercase;
        color: $text-muted;
        width: 52px;
        flex-shrink: 0;
        padding-right: 8px;
        text-align: left;
    }

    :deep(.n-form-item-blank) {
        flex: 1;
    }
}

.model-detail-item {
    margin-bottom: 0 !important;

    :deep(.n-form-item-label) {
        font-family: $font-mono;
        font-size: 10px;
        font-weight: 500;
        letter-spacing: 0.1em;
        text-transform: uppercase;
        color: $text-muted;
        width: 52px;
        flex-shrink: 0;
        padding-right: 6px;
        text-align: left;
    }

    :deep(.n-form-item-blank) {
        flex: 1;
    }

    &--grow {
        flex: 1;
        min-width: 0;
    }

    &--half {
        flex: 1;
        min-width: 0;
    }

    // 与同行的思考深度成 3:2。上下文是五位数字，占大头才不会被截断。
    &--context {
        flex: 3 1 0;
        min-width: 0;
    }

    /**
     * 纯数值输入框的数字视觉居中补偿。
     *
     * <h2>为何需要补偿，以及为何是 1px</h2>
     * 盒模型本来就是居中的（`line-height` 等于 28px 的容器高），偏下来自字体度量：
     * 正文字体 Crimson Pro 在 14px 下，数字的 `actualBoundingBoxAscent` 为 9px、
     * `actualBoundingBoxDescent` 为 1px —— 有一截墨迹落在 baseline 之下，
     * 于是墨迹中心比盒中心低 1px。
     *
     * <p>这个偏移对数字是<strong>恒定</strong>的：`4000`、`4096`、`650000` 实测都是
     * 9/1，与位数无关（阿拉伯数字在该字体里既无升部也无真正的降部）。所以能用一个
     * 固定的 1px 上移抵消，不需要换字体 —— 上一版换 mono 是绕过问题而非解决它。
     *
     * <p>用 `transform` 而不是 `padding` / `top`：`padding-bottom` 会挤压 content box
     * 与 28px 的 `line-height` 打架，`position` 要额外声明定位上下文。`transform` 不参与
     * 布局，光标也跟着一起移动，视觉保持一致。
     *
     * <p>占位符不用单独补：这两栏是单行 `n-input`，Naive UI 直接用 input 的原生
     * `placeholder` 属性，跟着同一个 `transform` 走。（只有 textarea 与 pair 模式才会
     * 渲染独立的 `.n-input__placeholder` 元素，那时才需要一起补。）
     *
     * <p>补偿只给数值栏。文本输入框里的 `g`、`y` 有真正的降部，墨迹本就该压在 baseline 下，
     * 强行上移反而错。
     */
    &--numeric :deep(.n-input__input-el) {
        transform: translateY(-1px);
        font-variant-numeric: tabular-nums;
    }

    &--switch {
        flex-shrink: 0;

        :deep(.n-form-item-label) {
            width: auto;
            padding-right: 4px;
        }

        :deep(.n-form-item-blank) {
            flex: 0;
        }
    }
}

/**
 * 思考深度占基础组首行的 2/5（上下文占 3/5）。
 *
 * 基准取 `0` 而非 `auto`：档位名长度不一（`Medium` 比 `Max` 宽），
 * 若以内容宽为基准，换一个档位两个控件的宽度就会跳变。
 */
.model-effort-item {
    flex: 2 1 0;
    min-width: 0;
    margin-bottom: 0 !important;

    :deep(.n-form-item-blank) {
        flex: 1;
        min-width: 0;
    }
}

/**
 * 思考深度的值区（`ModeScopedField` 插槽里的那个按钮）。
 *
 * 自己画一个按钮而非用 n-select：n-select 自带边框与高度，而这两样由外壳提供，
 * 抹掉它的框比画一个按钮更费事；这里要的只是「文案 + 箭头」。
 *
 * <p>占满剩余宽度并把箭头推到右缘，与最大输出那一栏 n-input 的后缀图标对齐。
 * 颜色继承外壳 —— inert 态的变灰因此自动生效。
 */
.effort-value {
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 4px;
    flex: 1;
    min-width: 0;
    height: 100%;
    padding: 0;
    border: none;
    background: transparent;
    color: inherit;
    font-family: inherit;
    font-size: inherit;
    cursor: pointer;
    transition: color 0.15s ease;

    &:hover {
        color: $accent;
    }

    svg {
        flex-shrink: 0;
    }
}

/**
 * 档位名一旦变长就截断，而不是把箭头挤出控件。
 *
 * `SlidingValue` 内部是 `inline-grid`，`min-width: 0` 让这里的截断能穿透到它 ——
 * 缺了它 grid 项以内容宽为下界，长档位名会把箭头顶出去。
 */
.effort-value__text {
    min-width: 0;
    overflow: hidden;
    white-space: nowrap;
}

/**
 * 数值输入框的滚轮位移反馈，上下文与最大输出共用。
 *
 * <h2>为何是 animation 而不是 transition</h2>
 * 这是一次**一次性的往复运动**（推一下、落定），而不是两个状态之间的过渡。
 * 用 transition 需要「设值 → 等一帧 → 清值」两次状态变更才能回弹，
 * keyframes 一条声明就够。
 *
 * <h2>为何位移量这么小</h2>
 * 几个像素只是一个「被推了一下」的暗示。这里的值是用户可能正在编辑的内容，
 * 大幅位移会让人以为控件出了问题。档位段那种整行滑过（`SlidingValue`）
 * 在这里不适用 —— 输入框套过渡会让光标与选区错乱。
 *
 * <p>时长与脚本里的 `NUDGE_MS` 必须一致，否则标记会在动画播完前摘掉。
 */
.numeric-nudge {

    // 向下滚（下标增量 +1，取更小的值）：新值从下方进入，
    // 与「列表往下走，内容整体上移」一致。
    &--from-below :deep(.n-input__input-el) {
        animation: numeric-nudge-from-below 0.18s ease-out;
    }

    &--from-above :deep(.n-input__input-el) {
        animation: numeric-nudge-from-above 0.18s ease-out;
    }
}

/**
 * 终点是 `-1px` 而非 `0`：那是 `--numeric` 的基线补偿量（见那块注释）。
 * 写 `0` 会让动画结束的那一帧跳回补偿后的位置，看起来像多抖了一下。
 */
@keyframes numeric-nudge-from-below {
    from {
        transform: translateY(3px);
        opacity: 0.35;
    }

    to {
        transform: translateY(-1px);
        opacity: 1;
    }
}

@keyframes numeric-nudge-from-above {
    from {
        transform: translateY(-5px);
        opacity: 0.35;
    }

    to {
        transform: translateY(-1px);
        opacity: 1;
    }
}

/**
 * 尊重系统的「减少动态效果」偏好 —— 这些动画纯装饰，关掉不影响功能。
 *
 * <p>第二层的展开只去掉过渡，不去掉 `grid-template-rows` 本身 ——
 * 那是收纳态成立的机制，去掉会让第二层永远展开。
 */
@media (prefers-reduced-motion: reduce) {

    .numeric-nudge--from-below :deep(.n-input__input-el),
    .numeric-nudge--from-above :deep(.n-input__input-el) {
        animation: none;
    }

    .model-card-secondary,
    .model-expand-btn :deep(svg) {
        transition: none;
    }
}


/**
 * Ollama 组的标签比基础组窄。
 *
 * 「最大输出」四个字在 52px 内会被挤压换行，而这一组只占中部三分之一宽度，
 * 输入框本就吃紧；标签放宽到 60px 并允许不换行，代价是与左侧的标签列不再严格
 * 对齐 —— 但两组之间有分隔线，视觉上本就是两个独立的对齐域。
 */
.model-capability-group--ollama {
    .model-detail-item :deep(.n-form-item-label) {
        width: 60px;
        white-space: nowrap;
    }

    /**
     * 工具 / 视觉两个开关平分该组横向空间，并各自在自己那一半里居中。
     *
     * `flex: 1 1 0` 让两项等分（`0` 基准而非 `auto`，否则「工具」与「视觉」
     * 字宽相同但控件内边距的细微差异会让两半不等）。居中靠 `justify-content`
     * 作用在 n-form-item 自身 —— 它在 left-labelled 模式下就是个 flex 容器，
     * 内部是「标签 + 控件」两个子项，因此这里居中的是这一对整体，而不是分别居中。
     *
     * 标签宽度必须回到 `auto`：这一组的其他项被固定成 60px，若开关也继承那个宽度，
     * 「工具」二字右侧会多出一段空白，看起来像没对齐而不是居中。
     */
    /**
     * 工具 / 视觉两个开关平分该组横向空间，并各自在自己那一半里居中。
     *
     * `flex: 1 1 0` 让两项等分（基准取 `0` 而非 `auto`，否则控件内边距的细微差异
     * 会让两半不等宽）。
     *
     * <p>居中不能靠在 n-form-item 上写 `justify-content` —— 实测它在 left-labelled
     * 模式下是 `display: grid`（标签列 + 控件列），那个属性作用于轨道而非内容，
     * 开关仍会贴在控件列的左边缘，右侧凭空留出 25px 空白。
     * 真正起作用的是把「标签 + 开关」这一对整体居中：让 grid 的两列各自收缩到内容宽度
     * （`grid-template-columns: auto auto` + `justify-content: center`），
     * 再由 blank 内部的 flex 居中兜住开关本身。
     */
    .model-detail-item--switch {
        flex: 1 1 0;
        min-width: 0;

        :deep(.n-form-item) {
            justify-content: center;
        }

        // 两列都收缩到内容宽度，否则控件列会吃掉剩余空间、居中无从体现。
        grid-template-columns: auto auto;
        justify-content: center;

        :deep(.n-form-item-label) {
            width: auto;
            // 默认 `0 12px 0 0` 的右内边距会把这一对整体推向左侧；6px 只留必要间隙。
            padding-right: 6px !important;
        }

        :deep(.n-form-item-blank) {
            flex: 0 0 auto;
            justify-content: center;
        }
    }
}

.model-remove-btn {
    flex-shrink: 0;
    color: $text-muted !important;

    &:hover {
        color: $danger !important;
    }
}

/**
 * 展开第二层的按钮。
 *
 * 图标是静态的箭头，靠旋转表达开合状态 —— 换成两个不同的图标会让过渡无从下手
 * （两条不同的 path 无法插值），而旋转是连续的。
 *
 * <p>hover 用 accent 而非 danger：它与删除按钮同列，颜色是唯一能一眼区分
 * 「安全操作」与「破坏性操作」的线索。
 */
.model-expand-btn {
    flex-shrink: 0;
    color: $text-muted !important;

    &:hover {
        color: $accent !important;
    }

    // 旋转作用在 svg 上而非按钮本身：按钮带 Naive UI 的波纹与背景，
    // 整体旋转会让那个圆形背景也跟着转（视觉上看不出，但 hover 区域会歪）。
    :deep(svg) {
        transition: transform 0.25s cubic-bezier(0.4, 0, 0.2, 1);
    }

    &--open :deep(svg) {
        transform: rotate(180deg);
    }
}

.add-model-btn,
.pull-models-btn,
.docs-window-btn {
    flex-shrink: 0;
    color: $text-muted !important;

    &:hover {
        color: $accent !important;
    }
}

.context-preset-trigger {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 18px;
    height: 18px;
    cursor: pointer;
    color: $text-muted;
    border-radius: 4px;
    transition: all 0.2s ease;

    &:hover {
        color: $accent;
        background: $accent-light;
    }
}

@media (max-width: 768px) {
    .model-form-row--details {
        flex-wrap: wrap;

        .model-detail-item--grow {
            flex: 1 1 100%;
            min-width: 0;
        }
    }
}

/**
 * 紧凑模式（抽屉被窗口宽度压到最小时）：两组改为竖排。
 *
 * 2:1 的横向分栏在窄屏下会让 Ollama 组只剩几十像素，最大输出输入框基本不可用。
 * 竖排后分隔线也要从竖线转成横线，否则它会变成一条贴着左边的孤立短线。
 */
.model-capability-groups--compact {
    flex-direction: column;
    align-items: stretch;

    .model-capability-group--basic,
    .model-capability-group--ollama {
        flex: 1 1 auto;
    }

    .model-capability-divider {
        width: auto;
        height: 1px;
        margin: 0;
    }
}
</style>