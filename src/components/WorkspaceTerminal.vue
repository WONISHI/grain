<script setup lang="ts">
import { FitAddon } from '@xterm/addon-fit'
import { WebLinksAddon } from '@xterm/addon-web-links'
import { Terminal } from '@xterm/xterm'
import '@xterm/xterm/css/xterm.css'
import type { PluginListenerHandle } from '@capacitor/core'
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'

const emit = defineEmits<{
  'open-link': [url: string]
}>()

function isLocalPreviewUrl(uri: string): boolean {
  try {
    const url = new URL(uri)
    if (url.protocol !== 'http:') return false
    return (url.hostname === '127.0.0.1' || url.hostname === 'localhost') && Boolean(url.port)
  } catch {
    return false
  }
}
import {
  hostTerminalClose,
  hostTerminalInterrupt,
  hostTerminalOpen,
  hostTerminalResize,
  hostTerminalWrite,
} from '../plugins/host-terminal'
import { isAndroidWorkspace, Workspace } from '../plugins/workspace'
import { settings } from '../settings'

const props = defineProps<{
  prepare: () => Promise<void>
  active?: boolean
  /** Workspace-relative directory for the shell cwd */
  cwd?: string
}>()

const hostMode = !isAndroidWorkspace && import.meta.env.DEV
const ready = ref(false)
const error = ref('')
const screen = ref<HTMLElement | null>(null)
let term: Terminal | null = null
let fit: FitAddon | null = null
let observer: ResizeObserver | null = null
let listener: PluginListenerHandle | undefined
let session = ''
let opening = false
let disposed = false
let hostAbort: AbortController | null = null
let cols = 0
let rows = 0
let pendingInput = ''

function writeTerm(text: string) {
  term?.write(text)
}

async function send(data: string) {
  if (!session) {
    pendingInput += data
    return
  }
  if (hostMode) await hostTerminalWrite(session, data)
  else await Workspace.terminalWrite({ id: session, data })
}

async function resize(nextCols: number, nextRows: number) {
  if (!session || nextCols < 2 || nextRows < 2) return
  if (hostMode) await hostTerminalResize(session, nextCols, nextRows)
  else await Workspace.terminalResize({ id: session, cols: nextCols, rows: nextRows })
}

function focusTerm() {
  term?.focus()
}

function fitTerm() {
  const el = screen.value
  if (!term || !fit || !el || el.clientWidth < 40 || el.clientHeight < 40) return
  fit.fit()
}

async function openSession() {
  if (disposed || opening || session || !term) return
  opening = true
  error.value = ''
  try {
    for (let attempt = 0; attempt < 20; attempt += 1) {
      try {
        await props.prepare()
        break
      } catch (e) {
        const message = e instanceof Error ? e.message : String(e)
        if (!message.includes('仍在加载') || attempt === 19) throw e
        await new Promise((resolve) => window.setTimeout(resolve, 250))
      }
    }
    if (disposed) return
    fitTerm()
    const width = term.cols
    const height = term.rows
    const cwd = props.cwd?.trim() || undefined
    if (hostMode) {
      hostAbort = new AbortController()
      session = await hostTerminalOpen(width, height, (event) => {
        if (event.type === 'data') writeTerm(event.text ?? '')
        else if (event.type === 'exit') {
          session = ''
          writeTerm('\r\n[终端已退出]\r\n')
        } else {
          error.value = event.text ?? '终端错误'
        }
      }, hostAbort.signal, cwd)
    } else {
      const id = crypto.randomUUID()
      session = id
      const opened = await Workspace.terminalOpen({ id, cols: width, rows: height, cwd })
      session = opened.id
    }
    ready.value = true
    const queued = pendingInput
    pendingInput = ''
    if (queued) await send(queued)
    if (props.active) term.focus()
  } catch (e) {
    session = ''
    error.value = e instanceof Error ? e.message : String(e)
  } finally {
    opening = false
  }
}

