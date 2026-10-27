<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import type { LogLine } from '../types'

const props = defineProps<{
  logs: LogLine[]
  active: boolean
}>()

const scroller = ref<HTMLElement | null>(null)

watch(
  () => [props.logs.length, props.active] as const,
  async ([, active]) => {
    if (!active) return
    await nextTick()
    const el = scroller.value
    if (el) el.scrollTop = el.scrollHeight
  },
)

function sourceLabel(source: LogLine['source']): string {
  if (source === 'stub') return '桩'
  if (source === 'device') return '设备'
  return '壳'
}
</script>

<template>
  <div ref="scroller" class="log-scroll" aria-live="polite">
    <p v-if="logs.length === 0" class="log-empty">还没有日志。</p>
    <ol v-else class="log">
      <li v-for="line in logs" :key="line.id" class="log-line" :class="line.level">
        <span class="log-time">{{ line.time }}</span>
        <span class="log-source" :class="line.source">{{ sourceLabel(line.source) }}</span>
        <span class="log-event">{{ line.eventName }}</span>
        <span class="log-msg">{{ line.message }}</span>
        <span v-if="line.detail" class="log-detail">{{ line.detail }}</span>
      </li>
    </ol>
  </div>
</template>
