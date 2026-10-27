<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'

export interface ConsoleLine {
  id: number
  time: string
  level: 'log' | 'info' | 'warn' | 'error' | 'system'
  text: string
}

const props = defineProps<{
  lines: ConsoleLine[]
  active: boolean
}>()

const scroller = ref<HTMLElement | null>(null)

watch(
  () => [props.lines.length, props.active] as const,
  async ([, active]) => {
    if (!active) return
    await nextTick()
    const el = scroller.value
    if (el) el.scrollTop = el.scrollHeight
  },
)

function label(level: ConsoleLine['level']): string {
  if (level === 'system') return 'run'
  return level
}
</script>

<template>
  <div ref="scroller" class="log-scroll" aria-live="polite">
    <p v-if="lines.length === 0" class="log-empty">还没有输出。写好 main.js，按运行。</p>
    <ol v-else class="log console-log">
      <li v-for="line in lines" :key="line.id" class="log-line" :class="line.level">
        <span class="log-time">{{ line.time }}</span>
        <span class="log-level">{{ label(line.level) }}</span>
        <span class="log-msg">{{ line.text }}</span>
      </li>
    </ol>
  </div>
</template>
