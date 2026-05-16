<script setup lang="ts">
import { NButton, NCheckbox, NForm, NFormItem, NInput, NPopselect, NSwitch } from 'naive-ui'

type EditableModel = Record<string, any>

defineProps<{
    pullingModels: boolean
}>()

const models = defineModel<EditableModel[]>('models', { required: true })

const emit = defineEmits<{
    (e: 'pull-models'): void
    (e: 'open-docs'): void
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
                <n-button text size="tiny" @click="emit('open-docs')" class="docs-window-btn">
                    <template #icon>
                        <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor"
                            stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                            <path d="M6 3h9l3 3v15H6a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2" />
                            <path d="M15 3v4h4" />
                        </svg>
                    </template>
                    官方文档
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
                        <div class="model-form-row">
                            <n-form-item label="模型id" class="model-name-item">
                                <n-input v-model:value="model.modelName" placeholder="模型名称" />
                            </n-form-item>
                        </div>
                        <div class="model-form-row model-form-row--details">
                            <n-form-item label="上下文" class="model-detail-item model-detail-item--grow">
                                <n-input v-model:value="model.contextSize" placeholder="4096">
                                    <template #suffix>
                                        <n-popselect :options="contextPresets" size="small" trigger="click"
                                            @update:value="(value: string) => model.contextSize = value">
                                            <span class="context-preset-trigger">
                                                <svg width="14" height="14" viewBox="0 0 24 24" fill="none"
                                                    stroke="currentColor" stroke-width="2" stroke-linecap="round"
                                                    stroke-linejoin="round">
                                                    <polyline points="6 9 12 15 18 9" />
                                                </svg>
                                            </span>
                                        </n-popselect>
                                    </template>
                                </n-input>
                            </n-form-item>
                            <n-form-item label="工具" class="model-detail-item model-detail-item--switch">
                                <n-switch v-model:value="model.capsTools" size="small" />
                            </n-form-item>
                            <n-form-item label="视觉" class="model-detail-item model-detail-item--switch">
                                <n-switch v-model:value="model.capsVision" size="small" />
                            </n-form-item>
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
        width: 45px;
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
        width: 45px;
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
</style>