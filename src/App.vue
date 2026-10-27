<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, shallowReactive, watch, type ComponentPublicInstance } from 'vue'
import CodeEditor from './components/CodeEditor.vue'
import VscodeIcon from './components/VscodeIcon.vue'
import FileTypeIcon from './components/FileTypeIcon.vue'
import ConsolePanel, { type ConsoleLine } from './components/ConsolePanel.vue'
import PreviewWindow from './components/PreviewWindow.vue'
import WorkspaceTerminal from './components/WorkspaceTerminal.vue'
import WelcomePage from './components/WelcomePage.vue'
import EditorEmptyState from './components/EditorEmptyState.vue'
import SettingsEditor from './components/SettingsEditor.vue'
import SearchPanel from './components/SearchPanel.vue'
import type { SearchHit } from './search/contentSearch'
import grainSymbol from './assets/grain-symbol.svg'
import { isAndroidWorkspace, Workspace, type WorkspaceDirEntry } from './plugins/workspace'
import {
  entryPath,
  forgetFile,
  languageFor,
  languageLabel,
  loadCatalog,
  loadFile,
  saveCatalog,
  saveFile,
  validEntryName,
  type ProjectFile,
} from './playground/project'
import { readSandboxEvent } from './playground/sandbox'
import { folderIcon, workspaceFolderIcon } from './icons/material'
import { readRecentItems, rememberRecentItem, type RecentItem } from './recent'
import { STATIC_SERVER_PATH, STATIC_SERVER_SOURCE } from './runtime/staticServer'

type EntryKind = 'file' | 'dir'
interface ExplorerRow {
  kind: EntryKind
  name: string
  path: string
  depth: number
  file?: ProjectFile
}
interface MenuState {
  kind: EntryKind
  name: string
  path: string
  x: number
  y: number
}
interface PromptState {
  mode: 'create' | 'rename' | 'delete'
  kind: EntryKind
  name: string
  path: string
  draft: string
}
interface TerminalTab {
  id: string
  title: string
  /** Workspace-relative cwd for this shell */
  cwd?: string
}
interface FileSnapshot {
  files: ProjectFile[]
  sources: Record<string, string>
  active: string
}

type Pane = 'editor' | 'console' | 'preview'
type RunState = 'idle' | 'running' | 'ok' | 'error'

const wideQuery = window.matchMedia('(min-width: 960px)')
const runShortcut = /Mac|iPhone|iPad/.test(navigator.userAgent) ? '⌘Enter' : 'Ctrl+Enter'
const openShortcut = /Mac|iPhone|iPad/.test(navigator.userAgent) ? '⌘P' : 'Ctrl+P'
const workspaceBarren = computed(() => !(dirChildren.value['']?.length) && !files.value.length)
const canRunProject = computed(() => !workspaceBarren.value)
const editorEmptyMode = computed<'loading' | 'barren' | 'idle' | null>(() => {
  if (workspaceLoading.value) return 'loading'
  if (workspaceError.value) return null
  if (workspaceBarren.value) return 'barren'
  if (!activeFile.value) return 'idle'
  return null
})
const HTML_SERVE_PORT = 5173

const pane = ref<Pane>('editor')
const explorerOpen = ref(wideQuery.matches)
type SidebarView = 'files' | 'search'
const sidebarView = ref<SidebarView>('files')
const searchPanel = ref<InstanceType<typeof SearchPanel> | null>(null)
const panelTab = ref<'console' | 'runtime'>('runtime')
const terminals = ref<TerminalTab[]>([{ id: 'primary', title: 'sh' }])
const activeTerminalId = ref('primary')
const searchScopePath = ref('')
const terminalViews = shallowReactive<Record<string, InstanceType<typeof WorkspaceTerminal> | null>>({})
let terminalSeq = 1
const activeTerminal = computed(() => terminalViews[activeTerminalId.value] ?? null)
const panelOpen = ref(wideQuery.matches)
const previewOpen = ref(false)
const previewServiceUrl = ref('')
const previewWindow = ref<InstanceType<typeof PreviewWindow> | null>(null)
const projectRunning = ref(false)
const projectRunBusy = ref(false)
const panelHeight = ref(readPanelHeight())
const explorerWidth = ref(readPaneWidth('grain.explorerWidth', 220, 160, 480))
const workspaceEl = ref<HTMLElement | null>(null)
const isWide = ref(wideQuery.matches)
const workspaceLoading = ref(isAndroidWorkspace)
const workspaceError = ref('')
const welcomeOpen = ref(true)
const leaveWelcomeOpen = ref(false)
let saveQueue = Promise.resolve()
const files = ref<ProjectFile[]>(loadCatalog())
const diskPaths = ref<string[]>([])
const dirChildren = ref<Record<string, WorkspaceDirEntry[]>>({})
const dirLoading = ref<Record<string, boolean>>({})
const expanded = ref<Record<string, boolean>>({ '': true })
const workspaceName = ref(readWorkspaceName())
const activePath = ref(files.value.some((file) => file.path === entryPath) ? entryPath : (files.value[0]?.path ?? ''))
const recentItems = ref<RecentItem[]>(readRecentItems())
const menu = ref<MenuState | null>(null)
const prompt = ref<PromptState | null>(null)
const promptError = ref('')
const promptInput = ref<HTMLInputElement | null>(null)
const notice = ref('')
let pressTimer = 0
let pressX = 0
let pressY = 0
let suppressClick = false
let noticeTimer = 0
const fileQuery = ref('')
const fileSearchOpen = ref(false)
const fileSearchIndex = ref(0)
const importOpen = ref(false)
const importing = ref(false)
const importFileInput = ref<HTMLInputElement | null>(null)
const importDirInput = ref<HTMLInputElement | null>(null)
let pendingFolderReplace = false
let closeSearchTimer = 0
const sources = reactive<Record<string, string>>({})
const cursor = ref({ line: 1, column: 1 })
const consoleLines = ref<ConsoleLine[]>([])
const runState = ref<RunState>('idle')
let consoleSeq = 0
let runId = 0
let sawFailure = false
let runTimer = 0

for (const file of files.value) sources[file.path] = loadFile(file)

const activeFile = computed(() => files.value.find((file) => file.path === activePath.value) ?? null)
const workspaceFiles = computed(() => files.value.map((file) => ({ path: file.path, content: sources[file.path] ?? '' })))
const reveal = ref<{ path: string; line: number; column: number; id: number } | null>(null)
const fileMatches = computed(() => {
  const query = fileQuery.value.trim().toLowerCase()
  const scope = searchScopePath.value.trim()
  let list = explorerFiles.value
  if (scope) {
    const prefix = scope.endsWith('/') ? scope : `${scope}/`
    list = list.filter((file) => file.path === scope || file.path.startsWith(prefix))
  }
  if (!query) return list
  return list.filter((file) => file.path.toLowerCase().includes(query))
})
const explorerFiles = computed(() => {
  const known = new Set(files.value.map((file) => file.path))
  const extras = diskPaths.value
    .filter((path) => !known.has(path))
    .map((path): ProjectFile => ({ path, language: languageFor(path), runnable: path === entryPath, starter: '' }))
  return [...files.value, ...extras]
})
const explorerRows = computed(() => buildLazyRows(dirChildren.value, expanded.value, workspaceName.value))
const emptyDirs = ref<string[]>([])
const multiSelectOpen = ref(false)
const selectedPaths = ref<string[]>([])
const batchMoving = ref(false)
const moveTargetPath = ref('')
const movePickerOpen = ref(false)
const batchBusy = ref(false)
const selectedCount = computed(() => selectedPaths.value.length)
const moveBreadcrumb = computed(() => {
  const parts = moveTargetPath.value ? moveTargetPath.value.split('/').filter(Boolean) : []
  return [
    { name: workspaceName.value || '工作区', path: '' },
    ...parts.map((name, index) => ({
      name,
      path: parts.slice(0, index + 1).join('/'),
    })),
  ]
})
const moveChildFolders = computed(() => {
  const entries = dirChildren.value[moveTargetPath.value] ?? []
  return entries.filter((entry) => {
    if (entry.kind !== 'dir') return false
    // 不能移到选中项自身或其子目录
    return !selectedPaths.value.some((path) => {
      const row = explorerRows.value.find((item) => item.path === path)
      if (row?.kind !== 'dir') return false
      return entry.path === path || entry.path.startsWith(`${path}/`)
    })
  })
})
const promptTitle = computed(() => {
  const current = prompt.value
  if (!current) return ''
  if (current.mode === 'create') {
    return current.kind === 'dir'
      ? `在 ${current.name} 中新建文件夹`
      : `在 ${current.name} 中新建文件`
  }
  if (current.mode === 'rename') return `重命名 ${current.name}`
  return current.kind === 'dir' ? `删除文件夹 ${current.name}` : `删除 ${current.name}`
})
const promptConfirm = computed(() => {
  if (prompt.value?.mode === 'create') return prompt.value.kind === 'dir' ? '新建文件夹' : '新建文件'
  if (prompt.value?.mode === 'rename') return '重命名'
  return '删除'
})
const editorActive = computed(() => true)
const dockedConsole = computed(() => panelOpen.value)

function readPanelHeight(): number {
  try {
    const stored = Number(localStorage.getItem('grain.panelHeight'))
    if (Number.isFinite(stored) && stored >= 140 && stored <= 900) return stored
  } catch {
    /* 无存储时使用默认高度 */
  }
  return 260
}

function readPaneWidth(key: string, fallback: number, min: number, max: number): number {
  try {
    const stored = Number(localStorage.getItem(key))
    if (Number.isFinite(stored) && stored >= min && stored <= max) return stored
  } catch {
    /* 无存储时使用默认宽度 */
  }
  return fallback
}

function rememberPanelHeight() {
  try {
    localStorage.setItem('grain.panelHeight', String(panelHeight.value))
  } catch {
    /* 忽略无法写入的存储 */
  }
}

function rememberPaneWidth(key: string, width: number) {
  try {
    localStorage.setItem(key, String(width))
  } catch {
    /* 忽略无法写入的存储 */
  }
}

function readWorkspaceName(): string {
  try {
    const name = localStorage.getItem('grain.workspaceName')?.trim()
    if (name) return name
  } catch {
    /* 忽略无法读取的存储 */
  }
  return 'GRAIN-WORKSPACE'
}

function setWorkspaceName(name: string) {
  const next = name.trim() || 'GRAIN-WORKSPACE'
  workspaceName.value = next
  try {
    localStorage.setItem('grain.workspaceName', next)
  } catch {
    /* 忽略无法写入的存储 */
  }
}

function markWorkspaceOpened() {
  try {
    localStorage.setItem('grain.workspaceOpened', '1')
  } catch {
    /* 忽略无法写入的存储 */
  }
  welcomeOpen.value = false
  leaveWelcomeOpen.value = false
}

function askLeaveToWelcome() {
  if (welcomeOpen.value || leaveWelcomeOpen.value) return
  leaveWelcomeOpen.value = true
}

async function confirmLeaveToWelcome() {
  leaveWelcomeOpen.value = false
  if (projectRunning.value) {
    try {
      await stopProjectRun()
    } catch {
      /* 停止失败也继续回到欢迎页 */
    }
  }
  previewOpen.value = false
  try {
    localStorage.removeItem('grain.workspaceOpened')
  } catch {
    /* 忽略无法写入的存储 */
  }
  welcomeOpen.value = true
}

function clampPanelHeight(height: number): number {
  const bounds = workspaceEl.value?.getBoundingClientRect().height ?? window.innerHeight
  const max = Math.max(180, Math.floor(bounds - 120))
  return Math.min(max, Math.max(140, Math.round(height)))
}

function clampPaneWidth(width: number, min: number, reserved: number): number {
  const bounds = workspaceEl.value?.getBoundingClientRect().width ?? window.innerWidth
  const max = Math.max(min, Math.floor(bounds - reserved - 180))
  return Math.min(max, Math.max(min, Math.round(width)))
}

