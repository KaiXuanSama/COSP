<script setup lang="ts">
import { ref } from 'vue'
import { StackedDiff, type LayerChange, type StackedSide } from '../index'

const original = JSON.stringify({
  title: '一个安静的阅读空间',
  language: 'zh-CN',
  layout: 'stacked',
  theme: { surface: 'paper', accent: 'copper', spacing: 16 },
  sections: [
    { title: '开始', description: '在原始文本中编辑，焦点会保护当前内容。' },
    { title: '对照', description: '移到另一侧露出的边缘，切换前景。' },
    { title: '继续', description: '切换后仍能回到原来的选区与滚动位置。' },
    { title: '细节', description: '标题可用键盘聚焦，也可以点击切换。' },
  ],
  enabled: true,
}, null, 2)

const sourceText = ref(original)
const resultText = JSON.stringify({
  ...JSON.parse(original),
  title: '一个清晰的阅读空间',
  theme: { surface: 'paper', accent: 'copper', spacing: 20 },
}, null, 2)
const activeSide = ref<StackedSide>('source')
const hoverSwitch = ref(true)
const compact = ref(false)
const lastChange = ref('等待切换')
const sourceInput = ref<HTMLTextAreaElement | null>(null)
const resultInput = ref<HTMLTextAreaElement | null>(null)
const actionMessage = ref('')

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
    await navigator.clipboard.writeText(resultText)
    actionMessage.value = '已复制只读样本'
  } catch {
    selectText(resultInput.value)
    actionMessage.value = '已选中结果，可使用复制快捷键'
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
          aria-label="独立双文本示例"
          @change="changed"
        >
          <template #source-actions>
            <button class="example-action" type="button" @click="selectText(sourceInput)">全选</button>
          </template>
          <template #source>
            <textarea
              ref="sourceInput"
              v-model="sourceText"
              class="example-text"
              aria-label="编辑原始文本"
              spellcheck="false"
            />
          </template>
          <template #source-notice>可编辑 · 输入和选取时暂停悬停切换</template>
          <template #result-actions>
            <button class="example-action" type="button" @click="copyResult">复制</button>
          </template>
          <template #result>
            <textarea
              ref="resultInput"
              :value="resultText"
              class="example-text example-text--result"
              aria-label="只读结果文本"
              readonly
              spellcheck="false"
            />
          </template>
          <template #result-notice>只读 · 固定样本用于观察叠放效果</template>
        </StackedDiff>
      </div>
      <p class="example-feedback" aria-live="polite">{{ actionMessage || 'Tab 进入另一侧会将其带到前景；Enter / Space 可激活标题。' }}</p>
    </section>

    <footer class="example-footer">
      <span>阶段一 · 容器与交互</span>
      <span>示例在本地运行；文本行映射与滚动协调将在阶段二接入。</span>
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

.example-text {
  box-sizing: border-box;
  display: block;
  flex: 1 1 0;
  width: 100%;
  height: 100%;
  min-height: 0;
  margin: 0;
  padding: 16px;
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
