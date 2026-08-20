<script setup lang="ts">
/**
 * JsonValueInput — 带类型档位的 JSON 值输入。
 *
 * 左侧下拉选类型（字符串 / 数值 / 列表 / 布尔 / null），右侧按类型给出对应控件。
 * 「设置字段值」与「条件 · 等于」共用本组件 —— 两者面对的都是「用户想表达哪个 JSON 值」
 * 这同一个问题，各写一套只会让同样的语义在两处慢慢分叉。
 *
 * <h2>为何组件自己持有草稿状态</h2>
 * 组件是受控的（值经 `update:value` 回写），但**输入过程中的文本**不能受控 ——
 * 打到 `[1,` 时解析失败、值不该写回，可输入框必须显示用户实际打的内容，
 * 否则会被弹回上一个合法值、列表根本打不完。这份「尚未写回的文本」是纯粹的
 * 本地编辑态，属于组件而非父级；条件列表里每行各有一份，天然按组件实例隔离。
 */
import { computed, ref } from 'vue'
import { NInput, NSelect } from 'naive-ui'
import type { JsonValueType } from '@/features/request-body-rules/jsonValueEditing'
import {
  BOOLEAN_VALUE_OPTIONS,
  JSON_VALUE_TYPE_OPTIONS,
  defaultJsonValueText,
  formatJsonValueText,
  inferJsonValueType,
  parseJsonValue,
  sanitizeNumberInput,
} from '@/features/request-body-rules/jsonValueEditing'

const props = defineProps<{
  /** 当前 JSON 值 */
  value: unknown
  /** 是否只读 */
  readonly?: boolean
  /** 类型下拉的额外类名，便于父级控制宽度 */
  typeClass?: string
  /** 取值控件的额外类名 */
  valueClass?: string
}>()

const emit = defineEmits<{
  (e: 'update:value', value: unknown): void
}>()

/**
 * 用户显式选择的类型档位。
 *
 * <p>档位通常能从已保存的值反推（见 `inferJsonValueType`），但有一档反推不出来：
 * 数值档位刚切过去时输入框是空的，空文本解析失败、值写不回去，于是反推仍得到旧类型 ——
 * 用户选了「数值」界面却弹回「字符串」。档位是用户的**意图**，当值暂时无法表达它时
 * 意图必须能独立存在，因此显式记一份，写回成功后清空交还给反推。
 */
const pendingType = ref<JsonValueType | null>(null)

/** 尚未写回的文本。 */
const pendingText = ref<string | null>(null)

/** 当前生效的类型档位。 */
const activeType = computed<JsonValueType>(
  () => pendingType.value ?? inferJsonValueType(props.value),
)

/** 由已保存值渲染出的规范文本。 */
const canonicalText = computed(() => formatJsonValueText(props.value, activeType.value))

/** 输入框显示的文本：优先显示未写回的草稿。 */
const displayText = computed(() => pendingText.value ?? canonicalText.value)

/** 展示用的解析错误：以输入框里实际显示的文本为准。 */
const displayError = computed(() => parseJsonValue(displayText.value, activeType.value).error)

/** 布尔档位用下拉，其余用文本输入。 */
const usesSelect = computed(() => activeType.value === 'boolean')

/** null 档位禁止输入。 */
const inputDisabled = computed(() => props.readonly || activeType.value === 'null')

const placeholder = computed(() => {
  switch (activeType.value) {
    case 'string':
      return '原样作为字符串，如 user'
    case 'number':
      return '如 0.7 或 -2'
    case 'list':
      return '如 [12.38, false, "hello world", null]'
    case 'null':
      return 'null'
    default:
      return ''
  }
})

/**
 * 写回解析后的值；解析失败时保留原值，只把文本留作草稿。
 *
 * <p>类型作为显式参数而非读 `activeType` —— 切换档位时后者尚未更新完毕，
 * 用它解析新档位的文本会得到错的值。
 *
 * <h3>为何成功后仍可能保留草稿</h3>
 * 解析成功不代表输入已经「写完」。`12.` 是合法数值（解析为 12），但把它规范化回 `12`
 * 会吃掉尾部小数点 —— 用户接着打 `3` 就得到 `123` 而不是 `12.3`，小数根本打不出来。
 * 列表同理：`[1, 2]` 规范化成 `[1,2]` 会在打字过程中不断吞掉空格。
 * 因此只在**文本与其规范形式一致**时才交还给反推，否则保留用户的原始文本。
 */
function commit(text: string, type: JsonValueType) {
  const parsed = parseJsonValue(text, type)
  if (parsed.error) {
    pendingText.value = text
    return
  }
  pendingText.value = text === formatJsonValueText(parsed.value, type) ? null : text
  pendingType.value = null
  emit('update:value', parsed.value)
}

function updateText(text: string) {
  commit(activeType.value === 'number' ? sanitizeNumberInput(text) : text, activeType.value)
}

/** 切换类型时用该档位的默认文本重置，避免把上一档位的内容按新类型硬解释。 */
function updateType(next: JsonValueType) {
  pendingType.value = next
  pendingText.value = null
  commit(defaultJsonValueText(next), next)
}

defineExpose({ displayError })
</script>

<template>
  <div class="json-value-input">
    <div class="json-value-row">
      <NSelect
        :value="activeType"
        @update:value="updateType($event as JsonValueType)"
        :options="JSON_VALUE_TYPE_OPTIONS"
        size="small"
        :class="['json-value-type', typeClass]"
        :disabled="readonly"
      />
      <NSelect
        v-if="usesSelect"
        :value="displayText"
        @update:value="updateText($event)"
        :options="BOOLEAN_VALUE_OPTIONS"
        size="small"
        :class="['json-value-control', valueClass]"
        :disabled="readonly"
      />
      <NInput
        v-else
        :value="displayText"
        @update:value="updateText"
        :placeholder="placeholder"
        size="small"
        :class="['json-value-control', valueClass]"
        :disabled="inputDisabled"
      />
    </div>
    <span v-if="displayError" class="json-value-error">{{ displayError }}</span>
  </div>
</template>

<style lang="scss" scoped>
@use '@/styles/variables' as *;

.json-value-input {
  min-width: 0;
  flex: 1;
}

/** 类型档位固定宽度，取值控件占满剩余空间。 */
.json-value-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.json-value-type {
  width: 96px;
  flex-shrink: 0;
}

.json-value-control {
  flex: 1;
  min-width: 0;
}

.json-value-error {
  display: block;
  color: $danger;
  font-size: 11px;
  margin-top: 2px;
}
</style>