function togglePanel() {
  if (panelOpen.value) {
    panelOpen.value = false
    return
  }
  panelTab.value = 'runtime'
  panelOpen.value = true
}

const settingsOpen = ref(false)

function openSettings() {
  settingsOpen.value = true
  pane.value = 'editor'
  if (!isWide.value) explorerOpen.value = false
}

function closeSettings() {
  settingsOpen.value = false
}

function openInTerminal() {
  const current = menu.value
  menu.value = null
  let cwd = ''
  let title = 'sh'
  if (current) {
    if (current.kind === 'dir') {
      cwd = current.path
      title = current.name || workspaceName.value || 'sh'
    } else if (current.path) {
      cwd = parentOf(current.path)
      const parts = cwd.split('/').filter(Boolean)
      title = parts[parts.length - 1] || workspaceName.value || 'sh'
    }
  }
  searchScopePath.value = cwd
  if (terminals.value.length >= 8) {
    showNotice('最多同时打开 8 个控制台')
    selectTerminal(terminals.value[terminals.value.length - 1]!.id)
  } else {
    terminalSeq += 1
    const id = `term-${terminalSeq}`
    terminals.value.push({ id, title, cwd: cwd || undefined })
    selectTerminal(id)
  }
  panelTab.value = 'runtime'
  panelOpen.value = true
  pane.value = 'editor'
}

const terminalBinders = new Map<string, (el: Element | ComponentPublicInstance | null) => void>()

function bindTerminal(id: string, el: Element | ComponentPublicInstance | null) {
  const view = (el as InstanceType<typeof WorkspaceTerminal> | null) ?? null
  if (terminalViews[id] === view) return
  if (view) terminalViews[id] = view
  else delete terminalViews[id]
}

function terminalBinder(id: string) {
  let binder = terminalBinders.get(id)
  if (!binder) {
    binder = (el) => bindTerminal(id, el)
    terminalBinders.set(id, binder)
  }
  return binder
}

function selectTerminal(id: string) {
  activeTerminalId.value = id
  panelTab.value = 'runtime'
  panelOpen.value = true
}

function addTerminal() {
  if (terminals.value.length >= 8) {
    showNotice('最多同时打开 8 个控制台')
    return
  }
  terminalSeq += 1
  const id = `term-${terminalSeq}`
  const cwd = searchScopePath.value.trim() || undefined
  const title = cwd
    ? (cwd.split('/').filter(Boolean).pop() || workspaceName.value || 'sh')
    : 'sh'
  terminals.value.push({ id, title, cwd })
  selectTerminal(id)
}

function closeTerminal(id: string) {
  if (terminals.value.length <= 1) return
  const index = terminals.value.findIndex((item) => item.id === id)
  if (index < 0) return
  terminals.value.splice(index, 1)
  delete terminalViews[id]
  if (activeTerminalId.value === id) {
    const next = terminals.value[Math.max(0, index - 1)]
    activeTerminalId.value = next.id
    panelTab.value = 'runtime'
  }
}

function togglePreview() {
  previewOpen.value = !previewOpen.value
  if (previewOpen.value) pane.value = 'editor'
}

function openLocalService(url: string) {
  previewServiceUrl.value = url
  previewOpen.value = true
  pane.value = 'editor'
  void nextTick(() => previewWindow.value?.openAddress(url))
}

function showProject() {
  explorerOpen.value = true
  sidebarView.value = 'files'
  pane.value = 'editor'
}

function showFilesSidebar() {
  settingsOpen.value = false
  sidebarView.value = 'files'
  explorerOpen.value = true
  pane.value = 'editor'
}

function showSearchSidebar() {
  settingsOpen.value = false
  sidebarView.value = 'search'
  explorerOpen.value = true
  pane.value = 'editor'
  void nextTick(() => searchPanel.value?.focusQuery())
}

function openSearchHit(hit: SearchHit) {
  openFile({
    path: hit.path,
    language: languageFor(hit.path),
    runnable: hit.path === entryPath,
    starter: '',
  })
  reveal.value = {
    path: hit.path,
    line: hit.line,
    column: hit.column,
    id: Date.now(),
  }
  if (!isWide.value) explorerOpen.value = false
}

function onPanelResizeDown(event: PointerEvent) {
  if (event.button !== 0) return
  event.preventDefault()
  const handle = event.currentTarget as HTMLElement
  try {
    handle.setPointerCapture(event.pointerId)
  } catch {
    /* 某些 WebView 在指针尚未激活时会拒绝捕获 */
  }
  const startY = event.clientY
  const startHeight = panelHeight.value
  const move = (next: PointerEvent) => {
    panelHeight.value = clampPanelHeight(startHeight + (startY - next.clientY))
  }
  const stop = () => {
    handle.removeEventListener('pointermove', move)
    handle.removeEventListener('pointerup', stop)
    handle.removeEventListener('pointercancel', stop)
    rememberPanelHeight()
  }
  handle.addEventListener('pointermove', move)
  handle.addEventListener('pointerup', stop)
  handle.addEventListener('pointercancel', stop)
}

function onPanelResizeKey(event: KeyboardEvent) {
  if (event.key !== 'ArrowUp' && event.key !== 'ArrowDown') return
  event.preventDefault()
  const delta = event.key === 'ArrowUp' ? 24 : -24
  panelHeight.value = clampPanelHeight(panelHeight.value + delta)
  rememberPanelHeight()
}

function onColumnResizeDown(event: PointerEvent) {
  if (event.button !== 0) return
  event.preventDefault()
  const handle = event.currentTarget as HTMLElement
  try {
    handle.setPointerCapture(event.pointerId)
  } catch {
    /* 某些 WebView 在指针尚未激活时会拒绝捕获 */
  }
  const startX = event.clientX
  const startWidth = explorerWidth.value
  const move = (next: PointerEvent) => {
    explorerWidth.value = clampPaneWidth(startWidth + (next.clientX - startX), 160, 0)
  }
  const stop = () => {
    handle.removeEventListener('pointermove', move)
    handle.removeEventListener('pointerup', stop)
    handle.removeEventListener('pointercancel', stop)
    rememberPaneWidth('grain.explorerWidth', explorerWidth.value)
  }
  handle.addEventListener('pointermove', move)
  handle.addEventListener('pointerup', stop)
  handle.addEventListener('pointercancel', stop)
}

function onColumnResizeKey(event: KeyboardEvent) {
  if (event.key !== 'ArrowLeft' && event.key !== 'ArrowRight') return
  event.preventDefault()
  const delta = event.key === 'ArrowRight' ? 24 : -24
  explorerWidth.value = clampPaneWidth(explorerWidth.value + delta, 160, 0)
  rememberPaneWidth('grain.explorerWidth', explorerWidth.value)
}

function stamp(): string {
  return new Date().toLocaleTimeString('zh-CN', { hour12: false })
}

function pushConsole(level: ConsoleLine['level'], text: string) {
  consoleLines.value = [
    ...consoleLines.value.slice(-299),
    { id: ++consoleSeq, time: stamp(), level, text },
  ]
}

function onWideChange() {
  isWide.value = wideQuery.matches
}

function toggleExplorer() {
  if (explorerOpen.value && sidebarView.value === 'files') {
    explorerOpen.value = false
    return
  }
  showFilesSidebar()
}

function onCursor(position: { line: number; column: number }) {
  cursor.value = position
}

function onNavigate(target: { path: string; line: number; column: number }) {
  const file = files.value.find((item) => item.path === target.path)
  if (!file) return
  reveal.value = { ...target, id: Date.now() }
  openFile(file)
}

function onChange(value: string) {
  if (!activePath.value) return
  sources[activePath.value] = value
  saveFile(activePath.value, value)
  if (isAndroidWorkspace) {
    const path = activePath.value
    recentWrites.add(path)
    window.setTimeout(() => recentWrites.delete(path), 1500)
    saveQueue = saveQueue.then(() => Workspace.writeFile({ path, content: value }))
      .catch((error: unknown) => pushConsole('error', `设备保存失败：${String(error)}`))
  }
}

async function prepareWorkspace() {
  if (!isAndroidWorkspace) return
  if (workspaceLoading.value) throw new Error('工作区仍在加载，请稍后执行')
  if (workspaceError.value) throw new Error(workspaceError.value)
  await saveQueue
  for (const file of files.value) {
    if (!(file.path in sources)) continue
    await Workspace.writeFile({ path: file.path, content: sources[file.path] ?? '' })
  }
}

const sourceLoads = new Set<string>()

function ensureSource(path: string) {
  if (!isAndroidWorkspace || path in sources || sourceLoads.has(path)) return
  sourceLoads.add(path)
  void Workspace.readFile({ path }).then((result) => {
    if (path in sources) return
    if (!result.exists) return
    sources[path] = result.content ?? ''
    saveFile(path, sources[path])
  }).catch((error: unknown) => {
    pushConsole('error', `读取 ${path} 失败：${String(error)}`)
  }).finally(() => {
    sourceLoads.delete(path)
  })
}

function trackRecent(item: Omit<RecentItem, 'at'> & { at?: number }) {
  recentItems.value = rememberRecentItem(item)
}

function openFile(file: ProjectFile) {
  settingsOpen.value = false
  if (!files.value.some((item) => item.path === file.path)) {
    files.value = [...files.value, file]
    saveCatalog(files.value)
  }
  activePath.value = file.path
  rememberLastOpenedPath(file.path)
  void expandToPath(file.path)
  ensureSource(file.path)
  if (!isWide.value) {
    pane.value = 'editor'
    explorerOpen.value = false
  }
}

function openFileSearch() {
  window.clearTimeout(closeSearchTimer)
  fileSearchOpen.value = true
  const current = fileMatches.value.findIndex((file) => file.path === activePath.value)
  fileSearchIndex.value = current >= 0 ? current : 0
}

function onFileQuery() {
  window.clearTimeout(closeSearchTimer)
  fileSearchOpen.value = true
  fileSearchIndex.value = 0
}

function closeFileSearch() {
  window.clearTimeout(closeSearchTimer)
  fileSearchOpen.value = false
  fileQuery.value = ''
}

function scheduleCloseFileSearch() {
  window.clearTimeout(closeSearchTimer)
  closeSearchTimer = window.setTimeout(closeFileSearch, 160)
}

function chooseSearchedFile(file: ProjectFile) {
  window.clearTimeout(closeSearchTimer)
  openFile(file)
  closeFileSearch()
}

function onFileSearchKeydown(event: KeyboardEvent) {
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    fileSearchOpen.value = true
    const last = Math.max(fileMatches.value.length - 1, 0)
    fileSearchIndex.value = Math.min(fileSearchIndex.value + 1, last)
    return
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    fileSearchIndex.value = Math.max(fileSearchIndex.value - 1, 0)
    return
  }
  if (event.key === 'Enter') {
    event.preventDefault()
    const file = fileMatches.value[fileSearchIndex.value]
    if (file) chooseSearchedFile(file)
    return
  }
  if (event.key === 'Escape') {
    closeFileSearch()
    ;(event.target as HTMLInputElement).blur()
  }
}

function clearConsole() {
  consoleLines.value = []
}

function finishRun(failed: boolean) {
  window.clearTimeout(runTimer)
  if (runState.value !== 'running') return
  runState.value = failed || sawFailure ? 'error' : 'ok'
}

function onSandboxMessage(event: MessageEvent) {
  const frame = document.querySelector<HTMLIFrameElement>('iframe.preview-frame')
  if (!frame || event.source !== frame.contentWindow) return
  const payload = readSandboxEvent(event.data)
  if (!payload || payload.runId !== runId) return

  if (payload.kind === 'log') {
    pushConsole(payload.level ?? 'log', payload.text ?? '')
    return
  }
  if (payload.kind === 'error' || payload.kind === 'rejection') {
    sawFailure = true
    runState.value = 'error'
    const text = payload.text ?? '未知错误'
    pushConsole('error', payload.kind === 'rejection' ? `未处理的 Promise 拒绝: ${text}` : text)
    return
  }
  if (payload.kind === 'dom') return
  finishRun(sawFailure)
}

