<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, shallowRef } from 'vue'
import {
  StackedDiff, DiffTextView, TextareaLineNumbers, createTextareaScrollPort,
  type LayerChange, type StackedSide, type LineScrollPort, type TextareaScrollPort,
} from '../index'
import { createSample } from './sample'

const sample = createSample()
const sourceText = ref(sample.sourceText)
const lineMap = computed(() => sourceText.value === sample.sourceText ? sample.lineMap : null)
const activeSide = ref<StackedSide>('source')
const hoverSwitch = ref(true)
const compact = ref(false)
const lastChange = ref('等待切换')
const sourceInput = ref<HTMLTextAreaElement | null>(null)
const sourcePort = shallowRef<TextareaScrollPort | null>(null)
const resultPort = shallowRef<LineScrollPort | null>(null)
const actionMessage = ref('')

onMounted(() => {
  if (sourceInput.value) sourcePort.value = createTextareaScrollPort(sourceInput.value)
})
onBeforeUnmount(() => sourcePort.value?.dispose())

function changed(change: LayerChange) {
  activeSide.value = change.side
  const reasons = { hover: '悬停', focus: '焦点进入', explicit: '主动切换' }
  lastChange.value = reasons[change.reason]
}

function selectText(input: HTMLTextAreaElement | null) {
  input?.focus({ preventScroll: true })
  input?.select()
}

async function copyResult() {
  try {
    await navigator.clipboard.writeText(sample.resultText)
    actionMessage.value = '已复制只读样本'
  } catch {
    actionMessage.value = '浏览器未授予剪贴板权限'
  }
}
</script>

<template>
  <main class="example-page">
    <header class="example-heading">
      <span class="example-eyebrow">STACKED DIFF · 交互示例</span>
      <h1>两份内容，一处对照。</h1>
      <p>悬停露出的侧边即可切换。编辑时保持当前层级，也可以点击标题主动切换。</p>
    </header>

    <section class="example-workspace" aria-label="叠放容器示例">
      <div class="example-toolbar">
        <div class="example-status" aria-live="polite">
          <span class="example-dot" aria-hidden="true" />
          <strong>{{ activeSide === 'source' ? '原始文本' : '只读结果' }}</strong>
          <span>{{ lastChange }}</span>
        </div>
        <div class="example-options">
          <label><input v-model="hoverSwitch" type="checkbox" /> 悬停切换</label>
          <label><input v-model="compact" type="checkbox" /> 窄容器</label>
        </div>
      </div>

      <div class="example-stage" :class="{ 'example-stage--compact': compact }">
        <StackedDiff
          source-title="原始文本"
          result-title="只读结果"
          :hover-switch="hoverSwitch"
          :line-map="lineMap"
          :source-scroll-port="sourcePort"
          :result-scroll-port="resultPort"
          aria-label="独立双文本示例"
          @change="changed"
        >
          <template #source-actions>
            <button class="example-action" type="button" @click="sourceText = sample.sourceText">还原</button>
            <button class="example-action" type="button" @click="selectText(sourceInput)">全选</button>
          </template>
          <template #source>
            <div class="example-source">
              <TextareaLineNumbers :port="sourcePort" :value="sourceText" />
              <textarea
                ref="sourceInput"
                v-model="sourceText"
                class="example-text"
                aria-label="编辑原始文本"
                spellcheck="false"
              />
            </div>
          </template>
          <template #source-notice>{{ lineMap ? '可编辑 · 输入和选取时暂停悬停切换' : '草稿已修改 · 还原样本后恢复同步滚动' }}</template>
          <template #result-actions>
            <button class="example-action" type="button" @click="copyResult">复制</button>
          </template>
          <template #result>
            <DiffTextView
              :lines="sample.resultLines"
              aria-label="只读结果文本"
              @port="resultPort = $event"
            />
          </template>
          <template #result-notice>只读 · 聚焦不锁定，悬停仍可切换</template>
        </StackedDiff>
      </div>
      <p class="example-feedback" aria-live="polite">{{ actionMessage || 'Tab 进入另一侧会将其带到前景；Enter / Space 可激活标题。' }}</p>
    </section>

    <footer class="example-footer">
      <span>阶段二 · 逻辑行与同步滚动</span>
      <span>前景滚动带动后景；增删行通过样本映射定位，原始草稿保持原样。</span>
    </footer>
  </main>
</template>

<style scoped lang="scss">
.example-page {
  min-height: 100vh;
  box-sizing: border-box;
  padding: clamp(24px, 5vw, 64px);
  background: #f5f3ee;
  color: #4a4740;
  font-family: system-ui, sans-serif;
  font-size: 14px;
  line-height: 1.6;
}

.example-heading,
.example-workspace,
.example-footer {
  max-width: 1040px;
  margin-inline: auto;
}

.example-eyebrow {
  color: #a06b3f;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.16em;
}

.example-heading h1 {
  margin: 12px 0 8px;
  color: #28251f;
  font-size: clamp(25px, 3.5vw, 36px);
  font-weight: 550;
  letter-spacing: -0.04em;
}

.example-heading p {
  margin: 0 0 28px;
  color: #7a746b;
}

.example-workspace {
  padding: clamp(12px, 3vw, 28px);
  border: 1px solid #e4ded4;
  border-radius: 22px;
  background: #fcfaf6;
  box-shadow: 0 12px 40px rgb(65 48 29 / 4%);
}

.example-toolbar,
.example-status,
.example-options,
.example-options label {
  display: flex;
  align-items: center;
  gap: 10px;
}

.example-toolbar {
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 22px;
}

.example-status,
.example-options {
  font-size: 12px;
}

.example-status strong {
  font-weight: 600;
}

.example-status > span:last-child {
  color: #9a9185;
}

.example-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #9b784c;
}

.example-options label {
  gap: 4px;
  cursor: pointer;
}

.example-options input {
  accent-color: #ad743f;
}

.example-stage {
  --stacked-diff-offset: 11%;
  --stacked-diff-color: #4a4740;
  --stacked-diff-muted: #948878;
  --stacked-diff-accent: #b57d47;
  --stacked-diff-border: rgb(180 155 123 / 30%);
  height: 460px;
  max-width: 760px;
  margin-inline: auto;
}

.example-stage--compact {
  max-width: 340px;
}

.example-action {
  padding: 4px 6px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: #a56e3d;
  font: inherit;
  font-size: 12px;
  cursor: pointer;
}

.example-action:hover {
  background: rgb(194 122 62 / 8%);
}

.example-action:focus-visible {
  outline: 2px solid #b57d47;
  outline-offset: 1px;
}

.example-source {
  display: flex;
  flex: 1 1 0;
  min-width: 0;
  min-height: 0;
  line-height: 22.75px;
}

.example-text {
  box-sizing: border-box;
  display: block;
  flex: 1 1 0;
  width: 0;
  min-width: 0;
  height: 100%;
  min-height: 0;
  margin: 0;
  padding: 16px 16px 16px 0;
  resize: none;
  border: 0;
  border-radius: 0;
  outline: none;
  background: transparent;
  color: inherit;
  font-family: 'Cascadia Code', Consolas, monospace;
  font-size: 13px;
  line-height: 1.75;
  tab-size: 2;
  scrollbar-width: thin;
  scrollbar-color: #ccc0b0 transparent;
}

.example-text--result {
  color: #74644f;
}

.example-feedback {
  min-height: 20px;
  margin: 20px 0 0;
  color: #918779;
  font-size: 12px;
}

.example-footer {
  display: flex;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 6px 20px;
  margin-top: 20px;
  color: #9a9185;
  font-size: 12px;
}
</style>
