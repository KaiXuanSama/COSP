<script setup lang="ts">
import { NButton, NCheckbox, NForm, NFormItem, NInput, NPopselect, NSelect, NSwitch } from 'naive-ui'

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

const maxOutputPresets = [
    { label: '512K', value: '512000' },
    { label: '256K', value: '256000' },
    { label: '128K', value: '128000' },
    { label: '64K', value: '64000' },
]

const effortOptions = [
    { label: 'None', value: 'None' },
    { label: 'Low', value: 'Low' },
    { label: 'Medium', value: 'Medium' },
    { label: 'High', value: 'High' },
    { label: 'Xhigh', value: 'Xhigh' },
    { label: 'Max', value: 'Max' }
]
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
                                    <n-form-item label="上下文" class="model-detail-item model-detail-item--half">
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
                                        <n-select :options="effortOptions" size="small" :value="model.reasoningEffort"
                                            @update:value="(val: string) => model.reasoningEffort = val"
                                            :consistent-menu-width="false" />
                                    </n-form-item>
                                </div>
                            </div>
                            <div class="model-capability-divider"></div>
                            <div class="model-capability-group model-capability-group--ollama">
                                <div class="model-form-row">
                                    <n-form-item label="最大输出" class="model-detail-item model-detail-item--half">
                                        <n-input v-model:value="model.maxOutputTokens" placeholder="128000">
                                            <template #suffix>
                                                <n-popselect :options="maxOutputPresets" size="small" trigger="click"
                                                    @update:value="(value: string) => model.maxOutputTokens = value">
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

.model-effort-item {
    flex: 0 0 auto;
    margin-bottom: 0 !important;

    :deep(.n-form-item-blank) {
        flex: 0;
        min-width: 0;
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