async function readPackageJson(): Promise<Record<string, unknown> | null> {
  let raw = sources['package.json']
  if ((raw == null || raw === '') && isAndroidWorkspace) {
    try {
      const file = await Workspace.readFile({ path: 'package.json' })
      if (file.exists && typeof file.content === 'string') {
        raw = file.content
        sources['package.json'] = file.content
      }
    } catch {
      /* 读取失败时继续尝试本地缓存 */
    }
  }
  if (raw == null || raw === '') {
    try {
      raw = loadFile({ path: 'package.json', language: 'json', runnable: false, starter: '' })
    } catch {
      return null
    }
  }
  try {
    return JSON.parse(raw) as Record<string, unknown>
  } catch {
    return null
  }
}

function pickStartScript(pkg: Record<string, unknown> | null): string | null {
  const scripts = pkg?.scripts
  if (!scripts || typeof scripts !== 'object') return null
  const map = scripts as Record<string, unknown>
  for (const name of ['dev', 'serve', 'start'] as const) {
    if (typeof map[name] === 'string' && map[name]) return name
  }
  return null
}

function rootFileNames(): string[] {
  const names = new Set<string>()
  for (const file of files.value) {
    if (!file.path.includes('/')) names.add(file.path)
  }
  for (const path of diskPaths.value) {
    if (!path.includes('/')) names.add(path)
  }
  for (const entry of dirChildren.value[''] ?? []) {
    if (entry.kind === 'file') names.add(entry.name)
  }
  return [...names]
}

async function findHtmlEntry(): Promise<string | null> {
  await ensureDirLoaded('', true)
  const names = rootFileNames()
  if (names.includes('index.html')) return 'index.html'
  const html = names
    .filter((name) => /\.html?$/i.test(name))
    .sort((left, right) => left.localeCompare(right, 'zh-CN'))
  return html[0] ?? null
}

function htmlEntryUrl(entry: string): string {
  const path = entry === 'index.html' ? '/' : `/${entry.split('/').map(encodeURIComponent).join('/')}`
  return `http://127.0.0.1:${HTML_SERVE_PORT}${path}`
}

async function waitForLocalUrl(url: string, attempts = 80): Promise<boolean> {
  for (let attempt = 0; attempt < attempts; attempt += 1) {
    try {
      if (isAndroidWorkspace) {
        const result = await Workspace.fetchUrl({ url })
        if (result.status > 0 && result.status < 500) return true
      } else {
        const response = await fetch(url, { method: 'GET', cache: 'no-store' })
        if (response.status > 0) return true
      }
    } catch {
      /* 服务尚未就绪 */
    }
    await new Promise((resolve) => window.setTimeout(resolve, 250))
  }
  return false
}

async function ensureStaticServerScript(): Promise<void> {
  if (isAndroidWorkspace) {
    await Workspace.writeFile({ path: STATIC_SERVER_PATH, content: STATIC_SERVER_SOURCE })
    return
  }
  // 浏览器/本机开发：尽量写入工作区缓存；终端侧若已有文件也可直接跑
  try {
    sources[STATIC_SERVER_PATH] = STATIC_SERVER_SOURCE
    saveFile(STATIC_SERVER_PATH, STATIC_SERVER_SOURCE)
  } catch {
    /* ignore */
  }
}

async function waitTerminalReady() {
  panelTab.value = 'runtime'
  panelOpen.value = true
  pane.value = 'editor'
  for (let attempt = 0; attempt < 40; attempt += 1) {
    await nextTick()
    const view = activeTerminal.value
    if (view?.ready) return view
    await new Promise((resolve) => window.setTimeout(resolve, 100))
  }
  return activeTerminal.value
}

async function stopProjectRun() {
  projectRunBusy.value = true
  try {
    panelTab.value = 'runtime'
    panelOpen.value = true
    const view = activeTerminal.value
    if (view) await view.interrupt('已停止项目')
    else showNotice('没有可停止的终端会话')
  } finally {
    projectRunning.value = false
    projectRunBusy.value = false
  }
}

async function startHtmlProject(entry: string) {
  const view = await waitTerminalReady()
  if (!view?.ready) {
    showNotice('终端还没准备好，稍后再试')
    return false
  }
  projectRunning.value = true
  const url = htmlEntryUrl(entry)
  showNotice(`正在启动静态服务（入口 ${entry}）…`)
  try {
    await ensureStaticServerScript()
  } catch (error) {
    showNotice(`写入静态服务脚本失败：${errorText(error)}`)
    projectRunning.value = false
    return false
  }

  // 先清掉可能占用端口的旧进程，再启动内置 Node 静态服务
  await view.runCommand(`node ${STATIC_SERVER_PATH} ${HTML_SERVE_PORT} 127.0.0.1`)
  const ready = await waitForLocalUrl(url)
  if (!ready) {
    // 回退：允许下载 serve 包再启动
    showNotice('内置服务未就绪，正在下载 serve…')
    await view.interrupt('切换到 serve')
    await new Promise((resolve) => window.setTimeout(resolve, 400))
    await view.runCommand(`npm install --no-save serve@14.2.4 && npx serve -l ${HTML_SERVE_PORT} --no-port-switching .`)
    const fallbackReady = await waitForLocalUrl(url)
    if (!fallbackReady) {
      showNotice('静态服务启动超时，请查看终端输出')
      return true
    }
  }

  showNotice(`服务已就绪，正在打开 ${url}`)
  openLocalService(url)
  return true
}

async function startProjectRun() {
  if (projectRunBusy.value || projectRunning.value) return
  if (!canRunProject.value) {
    showNotice('工作区是空的，先添加文件或打开文件夹')
    return
  }
  projectRunBusy.value = true
  try {
    const script = pickStartScript(await readPackageJson())
    if (script) {
      const view = await waitTerminalReady()
      if (!view?.ready) {
        showNotice('终端还没准备好，稍后再试')
        return
      }
      projectRunning.value = true
      await view.runCommand(`npm run ${script}`)
      return
    }

    const htmlEntry = await findHtmlEntry()
    if (htmlEntry) {
      await startHtmlProject(htmlEntry)
      return
    }

    panelTab.value = 'runtime'
    panelOpen.value = true
    showNotice('没有可启动的入口：需要 package.json 的 dev/serve/start，或根目录 HTML')
  } catch (e) {
    showNotice(e instanceof Error ? e.message : String(e))
    projectRunning.value = false
  } finally {
    projectRunBusy.value = false
  }
}

async function toggleProjectRun() {
  if (projectRunning.value) await stopProjectRun()
  else await startProjectRun()
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    if (leaveWelcomeOpen.value) leaveWelcomeOpen.value = false
    else if (prompt.value) prompt.value = null
    else menu.value = null
    return
  }
  if (prompt.value || menu.value || leaveWelcomeOpen.value) return
  if (event.key !== 'Enter' || !(event.metaKey || event.ctrlKey)) return
  event.preventDefault()
  if (!projectRunning.value && !canRunProject.value) return
  void toggleProjectRun()
}

let workspaceRefreshTimer = 0
let workspacePollTimer = 0
let workspaceListing = false
let filesListener: { remove: () => Promise<void> } | undefined
const recentWrites = new Set<string>()
const listingDirs = new Set<string>()

function listedPath(path: string): boolean {
  if (!path || path.startsWith('/') || path.endsWith('/')) return false
  return path.split('/').every((part) => (
    part
    && part !== '.'
    && part !== '..'
    && part !== '.git'
    && part !== '.grain'
  ))
}

function isHiddenWorkspaceEntry(entry: { name: string; path: string }): boolean {
  if (entry.name === '.grain' || entry.name === '.git') return true
  return entry.path.split('/').some((part) => part === '.grain' || part === '.git')
}

function readLastOpenedPath(): string {
  try {
    const path = localStorage.getItem('grain.lastOpenedPath') ?? ''
    return listedPath(path) || path === '' ? path : ''
  } catch {
    return ''
  }
}

function rememberLastOpenedPath(path: string) {
  try {
    localStorage.setItem('grain.lastOpenedPath', path)
  } catch {
    /* 忽略无法写入的存储 */
  }
}

function synthesizeDirEntries(dir: string): WorkspaceDirEntry[] {
  const prefix = dir ? `${dir}/` : ''
  const dirs = new Set<string>()
  const files: WorkspaceDirEntry[] = []
  const seenFiles = new Set<string>()
  for (const empty of emptyDirs.value) {
    if (dir) {
      if (!empty.startsWith(prefix)) continue
      const rest = empty.slice(prefix.length)
      const slash = rest.indexOf('/')
      if (slash < 0 && rest) dirs.add(rest)
      else if (slash > 0) dirs.add(rest.slice(0, slash))
    } else {
      const slash = empty.indexOf('/')
      if (slash < 0 && empty) dirs.add(empty)
      else if (slash > 0) dirs.add(empty.slice(0, slash))
    }
  }
  for (const file of explorerFiles.value) {
    if (dir) {
      if (file.path !== dir && !file.path.startsWith(prefix)) continue
      if (file.path === dir) continue
      const rest = file.path.slice(prefix.length)
      const slash = rest.indexOf('/')
      if (slash < 0) {
        if (!seenFiles.has(file.path)) {
          seenFiles.add(file.path)
          files.push({ name: rest, path: file.path, kind: 'file' })
        }
      } else {
        dirs.add(rest.slice(0, slash))
      }
    } else {
      const slash = file.path.indexOf('/')
      if (slash < 0) {
        if (!seenFiles.has(file.path)) {
          seenFiles.add(file.path)
          files.push({ name: file.path, path: file.path, kind: 'file' })
        }
      } else {
        dirs.add(file.path.slice(0, slash))
      }
    }
  }
  const entries: WorkspaceDirEntry[] = [...dirs].sort((a, b) => a.localeCompare(b, 'zh-CN')).map((name) => ({
    name,
    path: dir ? `${dir}/${name}` : name,
    kind: 'dir' as const,
  }))
  entries.push(...files.sort((a, b) => a.name.localeCompare(b.name, 'zh-CN')))
  return entries
}

function rebuildDiskPathsFromTree() {
  const next: string[] = []
  for (const [dir, entries] of Object.entries(dirChildren.value)) {
    for (const entry of entries) {
      if (entry.kind === 'file') next.push(entry.path)
      else if (!(entry.path in dirChildren.value)) {
        /* 未展开的目录不展开进 diskPaths */
      }
    }
    void dir
  }
  diskPaths.value = next
}

async function ensureDirLoaded(dir: string, force = false) {
  if (!force && dirChildren.value[dir]) return
  if (listingDirs.has(dir)) return
  listingDirs.add(dir)
  dirLoading.value = { ...dirLoading.value, [dir]: true }
  try {
    let entries: WorkspaceDirEntry[]
    if (isAndroidWorkspace) {
      const result = await Workspace.listDirectory({ path: dir })
      entries = result.entries
        .filter((entry) => !isHiddenWorkspaceEntry(entry))
        .filter((entry) => entry.kind === 'dir' ? listedPath(entry.path) || Boolean(entry.name) : listedPath(entry.path))
        .filter((entry) => entry.kind === 'dir' || listedPath(entry.path))
    } else {
      entries = synthesizeDirEntries(dir).filter((entry) => !isHiddenWorkspaceEntry(entry))
    }
    entries = [...entries].sort((left, right) => {
      if (left.kind !== right.kind) return left.kind === 'dir' ? -1 : 1
      return left.name.localeCompare(right.name, 'zh-CN')
    })
    const nextTree: Record<string, WorkspaceDirEntry[]> = { ...dirChildren.value, [dir]: entries }
    // 丢掉已不在磁盘上的子目录缓存，避免占名误判
    const live = new Set(entries.filter((entry) => entry.kind === 'dir').map((entry) => entry.path))
    const prefix = dir ? `${dir}/` : ''
    for (const key of Object.keys(nextTree)) {
      if (!key || key === dir) continue
      const under = dir ? key.startsWith(prefix) : !key.includes('/')
      if (!under) continue
      const rest = dir ? key.slice(prefix.length) : key
      if (!rest || rest.includes('/')) continue
      if (!live.has(key) && !emptyDirs.value.includes(key)) delete nextTree[key]
    }
    dirChildren.value = nextTree
    // 同步清理已不存在的空目录占位
    if (emptyDirs.value.length) {
      const kept = emptyDirs.value.filter((path) => {
        const parent = parentOf(path)
        if (parent !== dir) return true
        return live.has(path) || entries.some((entry) => entry.path === path)
      })
      if (kept.length !== emptyDirs.value.length) emptyDirs.value = kept
    }
    rebuildDiskPathsFromTree()
  } catch (error) {
    if (!workspaceError.value) pushConsole('error', `读取目录失败：${String(error)}`)
  } finally {
    listingDirs.delete(dir)
    const next = { ...dirLoading.value }
    delete next[dir]
    dirLoading.value = next
  }
}

