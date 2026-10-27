<script setup lang="ts">
import { computed } from 'vue'
import add from '@vscode/codicons/src/icons/add.svg?raw'
import caseSensitive from '@vscode/codicons/src/icons/case-sensitive.svg?raw'
import chevronDown from '@vscode/codicons/src/icons/chevron-down.svg?raw'
import checklist from '@vscode/codicons/src/icons/checklist.svg?raw'
import clearAll from '@vscode/codicons/src/icons/clear-all.svg?raw'
import code from '@vscode/codicons/src/icons/code.svg?raw'
import closeIcon from '@vscode/codicons/src/icons/close.svg?raw'
import regex from '@vscode/codicons/src/icons/regex.svg?raw'
import search from '@vscode/codicons/src/icons/search.svg?raw'
import debugConsole from '@vscode/codicons/src/icons/debug-console.svg?raw'
import debugStop from '@vscode/codicons/src/icons/debug-stop.svg?raw'
import discard from '@vscode/codicons/src/icons/discard.svg?raw'
import files from '@vscode/codicons/src/icons/files.svg?raw'
import folderOpened from '@vscode/codicons/src/icons/folder-opened.svg?raw'
import gripper from '@vscode/codicons/src/icons/gripper.svg?raw'
import inspect from '@vscode/codicons/src/icons/inspect.svg?raw'
import json from '@vscode/codicons/src/icons/json.svg?raw'
import layoutPanel from '@vscode/codicons/src/icons/layout-panel.svg?raw'
import newFile from '@vscode/codicons/src/icons/new-file.svg?raw'
import newFolder from '@vscode/codicons/src/icons/new-folder.svg?raw'
import layoutSidebarLeft from '@vscode/codicons/src/icons/layout-sidebar-left.svg?raw'
import layoutSidebarRight from '@vscode/codicons/src/icons/layout-sidebar-right.svg?raw'
import linkExternal from '@vscode/codicons/src/icons/link-external.svg?raw'
import move from '@vscode/codicons/src/icons/move.svg?raw'
import preview from '@vscode/codicons/src/icons/preview.svg?raw'
import refresh from '@vscode/codicons/src/icons/refresh.svg?raw'
import settingsGear from '@vscode/codicons/src/icons/settings-gear.svg?raw'
import terminal from '@vscode/codicons/src/icons/terminal.svg?raw'
import trash from '@vscode/codicons/src/icons/trash.svg?raw'
import wholeWord from '@vscode/codicons/src/icons/whole-word.svg?raw'

const layers2 = `<svg width="16" height="16" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg" fill="currentColor"><path d="M1.5 5.5 8 2l6.5 3.5L8 9 1.5 5.5Zm0 3L8 12l6.5-3.5-.75-1.3L8 10.2 2.25 7.2 1.5 8.5Zm0 3L8 15l6.5-3.5-.75-1.3L8 13.2 2.25 10.2 1.5 11.5Z"/></svg>`

// Codicon `debug`（虫子），缩到 16×16，避免 24px 原稿在小按钮上糊成三角
const debug = `<svg width="16" height="16" viewBox="0 0 16 16" xmlns="http://www.w3.org/2000/svg" fill="currentColor"><path d="M10.5 4c0-1.38-1.12-2.5-2.5-2.5S5.5 2.62 5.5 4H4.25C3.56 4 3 4.56 3 5.25V8H1.5v1H3v1.5c0 1.21.53 2.29 1.37 3.04L3.22 14.7l.7.7 1.22-1.22A4.48 4.48 0 0 0 8 15a4.48 4.48 0 0 0 2.86-.82l1.22 1.22.7-.7-1.15-1.16A4.49 4.49 0 0 0 13 10.5V9h1.5V8H13V5.25C13 4.56 12.44 4 11.75 4H10.5zm-4 0C6.5 3.17 7.17 2.5 8 2.5S9.5 3.17 9.5 4h-3zM4 5.25c0-.14.11-.25.25-.25h7.5c.14 0 .25.11.25.25V10.5A3.5 3.5 0 0 1 8 14a3.5 3.5 0 0 1-3.5-3.5V5.25zM6.85 7.15 8 8.29l1.15-1.14.7.7L8.71 9l1.14 1.15-.7.7L8 9.71l-1.15 1.14-.7-.7L7.29 9 6.15 7.85l.7-.7z"/></svg>`

const icons = {
  add,
  'case-sensitive': caseSensitive,
  'chevron-down': chevronDown,
  checklist,
  'clear-all': clearAll,
  code,
  close: closeIcon,
  debug,
  'debug-console': debugConsole,
  'debug-stop': debugStop,
  discard,
  files,
  'folder-opened': folderOpened,
  gripper,
  inspect,
  json,
  'layers-2': layers2,
  'layout-panel': layoutPanel,
  'new-file': newFile,
  'new-folder': newFolder,
  'layout-sidebar-left': layoutSidebarLeft,
  'layout-sidebar-right': layoutSidebarRight,
  'link-external': linkExternal,
  move,
  preview,
  refresh,
  regex,
  search,
  'settings-gear': settingsGear,
  terminal,
  trash,
  'whole-word': wholeWord,
} as const

const props = withDefaults(defineProps<{
  name: keyof typeof icons
  size?: number
}>(), {
  size: 16,
})

const svg = computed(() => icons[props.name]
  .replace(/\swidth="[^"]*"/, ` width="${props.size}"`)
  .replace(/\sheight="[^"]*"/, ` height="${props.size}"`))
</script>

<template>
  <span class="vscode-icon" :style="{ width: `${size}px`, height: `${size}px` }" v-html="svg" aria-hidden="true" />
</template>