async function interrupt(note = '已停止') {
  error.value = ''
  // SIGINT 不会在终端里留下 ^C，本地先写一条记录
  writeTerm(`\r\n^C\r\n\x1b[90m[${note}]\x1b[0m\r\n`)
  if (!session) return
  try {
    // 再向 PTY 发送 Ctrl+C，部分程序靠这个响应
    await send('\x03')
    if (hostMode) await hostTerminalInterrupt(session)
    else await Workspace.terminalInterrupt({ id: session })
  } catch (e) { error.value = String(e) }
}

onMounted(async () => {
  const view = new Terminal({
    cursorBlink: true,
    fontFamily: settings['terminal.integrated.fontFamily'],
    fontSize: settings['terminal.integrated.fontSize'],
    lineHeight: 1.2,
    scrollback: 5000,
    theme: {
      background: '#1e1e1e',
      foreground: '#cccccc',
      cursor: '#ff5e3b',
      selectionBackground: '#5c332b',
    },
  })
  const addon = new FitAddon()
  view.loadAddon(addon)
  view.loadAddon(new WebLinksAddon((_event, uri) => {
    if (isLocalPreviewUrl(uri)) emit('open-link', uri)
  }))
  term = view
  fit = addon
  if (screen.value) view.open(screen.value)
  view.onData((data) => {
    if (!session) {
      pendingInput += data
      void openSession()
      return
    }
    void send(data)
  })
  view.onResize(({ cols: nextCols, rows: nextRows }) => {
    if (nextCols === cols && nextRows === rows) return
    cols = nextCols
    rows = nextRows
    void resize(nextCols, nextRows)
  })
  if (screen.value) {
    observer = new ResizeObserver(() => fitTerm())
    observer.observe(screen.value)
  }
  void document.fonts?.ready.then(() => fitTerm())
  fitTerm()

  if (isAndroidWorkspace) {
    try {
      listener = await Workspace.addListener('output', (event) => {
        if (event.id !== session) return
        if (event.type === 'data') writeTerm(event.text ?? '')
        else if (event.type === 'exit') {
          session = ''
          writeTerm('\r\n[终端已退出]\r\n')
        } else error.value = event.text ?? '终端错误'
      })
    } catch (e) { error.value = String(e) }
  }
  if (!disposed && (hostMode || isAndroidWorkspace)) await openSession()
})

watch(() => props.active, (active) => {
  if (!active) return
  fitTerm()
  term?.focus()
})

watch(
  () => [settings['terminal.integrated.fontSize'], settings['terminal.integrated.fontFamily']] as const,
  ([fontSize, fontFamily]) => {
    if (!term) return
    term.options.fontSize = fontSize
    term.options.fontFamily = fontFamily
    fitTerm()
  },
)

async function runCommand(command: string) {
  const line = command.replace(/\r?\n+$/, '')
  if (!line) return
  await send(`${line}\n`)
}

defineExpose({ interrupt, ready, runCommand })

onBeforeUnmount(() => {
  disposed = true
  observer?.disconnect()
  hostAbort?.abort()
  const id = session
  session = ''
  if (id) {
    if (hostMode) void hostTerminalClose(id)
    else void Workspace.terminalClose({ id })
  }
  void listener?.remove()
  term?.dispose()
})
</script>

<template>
  <div class="workspace-terminal">
    <div v-if="!hostMode && !isAndroidWorkspace" class="runtime-state">
      <span class="runtime-label">GRAIN · ANDROID</span>
      <h2>本机终端需要 Android 应用</h2>
      <p>浏览器构建包无法访问 Shell。用开发服务打开时，可以在这里运行 node 和 npm。</p>
    </div>
    <template v-else>
      <div ref="screen" class="terminal-screen" @pointerdown="focusTerm"></div>
      <p v-if="error" class="terminal-error" role="alert">{{ error }}</p>
    </template>
  </div>
</template>