async function expandToPath(filePath: string) {
  const parts = filePath.split('/').filter(Boolean)
  const next: Record<string, boolean> = { ...expanded.value, '': true }
  let dir = ''
  await ensureDirLoaded('')
  for (let index = 0; index < parts.length - 1; index += 1) {
    dir = dir ? `${dir}/${parts[index]}` : parts[index]!
    next[dir] = true
    await ensureDirLoaded(dir)
  }
  expanded.value = next
}

function resetExplorerTree() {
  dirChildren.value = {}
  dirLoading.value = {}
  expanded.value = { '': true }
  diskPaths.value = []
}

async function refreshWorkspaceTree() {
  if (workspaceListing) return
  workspaceListing = true
  try {
    const loaded = Object.keys(dirChildren.value)
    if (!loaded.length) {
      await ensureDirLoaded('', true)
    } else {
      for (const dir of loaded) {
        if (expanded.value[dir] || dir === '') await ensureDirLoaded(dir, true)
      }
    }
  } finally {
    workspaceListing = false
  }
}

function scheduleWorkspaceRefresh(paths: string[] = []) {
  window.clearTimeout(workspaceRefreshTimer)
  workspaceRefreshTimer = window.setTimeout(() => {
    if (!paths.length) {
      void refreshWorkspaceTree()
      return
    }
    const parents = new Set<string>()
    for (const path of paths) {
      parents.add(parentOf(path))
      if (path.includes('/')) parents.add(path)
    }
    void (async () => {
      for (const dir of parents) {
        if (dir in dirChildren.value || dir === '' || expanded.value[dir]) await ensureDirLoaded(dir, true)
      }
    })()
  }, 300)
}

function reloadWatchedFile(path: string) {
  if (!listedPath(path) || !(path in sources) || recentWrites.has(path)) return
  void Workspace.readFile({ path }).then((result) => {
    if (!result.exists || recentWrites.has(path)) return
    const next = result.content ?? ''
    if (sources[path] === next) return
    sources[path] = next
    saveFile(path, next)
  }).catch(() => undefined)
}

async function startWorkspaceWatch() {
  filesListener = await Workspace.addListener('files', (event) => {
    const events = event.events ?? []
    if (!events.length) return
    scheduleWorkspaceRefresh(events.map((item) => item.path))
    for (const item of events) {
      if (item.type === 'update') reloadWatchedFile(item.path)
    }
  })
  await Workspace.watchWorkspace()
}

onMounted(async () => {
  wideQuery.addEventListener('change', onWideChange)
  window.addEventListener('keydown', onKeydown)
  window.addEventListener('message', onSandboxMessage)
  if (isAndroidWorkspace) {
    try {
      for (const file of files.value) {
        const result = await Workspace.readFile({ path: file.path })
        if (result.exists) sources[file.path] = result.content ?? ''
        else await Workspace.writeFile({ path: file.path, content: sources[file.path] ?? '' })
      }
      await ensureDirLoaded('', true)
      if (!(await maybeOpenSingleProjectFolder())) await syncWorkspaceNameFromPackage()
      const resume = activePath.value || readLastOpenedPath()
      if (resume) await expandToPath(resume)
    } catch (error) {
      workspaceError.value = `设备工作区初始化失败：${String(error)}`
      pushConsole('error', workspaceError.value)
    }
    finally { workspaceLoading.value = false }
    try {
      await startWorkspaceWatch()
    } catch (error) {
      pushConsole('error', `文件监听没有启动，改为定时同步：${error instanceof Error ? error.message : String(error)}`)
      if (!workspacePollTimer) workspacePollTimer = window.setInterval(() => { void refreshWorkspaceTree() }, 2000)
    }
  } else {
    await ensureDirLoaded('', true)
    const resume = activePath.value || readLastOpenedPath()
    if (resume) await expandToPath(resume)
  }
  refreshRecentItems()
})

onBeforeUnmount(() => {
  wideQuery.removeEventListener('change', onWideChange)
  window.removeEventListener('keydown', onKeydown)
  window.removeEventListener('message', onSandboxMessage)
  window.clearTimeout(runTimer)
  window.clearTimeout(closeSearchTimer)
  window.clearTimeout(pressTimer)
  window.clearTimeout(noticeTimer)
  window.clearTimeout(workspaceRefreshTimer)
  window.clearInterval(workspacePollTimer)
  void filesListener?.remove()
})

function buildLazyRows(
  tree: Record<string, WorkspaceDirEntry[]>,
  open: Record<string, boolean>,
  _rootName: string,
): ExplorerRow[] {
  // 根文件夹显示在标题栏，树里只列其子项
  const rows: ExplorerRow[] = []
  const walk = (dir: string, depth: number) => {
    if (!open[dir]) return
    const entries = tree[dir]
    if (!entries) return
    for (const entry of entries) {
      if (entry.kind === 'dir') {
        rows.push({ kind: 'dir', name: entry.name, path: entry.path, depth })
        walk(entry.path, depth + 1)
        continue
      }
      const file = files.value.find((item) => item.path === entry.path)
        ?? { path: entry.path, language: languageFor(entry.path), runnable: entry.path === entryPath, starter: '' }
      rows.push({
        kind: 'file',
        name: entry.name,
        path: entry.path,
        depth,
        file,
      })
    }
  }
  walk('', 0)
  return rows
}

function parentOf(path: string): string {
  const index = path.lastIndexOf('/')
  return index < 0 ? '' : path.slice(0, index)
}

function joinPath(dir: string, name: string): string {
  return dir ? `${dir}/${name}` : name
}

function pathOccupied(path: string): boolean {
  const prefix = `${path}/`
  if (files.value.some((file) => file.path === path || file.path.startsWith(prefix))) return true
  if (diskPaths.value.some((item) => item === path || item.startsWith(prefix))) return true
  if (emptyDirs.value.includes(path)) return true
  const parent = parentOf(path)
  // 只认父目录列表里的实际条目；不要用 `path in dirChildren`
  //（刷新后可能留下过期的目录缓存键，会误报「已有同名」）
  return (dirChildren.value[parent] ?? []).some((entry) => entry.path === path)
}

function errorText(error: unknown): string {
  if (error instanceof Error) return error.message
  if (error && typeof error === 'object' && 'message' in error) return String(error.message)
  return String(error)
}

function takeSnapshot(): FileSnapshot {
  return {
    files: files.value.map((file) => ({ ...file })),
    sources: { ...sources },
    active: activePath.value,
  }
}

function restoreSnapshot(snapshot: FileSnapshot, added: string[]) {
  for (const path of added) forgetFile(path)
  files.value = snapshot.files
  for (const key of Object.keys(sources)) delete sources[key]
  Object.assign(sources, snapshot.sources)
  activePath.value = snapshot.active
  saveCatalog(snapshot.files)
  for (const file of snapshot.files) saveFile(file.path, snapshot.sources[file.path] ?? '')
}

function retarget(file: ProjectFile, path: string): ProjectFile {
  return { ...file, path, language: languageFor(path), runnable: path === entryPath }
}

function showNotice(text: string) {
  notice.value = text
  window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => { notice.value = '' }, 1600)
}

function importablePath(raw: string): string {
  const parts = raw.replaceAll('\\', '/').replace(/^\/+/, '').split('/')
  const clean = parts.filter((part) => part && part !== '.' && part !== '..')
  if (!clean.length || clean.some((part) => part === 'node_modules' || part === '.git' || /[\u0000]/.test(part))) return ''
  return clean.join('/')
}

function sharedRootName(entries: { path: string; content: string }[]): string {
  if (entries.length === 0) return ''
  const roots = new Set(entries.map((entry) => entry.path.split('/')[0]).filter(Boolean))
  if (roots.size !== 1) return ''
  if (!entries.every((entry) => entry.path.includes('/'))) return ''
  return [...roots][0] ?? ''
}

function stripSharedRoot(entries: { path: string; content: string }[]): { path: string; content: string }[] {
  if (entries.length === 0) return entries
  const root = sharedRootName(entries)
  if (!root) return entries
  return entries.map((entry) => ({
    path: entry.path.split('/').slice(1).join('/'),
    content: entry.content,
  }))
}

async function clearWorkspaceFiles() {
  const previous = [...files.value]
  files.value = []
  activePath.value = ''
  resetExplorerTree()
  for (const path of Object.keys(sources)) delete sources[path]
  for (const file of previous) forgetFile(file.path)
  saveCatalog([])
  if (isAndroidWorkspace) {
    try {
      await Workspace.clearWorkspace()
    } catch {
      for (const file of previous) {
        try {
          await Workspace.deletePath({ path: file.path })
        } catch {
          /* 旧文件可能已不存在 */
        }
      }
    }
  }
}

function retargetCatalogToDirectory(path: string) {
  const root = path.replace(/\/$/, '')
  const slash = `${root}/`
  const nextFiles: ProjectFile[] = []
  const seen = new Set<string>()
  const candidates = [
    ...files.value,
    ...diskPaths.value
      .filter((item) => !files.value.some((file) => file.path === item))
      .map((item): ProjectFile => ({ path: item, language: languageFor(item), runnable: item === entryPath, starter: '' })),
  ]
  for (const file of candidates) {
    if (file.path !== root && !file.path.startsWith(slash)) continue
    const next = file.path === root ? '' : file.path.slice(slash.length)
    if (!next || seen.has(next)) continue
    seen.add(next)
    const content = sources[file.path]
    if (content !== undefined) {
      sources[next] = content
      delete sources[file.path]
      saveFile(next, content)
      forgetFile(file.path)
    }
    nextFiles.push({ path: next, language: languageFor(next), runnable: next === entryPath, starter: '' })
  }
  for (const key of Object.keys(sources)) {
    if (seen.has(key)) continue
    delete sources[key]
    forgetFile(key)
  }
  files.value = nextFiles
  activePath.value = nextFiles.find((file) => file.path === entryPath)?.path ?? nextFiles[0]?.path ?? ''
  saveCatalog(files.value)
  resetExplorerTree()
}

async function openDirectoryAsWorkspace(path: string, name: string) {
  if (!path) return
  await saveQueue
  if (isAndroidWorkspace) await Workspace.promoteDirectory({ path })
  retargetCatalogToDirectory(path)
  await ensureDirLoaded('', true)
  setWorkspaceName(name)
  markWorkspaceOpened()
  if (activePath.value) {
    const current = files.value.find((file) => file.path === activePath.value)
      ?? { path: activePath.value, language: languageFor(activePath.value), runnable: activePath.value === entryPath, starter: '' }
    openFile(current)
  }
  trackRecent({ kind: 'folder', name, path })
  showNotice(`已打开文件夹 ${name}`)
}

async function maybeOpenSingleProjectFolder() {
  await ensureDirLoaded('')
  const rootEntries = dirChildren.value[''] ?? []
  if (rootEntries.some((entry) => entry.path === 'package.json')) return false
  const tops = rootEntries.filter((entry) => entry.kind === 'dir')
  if (tops.length !== 1) return false
  const name = tops[0]!.name
  await ensureDirLoaded(name)
  const nested = dirChildren.value[name] ?? []
  if (!nested.some((entry) => entry.path === `${name}/package.json`)) return false
  await openDirectoryAsWorkspace(name, name)
  return true
}

