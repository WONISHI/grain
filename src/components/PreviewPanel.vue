<script setup lang="ts">
import { ref, watch } from 'vue'

const props = defineProps<{
  srcdoc: string
  empty: boolean
  localEnabled?: boolean
  openUrl?: string
}>()

const address = ref('http://127.0.0.1:5173')
const localUrl = ref('')
const error = ref('')
const revision = ref(0)

function normalizeLocalUrl(raw: string): string {
  const url = new URL(raw.trim())
  if (url.protocol !== 'http:') throw new Error('请输入 http://127.0.0.1:端口 形式的本机服务地址')
  if (url.hostname === 'localhost') url.hostname = '127.0.0.1'
  if (url.hostname !== '127.0.0.1' || !url.port || url.username || url.password) {
    throw new Error('请输入 http://127.0.0.1:端口 形式的本机服务地址')
  }
  url.hash = ''
  return url.href
}

function openLocal(raw = address.value) {
  try {
    const href = normalizeLocalUrl(raw)
    address.value = href
    localUrl.value = href
    revision.value += 1
    error.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : String(e)
  }
}

watch(
  () => props.openUrl,
  (value) => {
    if (!value || !props.localEnabled) return
    openLocal(value)
  },
)

defineExpose({ openLocal })
</script>

<template>
  <header class="pane-bar preview-bar">
    <span class="slash" aria-hidden="true"></span>
    <span v-if="localEnabled" class="pane-tab">本机服务</span>
  </header>
  <form v-if="localEnabled" class="terminal-command" @submit.prevent="openLocal()">
    <input v-model="address" type="url" aria-label="本机 HTTP 服务地址" autocapitalize="off" spellcheck="false" />
    <button class="btn" type="submit">打开</button>
  </form>
  <p v-if="error && localEnabled" class="terminal-error" role="alert">{{ error }}</p>
  <div v-if="localEnabled" class="preview-stage">
    <iframe v-if="localUrl" :key="revision" class="preview-frame" :src="localUrl" sandbox="allow-scripts allow-same-origin allow-forms" title="手机本机服务预览"></iframe>
    <div v-else class="preview-empty">
      <p>先在终端启动项目服务，再打开对应端口</p>
      <p class="preview-hint">此面板打开已有服务，不会自动启动 Node。</p>
    </div>
  </div>
  <div v-else class="preview-stage">
    <iframe
      v-show="srcdoc"
      class="preview-frame"
      sandbox="allow-scripts"
      title="运行预览"
      :srcdoc="srcdoc"
    ></iframe>
    <div v-if="!srcdoc" class="preview-empty">
      <img src="/favicon.svg" width="64" height="64" alt="" />
      <p>按「运行」执行 main.js</p>
      <p class="preview-hint">画出 DOM 的脚本会显示在这里</p>
    </div>
    <div v-else-if="empty" class="preview-empty overlay">
      <p>已在控制台输出</p>
    </div>
  </div>
</template>
