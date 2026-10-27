<script setup lang="ts">
import grainSymbol from '../assets/grain-symbol.svg'
import FileTypeIcon from './FileTypeIcon.vue'
import VscodeIcon from './VscodeIcon.vue'
import type { RecentItem } from '../recent'

defineProps<{
  busy?: boolean
  recents?: RecentItem[]
}>()

defineEmits<{
  openFolder: []
  openRecent: [item: RecentItem]
}>()
</script>

<template>
  <section class="welcome" aria-label="欢迎">
    <div class="welcome-grid" aria-hidden="true"></div>
    <div class="welcome-stage">
      <img class="welcome-mark" :src="grainSymbol" alt="" width="72" height="72" />
      <h1 class="welcome-brand">Grain</h1>
      <p class="welcome-copy">打开本地文件夹，在平板上写代码、跑终端。</p>
      <div class="welcome-actions">
        <button type="button" class="welcome-primary" :disabled="busy" @click="$emit('openFolder')">
          {{ busy ? '正在打开…' : '打开文件夹' }}
        </button>
      </div>
      <div v-if="recents?.length" class="welcome-recents">
        <p class="welcome-recents-label">最近打开</p>
        <ul class="welcome-recent-list" role="list">
          <li v-for="item in recents" :key="`${item.kind}:${item.path || item.name}`">
            <button
              type="button"
              class="welcome-recent-item"
              :disabled="busy"
              :title="item.name"
              @click="$emit('openRecent', item)"
            >
              <span class="welcome-recent-icon" aria-hidden="true">
                <VscodeIcon v-if="item.kind === 'folder'" name="folder-opened" :size="16" />
                <FileTypeIcon v-else :path="item.path || item.name" />
              </span>
              <span class="welcome-recent-text">
                <span class="welcome-recent-name">{{ item.name }}</span>
                <span class="welcome-recent-meta">{{ item.kind === 'folder' ? '文件夹' : '文件' }}</span>
              </span>
            </button>
          </li>
        </ul>
      </div>
    </div>
  </section>
</template>