async function syncWorkspaceNameFromPackage() {
  if (workspaceName.value !== 'GRAIN-WORKSPACE') return
  const hasPackage = diskPaths.value.includes('package.json')
    || files.value.some((file) => file.path === 'package.json')
  if (!hasPackage) return
  try {
    let raw = sources['package.json']
    if (raw === undefined && isAndroidWorkspace) {
      const result = await Workspace.readFile({ path: 'package.json' })
      if (!result.exists) return
      raw = result.content ?? ''
    }
    if (!raw) return
    const pkg = JSON.parse(raw) as { name?: unknown }
    if (typeof pkg.name === 'string' && pkg.name.trim()) setWorkspaceName(pkg.name.trim())
  } catch {
    /* package.json 无效时保留默认名称 */
  }
}

async function adoptImports(
  entries: { path: string; content: string }[],
  options: { replace?: boolean; name?: string } = {},
) {
  const accepted = entries.flatMap((entry) => {
    const path = importablePath(entry.path)
    return path ? [{ path, content: entry.content }] : []
  })
  if (options.replace) {
    await clearWorkspaceFiles()
  } else if (!accepted.length) {
    showNotice('没有可导入的文本文件')
    return false
  }
  for (const entry of accepted) {
    if (!files.value.some((file) => file.path === entry.path)) {
      files.value = [...files.value, { path: entry.path, language: languageFor(entry.path), runnable: entry.path === entryPath, starter: '' }]
    }
    sources[entry.path] = entry.content
    saveFile(entry.path, entry.content)
    expandDirectory(parentOf(entry.path))
    if (isAndroidWorkspace) await Workspace.writeFile({ path: entry.path, content: entry.content })
  }
  saveCatalog(files.value)
  if (options.replace && options.name) setWorkspaceName(options.name)
  const preferred = files.value.find((file) => file.path === entryPath)
    ?? (accepted[0] ? files.value.find((file) => file.path === accepted[0].path) : undefined)
    ?? files.value[0]
  if (preferred) openFile(preferred)
  else activePath.value = ''
  // 最近打开只记录「打开文件夹 / 打开文件」入口，不记录资源管理器内点击
  if (options.replace && options.name) {
    trackRecent({ kind: 'folder', name: options.name, path: options.name })
  } else if (!options.replace) {
    for (const entry of accepted) {
      trackRecent({
        kind: 'file',
        name: entry.path.split('/').pop() || entry.path,
        path: entry.path,
      })
    }
  }
  if (options.replace) {
    resetExplorerTree()
    await ensureDirLoaded('', true)
    if (preferred) await expandToPath(preferred.path)
    showNotice(accepted.length ? `已打开 ${accepted.length} 个文件` : '已打开空文件夹')
  } else {
    const parents = new Set(accepted.map((entry) => parentOf(entry.path)))
    for (const dir of parents) await ensureDirLoaded(dir, true)
    showNotice(`已导入 ${accepted.length} 个文件`)
  }
  return true
}

async function readPickedFiles(list: File[]): Promise<{ entries: { path: string; content: string }[]; name: string }> {
  const entries: { path: string; content: string }[] = []
  for (const file of list) {
    if (file.size > 512 * 1024) continue
    const relative = file.webkitRelativePath || file.name
    if (relative.split('/').some((part) => part === 'node_modules' || part === '.git')) continue
    const content = await file.text()
    if (content.includes('\0')) continue
    entries.push({ path: relative, content })
  }
  const name = sharedRootName(entries)
  return { entries: stripSharedRoot(entries), name }
}

async function startImport(kind: 'files' | 'folder') {
  importOpen.value = false
  if (importing.value || workspaceLoading.value) return
  importing.value = true
  try {
    if (isAndroidWorkspace) {
      if (kind === 'folder') {
        const picked = await Workspace.pickDirectory()
        if (picked.opened === false) return
        const opened = await adoptImports(stripSharedRoot(picked.files ?? []), {
          replace: true,
          name: picked.name?.trim() || sharedRootName(picked.files ?? []) || 'workspace',
        })
        if (opened) markWorkspaceOpened()
        return
      }
      const picked = await Workspace.pickFiles()
      await adoptImports(picked.files ?? [])
      return
    }
    pendingFolderReplace = kind === 'folder'
    const input = kind === 'folder' ? importDirInput.value : importFileInput.value
    input?.click()
  } catch (error) {
    showNotice(errorText(error))
  } finally {
    importing.value = false
  }
}

async function onPickedFiles(event: Event) {
  const input = event.target as HTMLInputElement
  const list = [...(input.files ?? [])]
  const replace = pendingFolderReplace
  pendingFolderReplace = false
  input.value = ''
  if (!list.length && !replace) return
  importing.value = true
  try {
    const picked = await readPickedFiles(list)
    const opened = await adoptImports(picked.entries, { replace, name: replace ? (picked.name || 'workspace') : undefined })
    if (opened && replace) markWorkspaceOpened()
  } catch (error) {
    showNotice(errorText(error))
  } finally {
    importing.value = false
  }
}

function refreshRecentItems() {
  recentItems.value = readRecentItems()
}

async function directoryExists(path: string): Promise<boolean> {
  if (!path) return true
  if (isAndroidWorkspace) {
    try {
      await Workspace.listDirectory({ path })
      return true
    } catch {
      return false
    }
  }
  const parent = parentOf(path)
  await ensureDirLoaded(parent, true)
  return (dirChildren.value[parent] ?? []).some((entry) => entry.kind === 'dir' && entry.path === path)
}

async function recentFileExists(path: string): Promise<boolean> {
  if (!path) return false
  if (files.value.some((file) => file.path === path) || diskPaths.value.includes(path)) return true
  if (isAndroidWorkspace) {
    try {
      const result = await Workspace.readFile({ path })
      return result.exists
    } catch {
      return false
    }
  }
  return false
}

async function openRecentFromWelcome(item: RecentItem) {
  if (importing.value || workspaceLoading.value) return
  trackRecent(item)

  if (item.kind === 'folder') {
    const folderPath = (item.path || item.name).trim()
    const label = item.name || folderPath
    const matchesCurrentWorkspace = label === workspaceName.value || folderPath === workspaceName.value

    // 当前工作区就是这项（允许空文件夹）
    if (matchesCurrentWorkspace) {
      await ensureDirLoaded('', true)
      markWorkspaceOpened()
      return
    }

    // 工作区内仍有这个目录（含子路径）→ 打开；空目录也可以
    if (folderPath && await directoryExists(folderPath)) {
      try {
        await openDirectoryAsWorkspace(folderPath, label)
      } catch (error) {
        showNotice(errorText(error))
      }
      return
    }

    // 按名字在根目录再找一次
    await ensureDirLoaded('', true)
    const match = (dirChildren.value[''] ?? []).find(
      (entry) => entry.kind === 'dir' && (entry.name === label || entry.path === folderPath),
    )
    if (match) {
      try {
        await openDirectoryAsWorkspace(match.path, match.name)
      } catch (error) {
        showNotice(errorText(error))
      }
      return
    }

    showNotice(`找不到文件夹「${label}」，可能已被移走或删除`)
    return
  }

  const filePath = item.path.trim()
  if (!(await recentFileExists(filePath))) {
    showNotice(`找不到文件「${item.name || filePath}」，可能已被移走或删除`)
    return
  }
  markWorkspaceOpened()
  openFile({
    path: filePath,
    language: languageFor(filePath),
    runnable: filePath === entryPath,
    starter: '',
  })
}

async function openFolderFromWelcome() {
  await startImport('folder')
}

function openMenu(row: ExplorerRow, x: number, y: number) {
  const itemCount = (row.kind === 'dir' ? 1 : 0) + (row.kind === 'dir' && row.path ? 1 : 0) + (row.path ? 2 : 0) + 2
  const width = 188
  const height = itemCount * 36 + 8
  menu.value = {
    kind: row.kind,
    name: row.name,
    path: row.path,
    x: Math.max(8, Math.min(x, window.innerWidth - width - 8)),
    y: Math.max(8, Math.min(y, window.innerHeight - height - 8)),
  }
}

async function openMenuDirectory() {
  const current = menu.value
  menu.value = null
  if (!current || current.kind !== 'dir' || !current.path) return
  try {
    importing.value = true
    await openDirectoryAsWorkspace(current.path, current.name)
  } catch (error) {
    showNotice(errorText(error))
  } finally {
    importing.value = false
  }
}

function onRowPointerDown(event: PointerEvent, row: ExplorerRow) {
  if (event.button !== 0) return
  pressX = event.clientX
  pressY = event.clientY
  window.clearTimeout(pressTimer)
  pressTimer = window.setTimeout(() => {
    pressTimer = 0
    holdClick()
    openMenu(row, pressX, pressY)
  }, 480)
}

function onRowPointerMove(event: PointerEvent) {
  if (!pressTimer) return
  if (Math.hypot(event.clientX - pressX, event.clientY - pressY) > 8) {
    window.clearTimeout(pressTimer)
    pressTimer = 0
  }
}

function onRowPointerUp() {
  window.clearTimeout(pressTimer)
  pressTimer = 0
}

function onRowContextMenu(event: MouseEvent, row: ExplorerRow) {
  event.preventDefault()
  window.clearTimeout(pressTimer)
  pressTimer = 0
  holdClick()
  openMenu(row, event.clientX, event.clientY)
}

function holdClick() {
  suppressClick = true
  window.setTimeout(() => { suppressClick = false }, 700)
}

function onRowClick(row: ExplorerRow) {
  if (suppressClick) {
    suppressClick = false
    return
  }
  if (row.kind === 'dir') {
    const nextOpen = !expanded.value[row.path]
    expanded.value = { ...expanded.value, [row.path]: nextOpen }
    if (nextOpen) void ensureDirLoaded(row.path)
    return
  }
  if (row.file) openFile(row.file)
}

function closeMenu() {
  menu.value = null
}

function askCreate() {
  const current = menu.value
  if (!current || current.kind !== 'dir') return
  promptError.value = ''
  prompt.value = { mode: 'create', kind: 'file', name: current.name, path: current.path, draft: '' }
  menu.value = null
}

function askCreateFolder() {
  const current = menu.value
  if (!current || current.kind !== 'dir') return
  promptError.value = ''
  prompt.value = { mode: 'create', kind: 'dir', name: current.name, path: current.path, draft: '' }
  menu.value = null
}

/** 有打开文件时在其父目录新建；否则在工作区根目录 */
function createTargetDir(): { path: string; name: string } {
  const open = activeFile.value?.path?.trim() || activePath.value.trim()
  if (!open) {
    return { path: '', name: workspaceName.value || '工作区' }
  }
  const dir = parentOf(open)
  if (!dir) {
    return { path: '', name: workspaceName.value || '工作区' }
  }
  const name = dir.includes('/') ? dir.slice(dir.lastIndexOf('/') + 1) : dir
  return { path: dir, name }
}

function askCreateAtRoot() {
  const target = createTargetDir()
  promptError.value = ''
  prompt.value = {
    mode: 'create',
    kind: 'file',
    name: target.name,
    path: target.path,
    draft: '',
  }
  if (target.path) expandDirectory(target.path)
}

function askCreateFolderAtRoot() {
  const target = createTargetDir()
  promptError.value = ''
  prompt.value = {
    mode: 'create',
    kind: 'dir',
    name: target.name,
    path: target.path,
    draft: '',
  }
  if (target.path) expandDirectory(target.path)
}

function askRename() {
  const current = menu.value
  if (!current?.path) return
  promptError.value = ''
  prompt.value = { mode: 'rename', kind: current.kind, name: current.name, path: current.path, draft: current.name }
  menu.value = null
}

function askDelete() {
  const current = menu.value
  if (!current?.path) return
  promptError.value = ''
  prompt.value = { mode: 'delete', kind: current.kind, name: current.name, path: current.path, draft: '' }
  menu.value = null
}

