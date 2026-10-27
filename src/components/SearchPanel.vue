<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import FileTypeIcon from './FileTypeIcon.vue'
import VscodeIcon from './VscodeIcon.vue'
import { searchContent, type SearchFileGroup, type SearchHit } from '../search/contentSearch'

const props = defineProps<{
  cwd?: string
  busy?: boolean
}>()

const emit = defineEmits<{
  open: [hit: SearchHit]
}>()

const query = ref('')
const caseSensitive = ref(false)
const wholeWord = ref(false)
const isRegex = ref(false)
const searching = ref(false)
const error = ref('')
const result = ref<{ files: SearchFileGroup[]; truncated: boolean; engine: string } | null>(null)
const expanded = ref<Record<string, boolean>>({})
const queryInput = ref<HTMLInputElement | null>(null)
let searchSeq = 0
let debounceTimer = 0

const matchCount = computed(() =>
  (result.value?.files ?? []).reduce((sum, file) => sum + file.matches.length, 0),
)

const statusText = computed(() => {
  if (searching.value) return '正在搜索…'
  if (error.value) return error.value
  if (!query.value.trim()) return '在文件中搜索'
  if (!result.value) return ''
  const files = result.value.files.length
  const matches = matchCount.value
  const base = matches === 0
    ? '未找到结果'
    : `${matches} 个结果 · ${files} 个文件`
  return result.value.truncated ? `${base}（已截断）` : base
})

async function runSearch() {
  const q = query.value.trim()
  const seq = ++searchSeq
  error.value = ''
  if (!q) {
    result.value = null
    searching.value = false
    return
  }
  searching.value = true
  try {
    const next = await searchContent({
      query: q,
      cwd: props.cwd || undefined,
      caseSensitive: caseSensitive.value,
      wholeWord: wholeWord.value,
      isRegex: isRegex.value,
      maxResults: 2000,
    })
    if (seq !== searchSeq) return
    result.value = next
    const open: Record<string, boolean> = {}
    for (const file of next.files.slice(0, 40)) open[file.path] = true
    expanded.value = open
  } catch (e) {
    if (seq !== searchSeq) return
    error.value = e instanceof Error ? e.message : String(e)
    result.value = null
  } finally {
    if (seq === searchSeq) searching.value = false
  }
}

function scheduleSearch() {
  window.clearTimeout(debounceTimer)
  debounceTimer = window.setTimeout(() => { void runSearch() }, 280)
}

function toggleFile(path: string) {
  expanded.value = { ...expanded.value, [path]: !expanded.value[path] }
}

function highlight(text: string, start: number, end: number) {
  const safeStart = Math.max(0, Math.min(start, text.length))
  const safeEnd = Math.max(safeStart, Math.min(end, text.length))
  return {
    before: text.slice(0, safeStart),
    match: text.slice(safeStart, safeEnd),
    after: text.slice(safeEnd),
  }
}

function focusQuery() {
  void nextTick(() => queryInput.value?.focus())
}

watch(() => props.cwd, () => {
  if (query.value.trim()) void runSearch()
})

defineExpose({ focusQuery, runSearch })
</script>

<template>
  <div class="search-panel">
    <div class="search-panel-head">
      <span class="search-panel-title">搜索</span>
      <span v-if="cwd" class="search-panel-scope" :title="cwd">范围 · {{ cwd }}</span>
    </div>

    <div class="search-box">
      <div class="search-input-row">
        <input
          ref="queryInput"
          v-model="query"
          class="search-input"
          type="search"
          enterkeyhint="search"
          placeholder="搜索"
          autocomplete="off"
          autocapitalize="off"
          spellcheck="false"
          :disabled="busy"
          @input="scheduleSearch"
          @keydown.enter.prevent="runSearch"
        />
        <div class="search-toggles" role="group" aria-label="搜索选项">
          <button
            type="button"
            class="search-toggle"
            :class="{ on: caseSensitive }"
            title="区分大小写"
            aria-label="区分大小写"
            :aria-pressed="caseSensitive"
            @click="caseSensitive = !caseSensitive; scheduleSearch()"
          >
            <VscodeIcon name="case-sensitive" :size="14" />
          </button>
          <button
            type="button"
            class="search-toggle"
            :class="{ on: wholeWord }"
            title="全字匹配"
            aria-label="全字匹配"
            :aria-pressed="wholeWord"
            @click="wholeWord = !wholeWord; scheduleSearch()"
          >
            <VscodeIcon name="whole-word" :size="14" />
          </button>
          <button
            type="button"
            class="search-toggle"
            :class="{ on: isRegex }"
            title="使用正则表达式"
            aria-label="使用正则表达式"
            :aria-pressed="isRegex"
            @click="isRegex = !isRegex; scheduleSearch()"
          >
            <VscodeIcon name="regex" :size="14" />
          </button>
        </div>
      </div>
      <p class="search-status">{{ statusText }}</p>
    </div>

    <div class="search-results" role="tree">
      <div v-for="file in result?.files ?? []" :key="file.path" class="search-file">
        <button
          type="button"
          class="search-file-row"
          :aria-expanded="!!expanded[file.path]"
          @click="toggleFile(file.path)"
        >
          <span class="search-twist" :class="{ open: expanded[file.path] }" aria-hidden="true" />
          <FileTypeIcon :path="file.path" />
          <span class="search-file-name">{{ file.path.split('/').pop() }}</span>
          <span class="search-file-path">{{ file.path.includes('/') ? file.path.slice(0, file.path.lastIndexOf('/')) : '' }}</span>
          <span class="search-file-count">{{ file.matches.length }}</span>
        </button>
        <div v-if="expanded[file.path]" class="search-matches">
          <button
            v-for="(hit, index) in file.matches"
            :key="`${hit.path}:${hit.line}:${hit.column}:${index}`"
            type="button"
            class="search-match"
            @click="$emit('open', hit)"
          >
            <span class="search-match-line">{{ hit.line }}</span>
            <span class="search-match-text">
              <template v-for="part in [highlight(hit.text, hit.matchStart, hit.matchEnd)]" :key="part.before + part.match">
                {{ part.before }}<mark>{{ part.match }}</mark>{{ part.after }}
              </template>
            </span>
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
