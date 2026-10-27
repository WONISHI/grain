<script setup lang="ts">
import { computed } from 'vue'
import grainSymbol from '../assets/grain-symbol.svg'
import FileTypeIcon from './FileTypeIcon.vue'
import VscodeIcon from './VscodeIcon.vue'
import type { RecentItem } from '../recent'

const props = defineProps<{
  mode: 'loading' | 'barren' | 'idle'
  workspaceName?: string
  recents?: RecentItem[]
  busy?: boolean
  openShortcut?: string
}>()

defineEmits<{
  openFolder: []
  createFile: []
  openSearch: []
  openRecent: [item: RecentItem]
}>()

const title = computed(() => {
  if (props.mode === 'loading') return '正在打开工作区'
  if (props.mode === 'barren') return '工作区还是空的'
  return '没有打开的编辑器'
})

const copy = computed(() => {
  if (props.mode === 'loading') return '正在读取设备上的项目文件…'
  if (props.mode === 'barren') return `在「${props.workspaceName || '工作区'}」里新建文件，或打开一个本地文件夹。`
  return '从左侧资源管理器选择文件，或用快速打开跳转。'
})
</script>

<template>
  <div class="editor-empty" role="status">
    <div class="editor-empty-inner">
      <img class="editor-empty-mark" :src="grainSymbol" alt="" width="48" height="48" />
      <h2 class="editor-empty-title">{{ title }}</h2>
      <p class="editor-empty-copy">{{ copy }}</p>

      <div v-if="mode !== 'loading'" class="editor-empty-actions">
        <button
          v-if="mode === 'barren'"
          type="button"
          class="editor-empty-btn primary"
          :disabled="busy"
          @click="$emit('openFolder')"
        >
          打开文件夹
        </button>
        <button
          type="button"
          class="editor-empty-btn"
          :disabled="busy"
          @click="$emit('createFile')"
        >
          新建文件
        </button>
        <button
          v-if="mode === 'idle'"
          type="button"
          class="editor-empty-btn"
          @click="$emit('openSearch')"
        >
          快速打开
        </button>
      </div>

      <div v-if="mode !== 'loading' && recents?.length" class="editor-empty-recents">
        <p class="editor-empty-recents-label">最近打开</p>
        <ul class="editor-empty-recent-list">
          <li v-for="item in recents.slice(0, 5)" :key="`${item.kind}:${item.path || item.name}`">
            <button
              type="button"
              class="editor-empty-recent"
              :disabled="busy"
              :title="item.path || item.name"
              @click="$emit('openRecent', item)"
            >
              <span class="editor-empty-recent-icon" aria-hidden="true">
                <VscodeIcon v-if="item.kind === 'folder'" name="folder-opened" :size="16" />
                <FileTypeIcon v-else :path="item.path || item.name" />
              </span>
              <span class="editor-empty-recent-name">{{ item.name }}</span>
              <span class="editor-empty-recent-meta">{{ item.kind === 'folder' ? '文件夹' : item.path }}</span>
            </button>
          </li>
        </ul>
      </div>

      <p v-if="mode === 'idle' && openShortcut" class="editor-empty-hint">
        {{ openShortcut }} 快速打开文件
      </p>
      <p v-else-if="mode === 'barren'" class="editor-empty-hint">
        也可在左侧文件夹上长按创建
      </p>
    </div>
  </div>
</template>