function copyWithCommand(path: string): boolean {
  const area = document.createElement('textarea')
  area.value = path
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.left = '-9999px'
  document.body.appendChild(area)
  area.select()
  const copied = document.execCommand('copy')
  area.remove()
  return copied
}

async function copyEntryPath() {
  const path = menu.value?.path || '.'
  menu.value = null
  let copied = copyWithCommand(path)
  if (!copied && navigator.clipboard?.writeText) {
    try {
      await navigator.clipboard.writeText(path)
      copied = true
    } catch {
      copied = false
    }
  }
  showNotice(copied ? '已复制路径' : path)
}

function expandDirectory(path: string) {
  void (async () => {
    const next: Record<string, boolean> = { ...expanded.value, '': true }
    const dirs: string[] = []
    let current = path
    while (current) {
      dirs.push(current)
      current = parentOf(current)
    }
    dirs.reverse()
    await ensureDirLoaded('')
    for (const dir of dirs) {
      next[dir] = true
      await ensureDirLoaded(dir)
    }
    expanded.value = next
  })()
}

function rememberEmptyDir(path: string) {
  if (!path || emptyDirs.value.includes(path)) return
  emptyDirs.value = [...emptyDirs.value, path]
}

function forgetEmptyDir(path: string) {
  const prefix = `${path}/`
  emptyDirs.value = emptyDirs.value.filter((item) => item !== path && !item.startsWith(prefix))
}

function insertDirIntoTree(parent: string, path: string, name: string) {
  const current = dirChildren.value[parent] ?? []
  if (current.some((entry) => entry.path === path)) return
  const next = [...current, { name, path, kind: 'dir' as const }].sort((left, right) => {
    if (left.kind !== right.kind) return left.kind === 'dir' ? -1 : 1
    return left.name.localeCompare(right.name, 'zh-CN')
  })
  dirChildren.value = { ...dirChildren.value, [parent]: next, [path]: dirChildren.value[path] ?? [] }
  expanded.value = { ...expanded.value, [parent]: true, '': true }
}

async function createEntry(dir: string, name: string) {
  const trimmed = name.trim()
  if (!validEntryName(trimmed)) throw new Error('文件名不能包含 / 或 \\')
  const path = joinPath(dir, trimmed)
  await ensureDirLoaded(dir, true)
  if (pathOccupied(path)) throw new Error('已有同名文件或文件夹')
  const snapshot = takeSnapshot()
  forgetEmptyDir(path)
  const file: ProjectFile = { path, language: languageFor(path), runnable: path === entryPath, starter: '' }
  files.value = [...files.value, file]
  sources[path] = ''
  saveFile(path, '')
  saveCatalog(files.value)
  expandDirectory(dir)
  openFile(file)
  if (!isAndroidWorkspace) {
    await ensureDirLoaded(dir, true)
    return
  }
  try {
    await saveQueue
    await Workspace.writeFile({ path, content: '' })
    await ensureDirLoaded(dir, true)
  } catch (error) {
    restoreSnapshot(snapshot, [path])
    throw new Error(errorText(error))
  }
}

async function createDirectory(dir: string, name: string) {
  const trimmed = name.trim()
  if (!validEntryName(trimmed)) throw new Error('文件夹名不能包含 / 或 \\')
  const path = joinPath(dir, trimmed)
  await ensureDirLoaded(dir, true)
  if (pathOccupied(path)) throw new Error('已有同名文件或文件夹')
  rememberEmptyDir(path)
  insertDirIntoTree(dir, path, trimmed)
  if (!isAndroidWorkspace) {
    await ensureDirLoaded(dir, true)
    showNotice(`已新建文件夹 ${trimmed}`)
    return
  }
  const marker = joinPath(path, '.gitkeep')
  try {
    await saveQueue
    // 现有原生层没有 mkdir，用写临时文件创建父目录后再删掉
    await Workspace.writeFile({ path: marker, content: '' })
    await Workspace.deletePath({ path: marker }).catch(() => undefined)
    await ensureDirLoaded(dir, true)
    await ensureDirLoaded(path, true)
    showNotice(`已新建文件夹 ${trimmed}`)
  } catch (error) {
    forgetEmptyDir(path)
    const parentEntries = (dirChildren.value[dir] ?? []).filter((entry) => entry.path !== path)
    const nextTree = { ...dirChildren.value, [dir]: parentEntries }
    delete nextTree[path]
    dirChildren.value = nextTree
    throw new Error(errorText(error))
  }
}

function toggleMultiSelect() {
  multiSelectOpen.value = !multiSelectOpen.value
  if (!multiSelectOpen.value) {
    selectedPaths.value = []
    batchMoving.value = false
    movePickerOpen.value = false
    moveTargetPath.value = ''
  }
}

function isPathSelected(path: string): boolean {
  return selectedPaths.value.includes(path)
}

function togglePathSelected(path: string) {
  if (!path) return
  if (selectedPaths.value.includes(path)) {
    selectedPaths.value = selectedPaths.value.filter((item) => item !== path)
  } else {
    selectedPaths.value = [...selectedPaths.value, path]
  }
  if (!selectedPaths.value.length) {
    batchMoving.value = false
    movePickerOpen.value = false
  }
}

function compressSelectedPaths(paths: string[]): string[] {
  const sorted = [...paths].sort((left, right) => left.length - right.length || left.localeCompare(right))
  const kept: string[] = []
  for (const path of sorted) {
    if (kept.some((parent) => path === parent || path.startsWith(`${parent}/`))) continue
    kept.push(path)
  }
  return kept
}

function entryKindOf(path: string): EntryKind {
  const row = explorerRows.value.find((item) => item.path === path)
  if (row?.kind === 'dir') return 'dir'
  if ((dirChildren.value[parentOf(path)] ?? []).some((entry) => entry.path === path && entry.kind === 'dir')) return 'dir'
  if (emptyDirs.value.includes(path) || path in dirChildren.value) return 'dir'
  return 'file'
}

function startBatchMove() {
  if (!selectedPaths.value.length) return
  batchMoving.value = true
  moveTargetPath.value = ''
  movePickerOpen.value = false
  void ensureDirLoaded('', true)
}

function cancelBatchMove() {
  batchMoving.value = false
  movePickerOpen.value = false
  moveTargetPath.value = ''
}

function setMoveTarget(path: string) {
  moveTargetPath.value = path
  void ensureDirLoaded(path, true)
}

function pickMoveChild(path: string) {
  setMoveTarget(path)
}

const moveTargetLabel = computed(() => {
  if (!moveTargetPath.value) return workspaceName.value || '工作区根目录'
  return moveTargetPath.value
})

async function relocateEntry(from: string, kind: EntryKind, destDir: string) {
  const name = from.includes('/') ? from.slice(from.lastIndexOf('/') + 1) : from
  const next = joinPath(destDir, name)
  if (next === from) return
  if (kind === 'dir' && (destDir === from || destDir.startsWith(`${from}/`))) {
    throw new Error('不能移动到自身或其子文件夹')
  }
  if (pathOccupied(next)) throw new Error(`目标已存在「${name}」`)
  const snapshot = takeSnapshot()
  const prefix = `${from}/`
  const moved: string[] = []
  files.value = files.value.map((file) => {
    const hit = kind === 'file' ? file.path === from : file.path === from || file.path.startsWith(prefix)
    if (!hit) return file
    const path = kind === 'file' ? next : (file.path === from ? next : `${next}${file.path.slice(from.length)}`)
    const content = sources[file.path] ?? ''
    sources[path] = content
    delete sources[file.path]
    saveFile(path, content)
    forgetFile(file.path)
    if (activePath.value === file.path) activePath.value = path
    moved.push(path)
    return retarget(file, path)
  })
  if (kind === 'dir') {
    emptyDirs.value = emptyDirs.value.map((item) => {
      if (item === from) return next
      if (item.startsWith(prefix)) return `${next}${item.slice(from.length)}`
      return item
    })
  }
  saveCatalog(files.value)
  if (!isAndroidWorkspace) {
    await ensureDirLoaded(parentOf(from), true)
    await ensureDirLoaded(destDir, true)
    return
  }
  try {
    await saveQueue
    await Workspace.renamePath({ from, to: next })
    await ensureDirLoaded(parentOf(from), true)
    await ensureDirLoaded(destDir, true)
  } catch (error) {
    const message = errorText(error)
    if (!message.includes('路径不存在')) {
      restoreSnapshot(snapshot, moved)
      throw new Error(message)
    }
    try {
      for (const path of moved) {
        const oldPath = path === next ? from : `${from}${path.slice(next.length)}`
        await Workspace.writeFile({ path, content: sources[path] ?? '' })
        await Workspace.deletePath({ path: oldPath }).catch(() => undefined)
      }
      await ensureDirLoaded(parentOf(from), true)
      await ensureDirLoaded(destDir, true)
    } catch (fallback) {
      restoreSnapshot(snapshot, moved)
      throw new Error(errorText(fallback))
    }
  }
}

async function confirmBatchMove() {
  if (batchBusy.value || !selectedPaths.value.length) return
  batchBusy.value = true
  try {
    const targets = compressSelectedPaths(selectedPaths.value)
    for (const path of targets) {
      await relocateEntry(path, entryKindOf(path), moveTargetPath.value)
    }
    showNotice(`已移动 ${targets.length} 项`)
    selectedPaths.value = []
    cancelBatchMove()
    await ensureDirLoaded('', true)
    if (moveTargetPath.value) await ensureDirLoaded(moveTargetPath.value, true)
  } catch (error) {
    showNotice(errorText(error))
  } finally {
    batchBusy.value = false
  }
}

async function confirmBatchDelete() {
  if (batchBusy.value || !selectedPaths.value.length) return
  const targets = compressSelectedPaths(selectedPaths.value)
  if (!window.confirm(`确定删除选中的 ${targets.length} 项？`)) return
  batchBusy.value = true
  try {
    for (const path of targets) {
      const kind = entryKindOf(path)
      forgetEmptyDir(path)
      await removeEntry(path, kind)
    }
    showNotice(`已删除 ${targets.length} 项`)
    selectedPaths.value = []
    cancelBatchMove()
  } catch (error) {
    showNotice(errorText(error))
  } finally {
    batchBusy.value = false
  }
}

async function renameEntry(from: string, kind: EntryKind, name: string) {
  const trimmed = name.trim()
  if (!validEntryName(trimmed)) throw new Error('名称不能包含 / 或 \\')
  const next = joinPath(parentOf(from), trimmed)
  if (next === from) return
  if (pathOccupied(next) || files.value.some((file) => next === file.path || next.startsWith(`${file.path}/`))) {
    throw new Error('已有同名文件或文件夹')
  }
  const snapshot = takeSnapshot()
  const prefix = `${from}/`
  const moved: string[] = []
  files.value = files.value.map((file) => {
    const hit = kind === 'file' ? file.path === from : file.path === from || file.path.startsWith(prefix)
    if (!hit) return file
    const path = kind === 'file' ? next : (file.path === from ? next : `${next}${file.path.slice(from.length)}`)
    const content = sources[file.path] ?? ''
    sources[path] = content
    delete sources[file.path]
    saveFile(path, content)
    forgetFile(file.path)
    if (activePath.value === file.path) activePath.value = path
    moved.push(path)
    return retarget(file, path)
  })
  saveCatalog(files.value)
  if (!isAndroidWorkspace) return
  try {
    await saveQueue
    await Workspace.renamePath({ from, to: next })
  } catch (error) {
    const message = errorText(error)
    if (!message.includes('路径不存在')) {
      restoreSnapshot(snapshot, moved)
      throw new Error(message)
    }
    try {
      for (const path of moved) {
        const oldPath = path === next ? from : `${from}${path.slice(next.length)}`
        await Workspace.writeFile({ path, content: sources[path] ?? '' })
        await Workspace.deletePath({ path: oldPath }).catch(() => undefined)
      }
    } catch (fallback) {
      restoreSnapshot(snapshot, moved)
      throw new Error(errorText(fallback))
    }
  }
}

