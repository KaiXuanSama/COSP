<script setup lang="ts">
import { NButton, NCheckbox, NForm, NFormItem, NInput, NPopselect, NSwitch } from 'naive-ui'
import {
  MAX_OUTPUT_OVERWRITE_MODE_HINTS,
  MAX_OUTPUT_OVERWRITE_MODE_LABELS,
  MAX_OUTPUT_PRESETS,
  REASONING_EFFORT_OPTIONS,
  REASONING_OVERWRITE_MODE_HINTS,
  REASONING_OVERWRITE_MODE_LABELS,
  nextMaxOutputOverwriteMode,
  nextReasoningOverwriteMode,
  parseMaxOutputConfig,
  parseReasoningEffortConfig,
  serializeMaxOutputConfig,
  serializeReasoningEffortConfig,
} from '@/features/provider-config'

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

const contextPresets = [
    { label: '1M', value: '1000000' },
    { label: '512K', value: '512000' },
    { label: '256K', value: '256000' },
    { label: '128K', value: '128000' },
    { label: '64K', value: '64000' },
]

/**
 * 最大输出预设。
 *
 * `value` 是数字而非字符串（与上下文预设不同）：这一栏的值要进
 * `MaxOutputConfig.maxOutputTokens`，而那里是 number。清单定义在
 * `features/provider-config/maxOutput.ts`，与迁移的档位重映射表共用一套口径。
 */
const maxOutputPresets = MAX_OUTPUT_PRESETS.map(preset => ({ ...preset }))

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

/** 轮转注入模式。三个模式循环切换，无需展开二级菜单。 */
function cycleOverwriteMode(model: EditableModel) {
    const current = effortConfigOf(model)
    model.reasoningEffort = serializeReasoningEffortConfig({
        ...current,
        mode: nextReasoningOverwriteMode(current.mode),
    })
}

function overwriteModeLabel(model: EditableModel) {
    return REASONING_OVERWRITE_MODE_LABELS[effortConfigOf(model).mode]
}

function overwriteModeHint(model: EditableModel) {
    return REASONING_OVERWRITE_MODE_HINTS[effortConfigOf(model).mode]
}

/**
 * 档位是否真的会被发往上游。
 *
 * 透传与删除两档都<strong>不使用</strong>此处配置的档位：前者完全不干预、
 * 后者直接剔除字段，档位只作为界面上的记忆值存在。触发器据此降低存在感，
 * 否则一个永不生效的档位会与已生效的配置长得一模一样。
 */
function effortIsInert(model: EditableModel) {
    const mode = effortConfigOf(model).mode
    return mode === 'passthrough' || mode === 'delete'
}

/**
 * 触发器上显示的档位。
 *
 * 只给档位、不拼模式：模式已由左侧的前缀标签外显，拼进来会重复一遍。
 * 折叠状态下「模式 | 档位」两段各司其职，与最大输出那一栏的读法一致。
 */
