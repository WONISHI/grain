import { languageOf } from '../editor/languages'

export interface ProjectFile {
  path: string
  language: string
  runnable: boolean
  starter: string
}

export const mainStarter = `const name = 'Grain'

console.log(name + ' 已启动')
console.info('Ctrl+Enter 或 ⌘Enter 再跑一次')
console.warn('这是一条警告')

document.body.innerHTML = \`
  <style>
    body {
      margin: 0;
      min-height: 100vh;
      display: grid;
      place-items: center;
      background: #0b0d10;
      color: #f4f1ec;
      font-family: sans-serif;
    }
    main { text-align: center; }
    h1 { margin: 0 0 8px; font-weight: 560; }
    .eyebrow {
      margin: 0 0 8px;
      color: #ff6b4a;
      letter-spacing: 0.16em;
      font-size: 12px;
    }
    p { margin: 0; color: #b4bac3; }
  </style>
  <main>
    <p class="eyebrow">PLAYGROUND</p>
    <h1>\${name}</h1>
    <p>这段页面是 main.js 画出来的。</p>
  </main>
\`
`

export const vueStarter = `<script setup lang="ts">
import { ref } from 'vue'

const count = ref(0)
</script>

<template>
  <main>
    <p class="eyebrow">Grain</p>
    <h1>在手机上改前端</h1>
    <button type="button" @click="count++">已点 {{ count }} 次</button>
  </main>
</template>

<style scoped>
main {
  min-height: 100svh;
  display: grid;
  place-content: center;
  gap: 12px;
  background: #0b0d10;
  color: #f4f1ec;
  font-family: sans-serif;
}

.eyebrow {
  margin: 0;
  color: #ff6b4a;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  font-size: 12px;
}

button {
  border: 0;
  border-radius: 999px;
  padding: 10px 16px;
  background: #ff6b4a;
  color: #2a100b;
}
</style>
`

export const readmeStarter = `# Grain 工作区

这是编辑器里的项目。

## 文件

- \`main.js\` 是运行入口。按运行后，输出会出现在下方面板。
- \`src/App.vue\` 用来编辑 Vue 页面。

## 本机服务

先在终端里启动项目，再打开右侧的本机服务，填入地址，例如：

\`http://127.0.0.1:5173\`

这里只打开已经在运行的服务。
`

export const projectFiles: ProjectFile[] = [
  { path: 'README.md', language: 'markdown', runnable: false, starter: readmeStarter },
  { path: 'main.js', language: 'javascript', runnable: true, starter: mainStarter },
  { path: 'src/App.vue', language: 'vue', runnable: false, starter: vueStarter },
]

export const entryPath = 'main.js'

function storageKey(path: string): string {
  return `grain.file.${path}`
}

export function loadFile(file: ProjectFile): string {
  try {
    const saved = localStorage.getItem(storageKey(file.path))
    if (saved !== null) return saved
    if (file.path === 'src/App.vue') {
      return localStorage.getItem('grain.draft.src/App.vue') ?? file.starter
    }
    return file.starter
  } catch {
    return file.starter
  }
}

export function saveFile(path: string, source: string): void {
  try {
    localStorage.setItem(storageKey(path), source)
  } catch {
    // Private mode can reject storage. The buffer still lives in memory.
  }
}

export function forgetFile(path: string): void {
  try {
    localStorage.removeItem(storageKey(path))
  } catch {
    // Ignore storage failures. The in-memory buffer is already gone.
  }
}

const catalogKey = 'grain.catalog'

export function languageFor(path: string): ProjectFile['language'] {
  return languageOf(path).id
}

export function languageLabel(path: string): string {
  return languageOf(path).label
}

export function validEntryName(name: string): boolean {
  const trimmed = name.trim()
  return Boolean(trimmed) && trimmed !== '.' && trimmed !== '..' && !/[/\\\u0000]/.test(trimmed)
}

function safeRelativePath(path: string): boolean {
  if (!path || path.startsWith('/') || path.endsWith('/')) return false
  return path.split('/').every((part) => part && part !== '.' && part !== '..')
}

function blankFile(path: string): ProjectFile {
  return { path, language: languageFor(path), runnable: path === entryPath, starter: '' }
}

export function loadCatalog(): ProjectFile[] {
  const starters = new Map(projectFiles.map((file) => [file.path, file]))
  let paths: string[] = []
  try {
    const raw = localStorage.getItem(catalogKey)
    if (raw) {
      const parsed = JSON.parse(raw) as unknown
      if (Array.isArray(parsed) && parsed.every((item) => typeof item === 'string' && safeRelativePath(item))) {
        paths = parsed as string[]
      }
    }
  } catch {
    // Empty workspace when storage is unreadable.
  }
  return paths.map((path) => starters.get(path) ?? blankFile(path))
}

export function saveCatalog(files: ProjectFile[]): void {
  try {
    localStorage.setItem(catalogKey, JSON.stringify(files.map((file) => file.path)))
  } catch {
    // The editor still has the list in memory.
  }
}