async function removeEntry(path: string, kind: EntryKind) {
  const snapshot = takeSnapshot()
  const prefix = `${path}/`
  const removed = files.value.filter((file) => file.path === path || (kind === 'dir' && file.path.startsWith(prefix)))
  files.value = files.value.filter((file) => !removed.includes(file))
  for (const file of removed) {
    delete sources[file.path]
    forgetFile(file.path)
  }
  if (!files.value.some((file) => file.path === activePath.value)) {
    activePath.value = files.value[0]?.path ?? ''
  }
  diskPaths.value = diskPaths.value.filter((item) => item !== path && !(kind === 'dir' && item.startsWith(prefix)))
  saveCatalog(files.value)
  const parent = parentOf(path)
  if (!isAndroidWorkspace) {
    await ensureDirLoaded(parent, true)
    return
  }
  try {
    await saveQueue
    await Workspace.deletePath({ path })
    await ensureDirLoaded(parent, true)
  } catch (error) {
    restoreSnapshot(snapshot, [])
    throw new Error(errorText(error))
  }
}

async function confirmPrompt() {
  const current = prompt.value
  if (!current) return
  promptError.value = ''
  try {
    if (current.mode === 'create') {
      if (current.kind === 'dir') await createDirectory(current.path, current.draft)
      else await createEntry(current.path, current.draft)
    } else if (current.mode === 'rename') await renameEntry(current.path, current.kind, current.draft)
    else {
      forgetEmptyDir(current.path)
      await removeEntry(current.path, current.kind)
    }
    prompt.value = null
  } catch (error) {
    promptError.value = errorText(error)
  }
}

watch(prompt, async (current) => {
  if (!current || current.mode === 'delete') return
  await nextTick()
  promptInput.value?.focus()
  promptInput.value?.select()
})
</script>