function effortTriggerLabel(model: EditableModel) {
    return effortConfigOf(model).effort
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

/** 轮转注入模式。只有两档，点一次就切换。 */
function cycleMaxOutputMode(model: EditableModel) {
    const current = maxOutputConfigOf(model)
    model.maxOutputTokens = serializeMaxOutputConfig({
        ...current,
        mode: nextMaxOutputOverwriteMode(current.mode),
    })
}

function maxOutputModeLabel(model: EditableModel) {
    return MAX_OUTPUT_OVERWRITE_MODE_LABELS[maxOutputConfigOf(model).mode]
}

function maxOutputModeHint(model: EditableModel) {
    return MAX_OUTPUT_OVERWRITE_MODE_HINTS[maxOutputConfigOf(model).mode]
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
                                        <n-input v-model:value="model.contextSize" placeholder="4096">
                                            <template #suffix>
                                                <n-popselect :options="contextPresets" size="small" trigger="click"
                                                    @update:value="(value: string) => model.contextSize = value">
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
                                            模式做成触发器内的前缀标签，与最大输出同构：模式与档位不是
                                            同一维度的选择，混进选项列表会让用户以为「覆写」和「Medium」
                                            是互斥的同类项；而放在下拉的 header 里则折叠状态看不见。
                                            标签在最左、竖线分隔、档位居右 —— 两栏的读法完全一致。
                                        -->
                                        <div class="effort-trigger"
                                            :class="{ 'effort-trigger--muted': effortIsInert(model) }">
                                            <button type="button" class="inline-mode-toggle"
                                                :title="overwriteModeHint(model)"
                                                @click="cycleOverwriteMode(model)">
                                                {{ overwriteModeLabel(model) }}
                                            </button>
                                            <n-popselect :options="effortOptions" size="small" trigger="click"
                                                :value="effortConfigOf(model).effort"
                                                @update:value="(value: string) => setEffort(model, value)">
                                                <button type="button" class="effort-trigger__value"
                                                    :title="overwriteModeHint(model)">
                                                    <span class="effort-trigger__text">{{ effortTriggerLabel(model) }}</span>
                                                    <svg width="12" height="12" viewBox="0 0 24 24" fill="none"
                                                        stroke="currentColor" stroke-width="2" stroke-linecap="round"
                                                        stroke-linejoin="round" aria-hidden="true">
                                                        <polyline points="6 9 12 15 18 9" />
                                                    </svg>
                                                </button>
                                            </n-popselect>
                                        </div>
                                    </n-form-item>
                                </div>
                            </div>
                            <div class="model-capability-divider"></div>
                            <div class="model-capability-group model-capability-group--ollama">
                                <div class="model-form-row">
                                    <n-form-item label="最大输出"
                                        class="model-detail-item model-detail-item--half model-detail-item--numeric">
                                        <!--
                                            输入框直接编辑 token 数，模式藏在预设菜单的 header 里 ——
                                            与思考深度同构。这里保留可手填的输入框而非只给下拉：
                                            上游文档里的 4096、8192 那类二进制值不在预设中，
                                            而迁移把它们当作「非标值」保留，界面也得能填进去。
                                        -->
                                        <n-input :value="String(maxOutputConfigOf(model).maxOutputTokens)"
                                            placeholder="4000"
                                            @update:value="(value: string) => setMaxOutputTokens(model, value)">
                                            <!--
                                                模式放在输入框前缀而非预设菜单里：它决定这个值到底会不会
                                                发往上游，折叠状态下必须可见。与思考深度的「兜底: Max」同理，
                                                只是这一栏的值要能手填，所以模式只能挂在输入框旁边。
                                            -->
                                            <template #prefix>
                                                <button type="button" class="inline-mode-toggle"
                                                    :title="maxOutputModeHint(model)"
                                                    @click="cycleMaxOutputMode(model)">
                                                    {{ maxOutputModeLabel(model) }}
                                                </button>
                                            </template>
                                            <template #suffix>
                                                <n-popselect :options="maxOutputPresets" size="small" trigger="click"
                                                    :value="maxOutputConfigOf(model).maxOutputTokens"
                                                    @update:value="(value: number) => setMaxOutputTokens(model, String(value))">
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
                </div>
                <div class="model-divider"></div>
                <div class="model-remove-col">
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

.model-remove-col {
    display: flex;
    align-items: center;
    justify-content: center;
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
 * 思考深度触发器。
 *
 * 自己画一个按钮而非用 n-select：显示文案是「模式: 档位」两个维度拼出来的，
 * 而 n-select 的显示值与 `value` 绑死，只能显示被选中的那一个档位。
 */
.effort-trigger {
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

    &:hover {
        border-color: $accent;
    }

    // 透传与删除两档不使用此处配置的档位，整体降低存在感，
    // 与「已生效的配置」区分开。
    &--muted {
        color: $text-muted;
    }
}

/**
 * 触发器里的档位部分（前缀标签之右的那一段）。
 *
 * 占满剩余宽度并把箭头推到右缘，与最大输出那一栏 n-input 的后缀图标对齐。
 * 背景与边框由外层容器提供，这里只负责布局与 hover 色。
 */
.effort-trigger__value {
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

/** 档位名一旦变长就截断，而不是把箭头挤出控件。 */
.effort-trigger__text {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
    font-variant-numeric: tabular-nums;
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

/**
 * 控件内嵌的模式轮转标签，思考深度与最大输出共用。
 *
 * <h2>为何做成前缀而非独立控件或下拉 header</h2>
 * 模式决定了旁边那个值到底会不会发往上游，所以它必须在<strong>折叠状态</strong>可见 ——
 * 放在下拉菜单的 header 里就看不到了。而做成独立控件会再占一份横向空间，
 * 这一行本来就只有 2/5 或 1/3 的宽度。挂在值的左侧既外显又不额外占位。
 *
 * <p>右侧的竖线把它与值分开 —— 没有分隔时它看起来像值的前半段。
 * 字号比正文小一号：它是标签而非可编辑内容，视觉上要能一眼区分。
 *
 * <p>两栏都用同一个类，读法因此完全一致：左边模式、竖线、右边值。
 */
.inline-mode-toggle {
    flex-shrink: 0;
    padding: 0 6px 0 0;
    margin-right: 6px;
    border: none;
    border-right: 1px solid $border;
    background: transparent;
    color: $text-muted;
    // 模式名是中文，$font-mono 里没有中文字形，声明了也只会回退到系统字体。
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