<template>
  <div class="shell vscode-shell">
    <WelcomePage
      v-if="welcomeOpen"
      :busy="importing"
      :recents="recentItems"
      @open-folder="openFolderFromWelcome"
      @open-recent="openRecentFromWelcome"
    />
    <button v-if="importOpen" class="import-scrim" type="button" aria-label="关闭导入菜单" @click="importOpen = false"></button>
    <header class="ide-titlebar">
      <div class="title-side">
        <button
          type="button"
          class="ide-brand-btn"
          title="回到欢迎页"
          aria-label="回到欢迎页选择文件夹"
          :disabled="welcomeOpen"
          @click="askLeaveToWelcome"
        >
          <img class="ide-brand" :src="grainSymbol" alt="" width="26" height="26" />
        </button>
      </div>
      <div class="title-center">
        <div class="import-anchor">
          <button type="button" class="pane-icon" aria-label="导入" title="导入" aria-haspopup="menu" :aria-expanded="importOpen" :disabled="importing" @click="importOpen = !importOpen">
            <VscodeIcon name="folder-opened" :size="16" />
          </button>
          <div v-if="importOpen" class="import-menu" role="menu">
            <button type="button" role="menuitem" @click="startImport('files')">打开文件</button>
            <button type="button" role="menuitem" @click="startImport('folder')">打开文件夹</button>
          </div>
        </div>
        <div class="file-search">
          <input
            v-model="fileQuery"
            type="text"
            role="combobox"
            aria-label="搜索文件"
            aria-autocomplete="list"
            aria-controls="file-search-list"
            :aria-expanded="fileSearchOpen"
            :aria-activedescendant="fileSearchOpen && fileMatches[fileSearchIndex] ? `file-search-${fileSearchIndex}` : undefined"
            :placeholder="activePath"
            autocomplete="off"
            autocapitalize="off"
            spellcheck="false"
            @pointerdown="openFileSearch"
            @focus="openFileSearch"
            @input="onFileQuery"
            @keydown="onFileSearchKeydown"
            @blur="scheduleCloseFileSearch"
          />
          <ul v-if="fileSearchOpen" id="file-search-list" class="file-search-list" role="listbox">
            <li v-if="fileMatches.length === 0" class="file-search-empty">没有匹配的文件</li>
            <li v-for="(file, index) in fileMatches" :key="file.path">
              <button
                :id="`file-search-${index}`"
                type="button"
                role="option"
                :aria-selected="index === fileSearchIndex"
                :class="{ on: index === fileSearchIndex }"
                @pointerdown.prevent="chooseSearchedFile(file)"
                @mouseenter="fileSearchIndex = index"
              >
                <FileTypeIcon :path="file.path" />
                <span class="file-search-name">{{ file.path.split('/').pop() }}</span>
                <span v-if="file.path.includes('/')" class="file-search-path">{{ file.path }}</span>
              </button>
            </li>
          </ul>
        </div>
        <span class="import-balance" aria-hidden="true"></span>
      </div>
      <div class="title-side title-side-end">
        <button
          v-if="!welcomeOpen"
          class="ide-icon"
          type="button"
          :class="{ stop: projectRunning }"
          :title="projectRunning
            ? '停止项目'
            : (canRunProject ? '启动项目（package.json → index.html → 根目录 HTML）' : '工作区为空，无法启动')"
          :aria-label="projectRunning ? '停止项目' : '启动项目'"
          :disabled="projectRunBusy || (!projectRunning && !canRunProject)"
          @click="toggleProjectRun"
        >
          <VscodeIcon :name="projectRunning ? 'debug-stop' : 'debug'" :size="16" />
        </button>
        <div class="layout-toggles" role="group" aria-label="面板布局">
          <button type="button" class="layout-toggle" :class="{ on: explorerOpen }" :aria-pressed="explorerOpen" aria-label="显示或隐藏左侧边栏" title="左侧边栏" @click="toggleExplorer">
            <VscodeIcon name="layout-sidebar-left" :size="16" />
          </button>
          <button type="button" class="layout-toggle" :class="{ on: dockedConsole }" :aria-pressed="dockedConsole" aria-label="显示或隐藏底部面板" title="底部面板" @click="togglePanel">
            <VscodeIcon name="layout-panel" :size="16" />
          </button>
          <button type="button" class="layout-toggle" :class="{ on: previewOpen }" :aria-pressed="previewOpen" aria-label="页面预览" title="页面预览" @click="togglePreview">
            <VscodeIcon name="layers-2" :size="16" />
          </button>
        </div>
      </div>
      <input ref="importFileInput" class="import-input" type="file" multiple @change="onPickedFiles" />
      <input ref="importDirInput" class="import-input" type="file" multiple webkitdirectory @change="onPickedFiles" />
    </header>
    <div class="ide-body">
      <nav class="activitybar" aria-label="工作区工具">
        <button
          type="button"
          class="activity"
          :class="{ selected: explorerOpen && sidebarView === 'files' }"
          aria-label="资源管理器"
          title="资源管理器"
          :aria-expanded="explorerOpen && sidebarView === 'files'"
          @click="toggleExplorer"
        >
          <VscodeIcon name="files" :size="22" />
        </button>
        <button
          type="button"
          class="activity"
          :class="{ selected: explorerOpen && sidebarView === 'search' }"
          aria-label="搜索"
          title="搜索"
          :aria-expanded="explorerOpen && sidebarView === 'search'"
          @click="showSearchSidebar"
        >
          <VscodeIcon name="search" :size="22" />
        </button>
        <button type="button" class="activity" :class="{ selected: !explorerOpen }" aria-label="显示项目资源库" title="项目资源库" @click="showProject">
          <VscodeIcon name="code" :size="22" />
        </button>
        <span class="activity-spacer"></span>
        <button type="button" class="activity" :class="{ selected: settingsOpen }" aria-label="设置" title="设置" :aria-pressed="settingsOpen" @click="openSettings">
          <VscodeIcon name="settings-gear" :size="22" />
        </button>
      </nav>
    <div
      ref="workspaceEl"
      class="workspace"
      :class="{ 'explorer-open': explorerOpen, 'console-docked': dockedConsole }"
      :style="{ '--panel-height': panelHeight + 'px', '--explorer-width': explorerWidth + 'px' }"
    >
      <button
        v-if="explorerOpen"
        class="col-sash"
        type="button"
        aria-label="调整项目资源库宽度"
        :aria-valuenow="explorerWidth"
        aria-valuemin="160"
        :style="{ left: explorerWidth + 'px' }"
        @pointerdown="onColumnResizeDown"
        @keydown="onColumnResizeKey"
      ></button>
      <aside
        v-if="explorerOpen"
        class="files"
        :class="{
          'files-batching': sidebarView === 'files' && multiSelectOpen && (selectedCount > 0 || batchMoving),
          'files-search': sidebarView === 'search',
        }"
      >
        <SearchPanel
          v-if="sidebarView === 'search'"
          ref="searchPanel"
          :cwd="searchScopePath"
          :busy="importing || workspaceLoading"
          @open="openSearchHit"
        />
        <template v-else>
        <div class="files-header">
          <button
            type="button"
            class="files-label files-root"
            title="在根目录创建或管理"
            @contextmenu.prevent="openMenu({ kind: 'dir', name: workspaceName, path: '', depth: 0 }, $event.clientX, $event.clientY)"
            @pointerdown="onRowPointerDown($event, { kind: 'dir', name: workspaceName, path: '', depth: 0 })"
            @pointermove="onRowPointerMove"
            @pointerup="onRowPointerUp"
            @pointercancel="onRowPointerUp"
          >
            <img class="file-icon" :src="workspaceFolderIcon" width="16" height="16" alt="" />
            <span>{{ workspaceName }}</span>
          </button>
          <div class="files-actions" role="group" aria-label="资源管理器操作">
            <button
              type="button"
              class="files-action"
              :class="{ on: multiSelectOpen }"
              title="多选"
              aria-label="多选"
              :aria-pressed="multiSelectOpen"
              @click="toggleMultiSelect"
            >
              <VscodeIcon name="checklist" :size="16" />
            </button>
            <button type="button" class="files-action" title="新建文件" aria-label="新建文件" @click="askCreateAtRoot">
              <VscodeIcon name="new-file" :size="16" />
            </button>
            <button type="button" class="files-action" title="新建文件夹" aria-label="新建文件夹" @click="askCreateFolderAtRoot">
              <VscodeIcon name="new-folder" :size="16" />
            </button>
          </div>
        </div>
        <div class="files-tree">
          <div
            v-for="row in explorerRows"
            :key="`${row.kind}:${row.path || '.'}`"
            class="file-row"
            :class="{ selected: multiSelectOpen && isPathSelected(row.path) }"
          >
            <button
              v-if="multiSelectOpen"
              type="button"
              class="file-check"
              :aria-checked="isPathSelected(row.path)"
              role="checkbox"
              :aria-label="`选择 ${row.name}`"
              @click.stop="togglePathSelected(row.path)"
            >
              <span class="file-check-box" :class="{ on: isPathSelected(row.path) }"></span>
            </button>
            <button
              type="button"
              class="file"
              :class="{ on: row.kind === 'file' && row.path === activePath, dir: row.kind === 'dir' }"
              :style="{ paddingLeft: `${8 + row.depth * 14}px` }"
              :data-path="row.path || '.'"
              :data-kind="row.kind"
              :aria-expanded="row.kind === 'dir' ? expanded[row.path] === true : undefined"
              @click="onRowClick(row)"
              @contextmenu="onRowContextMenu($event, row)"
              @pointerdown="onRowPointerDown($event, row)"
              @pointermove="onRowPointerMove"
              @pointerup="onRowPointerUp"
              @pointercancel="onRowPointerUp"
            >
              <VscodeIcon
                v-if="row.kind === 'dir'"
                class="fold-chevron"
                :class="{ shut: expanded[row.path] !== true }"
                name="chevron-down"
                :size="16"
              />
              <img
                v-if="row.kind === 'dir'"
                class="file-icon"
                :src="expanded[row.path] === true ? workspaceFolderIcon : folderIcon"
                width="16"
                height="16"
                alt=""
              />
              <template v-else>
                <span class="fold-chevron"></span>
                <FileTypeIcon :path="row.path" />
              </template>
              <span>{{ row.name }}</span>
            </button>
          </div>
        </div>
        <footer v-if="multiSelectOpen && selectedCount > 0 && !batchMoving" class="files-batch">
          <span class="files-batch-count">已选 {{ selectedCount }}</span>
          <span class="files-batch-spacer"></span>
          <button type="button" class="files-batch-btn" :disabled="batchBusy" @click="startBatchMove">
            <VscodeIcon name="move" :size="14" />
            移动
          </button>
          <button type="button" class="files-batch-btn danger" :disabled="batchBusy" @click="confirmBatchDelete">
            <VscodeIcon name="trash" :size="14" />
            删除
          </button>
        </footer>
        <footer v-else-if="multiSelectOpen && batchMoving" class="files-move">
          <div class="files-move-head">
            <strong>移动到</strong>
            <span>已选 {{ selectedCount }} 项</span>
          </div>
          <nav class="files-move-crumbs" aria-label="目标路径">
            <button
              v-for="(crumb, index) in moveBreadcrumb"
              :key="`${crumb.path || '.'}-${index}`"
              type="button"
              class="files-move-crumb-chip"
              :class="{ on: crumb.path === moveTargetPath }"
              @click="setMoveTarget(crumb.path)"
            >
              <img v-if="index === 0" class="file-icon" :src="workspaceFolderIcon" width="12" height="12" alt="" />
              <span>{{ crumb.name }}</span>
            </button>
          </nav>
          <div class="files-move-folders" role="listbox" aria-label="进入子文件夹">
            <button
              v-if="moveTargetPath"
              type="button"
              class="files-move-folder"
              role="option"
              @click="setMoveTarget(parentOf(moveTargetPath))"
            >
              <span class="files-move-folder-up">↑</span>
              <span>返回上级</span>
            </button>
            <button
              v-for="folder in moveChildFolders"
              :key="folder.path"
              type="button"
              class="files-move-folder"
              role="option"
              @click="pickMoveChild(folder.path)"
            >
              <img class="file-icon" :src="folderIcon" width="16" height="16" alt="" />
              <span>{{ folder.name }}</span>
              <span class="files-move-folder-go">进入</span>
            </button>
            <p v-if="!moveChildFolders.length" class="files-move-empty">
              当前没有子文件夹，可直接移动到此处
            </p>
          </div>
          <div class="files-move-dest" :title="moveTargetLabel">
            目标：<code>{{ moveTargetLabel }}</code>
          </div>
          <div class="files-move-actions">
            <button type="button" class="files-batch-btn" :disabled="batchBusy" @click="cancelBatchMove">取消</button>
            <button type="button" class="files-batch-btn primary" :disabled="batchBusy" @click="confirmBatchMove">
              移动到此处
            </button>
          </div>
        </footer>
        </template>
      </aside>

      <section class="pane editor" :class="{ active: editorActive }" :aria-hidden="!editorActive">
        <header v-if="settingsOpen || files.length" class="pane-bar file-tabs">
          <button
            v-for="file in files"
            :key="file.path"
            type="button"
            class="tab"
            :class="{ on: !settingsOpen && file.path === activePath }"
            @click="settingsOpen = false; openFile(file)"
          >
            <FileTypeIcon :path="file.path" />
            {{ file.path.split('/').pop() }}
          </button>
          <button
            v-if="settingsOpen"
            type="button"
            class="tab on"
            @click="openSettings"
          >
            <VscodeIcon name="settings-gear" :size="16" />
            设置
            <span
              class="tab-close"
              role="button"
              tabindex="0"
              aria-label="关闭设置"
              @click.stop="closeSettings"
              @keydown.enter.prevent="closeSettings"
            >
              <VscodeIcon name="close" :size="12" />
            </span>
          </button>
          <span class="pane-meta">{{ settingsOpen ? '用户设置' : (activeFile?.runnable ? '运行入口' : '仅编辑') }}</span>
        </header>
        <div v-if="settingsOpen" class="breadcrumbs">设置</div>
        <div v-else-if="!editorEmptyMode" class="breadcrumbs">{{ workspaceName }} <span v-if="activePath">›</span> {{ activePath.replaceAll('/', ' › ') }}</div>
        <p v-if="workspaceError" class="terminal-error" role="alert">{{ workspaceError }}</p>
        <SettingsEditor v-else-if="settingsOpen" />
        <EditorEmptyState
          v-else-if="editorEmptyMode"
          :mode="editorEmptyMode"
          :workspace-name="workspaceName"
          :recents="recentItems"
          :busy="importing || workspaceLoading"
          :open-shortcut="openShortcut"
          @open-folder="startImport('folder')"
          @create-file="askCreateAtRoot"
          @open-search="openFileSearch"
          @open-recent="openRecentFromWelcome"
        />
        <CodeEditor
          v-else
          :path="activePath"
          :files="workspaceFiles"
          :active="editorActive"
          :reveal="reveal"
          @cursor="onCursor"
          @change="onChange"
          @navigate="onNavigate"
        />
      </section>

      <section
        class="pane console"
        :class="{ active: dockedConsole }"
        :aria-hidden="!dockedConsole"
      >
        <button
          class="panel-sash"
          type="button"
          aria-label="调整终端高度"
          :aria-valuenow="panelHeight"
          aria-valuemin="140"
          @pointerdown="onPanelResizeDown"
          @keydown="onPanelResizeKey"
        ></button>
        <header class="pane-bar">
          <div class="panel-tabs" role="tablist" aria-label="底部面板">
            <button type="button" role="tab" :aria-selected="panelTab === 'console'" @click="panelTab = 'console'">输出</button>
            <button type="button" role="tab" :aria-selected="panelTab === 'runtime'" @click="panelTab = 'runtime'">终端</button>
          </div>
          <span class="pane-actions">
            <button v-if="panelTab === 'runtime'" type="button" class="pane-icon" aria-label="添加控制台" title="添加控制台" :disabled="terminals.length >= 8" @click="addTerminal">
              <VscodeIcon name="add" :size="18" />
            </button>
            <button
              v-if="panelTab === 'runtime' && terminals.length > 1"
              type="button"
              class="pane-icon"
              aria-label="关闭当前控制台"
              title="关闭当前控制台"
              @click="closeTerminal(activeTerminalId)"
            >
              <VscodeIcon name="trash" :size="16" />
            </button>
            <button v-if="panelTab === 'console'" type="button" class="pane-icon" aria-label="清空" title="清空" @click="clearConsole">
              <VscodeIcon name="clear-all" :size="16" />
            </button>
            <button v-else type="button" class="pane-icon pane-icon-lg" aria-label="停止" title="停止" :disabled="!activeTerminal?.ready" @click="activeTerminal?.interrupt('已停止')">
              <VscodeIcon name="debug-stop" :size="16" />
            </button>
          </span>
        </header>
        <div class="console-body">
          <div class="console-main">
            <ConsolePanel v-show="panelTab === 'console'" :lines="consoleLines" :active="dockedConsole && panelTab === 'console'" />
            <WorkspaceTerminal
              v-for="item in terminals"
              :key="item.id"
              :ref="terminalBinder(item.id)"
              v-show="panelTab === 'runtime' && activeTerminalId === item.id"
              :prepare="prepareWorkspace"
              :cwd="item.cwd"
              :active="dockedConsole && panelTab === 'runtime' && activeTerminalId === item.id"
              @open-link="openLocalService"
            />
          </div>
          <aside
            v-if="panelTab === 'runtime'"
            class="terminal-list"
            aria-label="已打开的控制台"
          >
            <button
              v-for="item in terminals"
              :key="`list-${item.id}`"
              type="button"
              class="terminal-list-item"
              :class="{ on: activeTerminalId === item.id }"
              :title="item.cwd ? `${item.title} · ${item.cwd}` : item.title"
              @click="selectTerminal(item.id)"
            >
              <VscodeIcon name="terminal" :size="14" />
              <span class="terminal-list-title">{{ item.title }}</span>
            </button>
          </aside>
        </div>
      </section>
    </div>
    </div>

    <footer class="statusbar">
      <span class="accent">{{ activeFile ? languageLabel(activeFile.path) : '纯文本' }}</span>
      <span>UTF-8</span>
      <span>Ln {{ cursor.line }}, Col {{ cursor.column }}</span>
      <span>{{ entryPath }}</span>
      <span class="spacer"></span>
      <span :data-state="projectRunning ? 'running' : runState">{{ projectRunning ? '项目运行中' : `${runShortcut} 启动` }}</span>
    </footer>
    <Teleport to="body">
      <div v-if="menu" class="menu-scrim" @pointerdown="closeMenu" @contextmenu.prevent="closeMenu"></div>
      <div
        v-if="menu"
        class="context-menu"
        role="menu"
        :style="{ left: `${menu.x}px`, top: `${menu.y}px` }"
        @pointerdown.stop
      >
        <button v-if="menu.kind === 'dir'" type="button" role="menuitem" data-action="create" @click="askCreate">新建文件</button>
        <button v-if="menu.kind === 'dir'" type="button" role="menuitem" data-action="create-folder" @click="askCreateFolder">新建文件夹</button>
        <button v-if="menu.kind === 'dir' && menu.path" type="button" role="menuitem" data-action="open-folder" @click="openMenuDirectory">打开此文件夹</button>
        <button type="button" role="menuitem" data-action="terminal" @click="openInTerminal">在控制台中打开</button>
        <button v-if="menu.path" type="button" role="menuitem" data-action="rename" @click="askRename">重命名</button>
        <button type="button" role="menuitem" data-action="copy" @click="copyEntryPath">复制路径</button>
        <button v-if="menu.path" type="button" role="menuitem" class="danger" data-action="delete" @click="askDelete">删除</button>
      </div>
      <div v-if="prompt" class="tree-dialog-scrim" @pointerdown.self="prompt = null">
        <form class="tree-dialog" @submit.prevent="confirmPrompt">
          <h2>{{ promptTitle }}</h2>
          <p v-if="prompt.mode === 'delete'" class="tree-dialog-copy">
            {{ prompt.kind === 'dir' ? '文件夹里的文件会一起删除。' : '这个文件会从工作区移除。' }}
          </p>
          <input
            v-else
            ref="promptInput"
            v-model="prompt.draft"
            class="tree-dialog-input"
            :aria-label="prompt.mode === 'create' ? (prompt.kind === 'dir' ? '新文件夹名' : '新文件名') : '新名称'"
            :placeholder="prompt.mode === 'create' ? (prompt.kind === 'dir' ? '文件夹名' : '文件名') : undefined"
            autocomplete="off"
            autocapitalize="off"
            spellcheck="false"
          />
          <p v-if="promptError" class="terminal-error" role="alert">{{ promptError }}</p>
          <div class="tree-dialog-actions">
            <button type="button" @click="prompt = null">取消</button>
            <button type="submit" :class="{ danger: prompt.mode === 'delete' }">{{ promptConfirm }}</button>
          </div>
        </form>
      </div>
      <div v-if="leaveWelcomeOpen" class="tree-dialog-scrim" @pointerdown.self="leaveWelcomeOpen = false">
        <div class="tree-dialog" role="dialog" aria-modal="true" aria-labelledby="leave-welcome-title">
          <h2 id="leave-welcome-title">回到欢迎页？</h2>
          <p class="tree-dialog-copy">将离开当前工作区，回到欢迎页重新选择要打开的文件夹。</p>
          <div class="tree-dialog-actions">
            <button type="button" @click="leaveWelcomeOpen = false">取消</button>
            <button type="button" class="primary" @click="confirmLeaveToWelcome">确定</button>
          </div>
        </div>
      </div>
      <p v-if="notice" class="tree-notice" role="status">{{ notice }}</p>
      <PreviewWindow
        ref="previewWindow"
        :open="previewOpen"
        :url="previewServiceUrl"
        @update:open="previewOpen = $event"
      />
    </Teleport>
  </div>
</template>
