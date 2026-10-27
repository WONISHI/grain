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
const canCopySelection = ref(false)
const copyHint = ref('')
let term: Terminal | null = null
let fit: FitAddon | null = null
let observer: ResizeObserver | null = null
let listener: PluginListenerHandle | undefined
let disposeTouchSelect: (() => void) | null = null
let session = ''
let opening = false
let disposed = false
let hostAbort: AbortController | null = null
let cols = 0
let rows = 0
let pendingInput = ''

/** xterm 只监听鼠标选区；平板用长按 + 拖动模拟鼠标选中。 */
function enableTouchSelection(root: HTMLElement): () => void {
  const LONG_MS = 380
  const MOVE_PX = 12
  let timer: number | null = null
  let selecting = false
  let startX = 0
  let startY = 0

  const clearTimer = () => {
    if (timer == null) return
    window.clearTimeout(timer)
    timer = null
  }

  const targetEl = () => root.querySelector('.xterm') ?? root

  const fire = (type: 'mousedown' | 'mousemove' | 'mouseup', touch: Touch, target: EventTarget, detail = 1) => {
    target.dispatchEvent(new MouseEvent(type, {
      bubbles: true,
      cancelable: true,
      view: window,
      detail,
      clientX: touch.clientX,
      clientY: touch.clientY,
      screenX: touch.screenX,
      screenY: touch.screenY,
      button: 0,
      buttons: type === 'mouseup' ? 0 : 1,
    }))
  }

  const onStart = (event: TouchEvent) => {
    if (event.touches.length !== 1) return
    const touch = event.touches[0]!
    startX = touch.clientX
    startY = touch.clientY
    selecting = false
    clearTimer()
    timer = window.setTimeout(() => {
      timer = null
      selecting = true
      try { navigator.vibrate?.(10) } catch { /* ignore */ }
      fire('mousedown', touch, targetEl(), 1)
    }, LONG_MS)
  }

  const onMove = (event: TouchEvent) => {
    const touch = event.touches[0]
    if (!touch) return
    if (!selecting) {
      if (Math.hypot(touch.clientX - startX, touch.clientY - startY) > MOVE_PX) clearTimer()
      return
    }
    event.preventDefault()
    fire('mousemove', touch, document, 1)
  }

  const onEnd = (event: TouchEvent) => {
    clearTimer()
    if (!selecting) return
    selecting = false
    const touch = event.changedTouches[0]
    if (touch) fire('mouseup', touch, document, 1)
  }

  root.addEventListener('touchstart', onStart, { passive: true })
  root.addEventListener('touchmove', onMove, { passive: false })
  root.addEventListener('touchend', onEnd)
  root.addEventListener('touchcancel', onEnd)
  return () => {
    clearTimer()
    root.removeEventListener('touchstart', onStart)
    root.removeEventListener('touchmove', onMove)
    root.removeEventListener('touchend', onEnd)
    root.removeEventListener('touchcancel', onEnd)
  }
}

/** WebView 常读不到系统剪贴板；同应用内复制后靠这份缓存粘贴。 */
let lastCopied = ''

async function copySelection() {
  const text = term?.getSelection() ?? ''
  if (!text) return
  const ok = await copyText(text)
  copyHint.value = ok ? '已复制' : '复制失败'
  window.setTimeout(() => { copyHint.value = '' }, 1200)
}

async function pasteSelection() {
  const ok = await pasteFromClipboard()
  copyHint.value = ok ? '已粘贴' : '剪贴板为空'
  window.setTimeout(() => { copyHint.value = '' }, 1200)
}

function writeTerm(text: string) {
  term?.write(text)
}

async function copyText(text: string): Promise<boolean> {
  if (!text) return false
  lastCopied = text
  let ok = false
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
      ok = true
    }
  } catch {
    /* fall through */
  }
  if (isAndroidWorkspace) {
    try {
      await Workspace.clipboardWrite({ text })
      ok = true
    } catch {
      /* keep previous ok */
    }
  }
  if (ok) return true
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.left = '-9999px'
  document.body.appendChild(area)
  area.select()
  try {
    ok = document.execCommand('copy')
  } catch {
    ok = false
  }
  area.remove()
  return ok || Boolean(lastCopied)
}

async function readClipboardText(): Promise<string> {
  try {
    const web = (await navigator.clipboard?.readText?.()) ?? ''
    if (web) return web
  } catch {
    /* Android WebView 经常拒绝 */
  }
  if (isAndroidWorkspace) {
    try {
      const native = await Workspace.clipboardRead()
      if (native.text) return native.text
    } catch {
      /* fall through */
    }
  }
  return lastCopied
}

async function pasteFromClipboard(): Promise<boolean> {
  const text = await readClipboardText()
  if (!text || !term) return false
  term.paste(text)
  return true
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
    rightClickSelectsWord: true,
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
  if (screen.value) {
    view.open(screen.value)
    disposeTouchSelect = enableTouchSelection(screen.value)
  }
  view.onSelectionChange(() => {
    canCopySelection.value = Boolean(view.hasSelection() && view.getSelection())
  })
  // VS Code 风格：有选区时 Ctrl/Cmd+C 复制；Ctrl/Cmd+V 粘贴。
  // 无选区时 Ctrl+C 仍发给 PTY（中断）。Ctrl+Shift+C/V 始终复制/粘贴。
  view.attachCustomKeyEventHandler((event) => {
    if (event.type !== 'keydown') return true
    if (!(event.ctrlKey || event.metaKey)) return true
    const key = event.key.length === 1 ? event.key.toLowerCase() : event.key
    if (key === 'c' && (event.shiftKey || view.hasSelection())) {
      const selected = view.getSelection()
      if (selected) {
        event.preventDefault()
        void copyText(selected)
        return false
      }
    }
    if (key === 'v') {
      event.preventDefault()
      void pasteFromClipboard()
      return false
    }
    return true
  })
  screen.value?.addEventListener('paste', (event) => {
    const text = event.clipboardData?.getData('text/plain') ?? event.clipboardData?.getData('text')
    if (!text) return
    event.preventDefault()
    lastCopied = text
    view.paste(text)
  })
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

defineExpose({ interrupt, ready, runCommand, focus: focusTerm })

onBeforeUnmount(() => {
  disposed = true
  disposeTouchSelect?.()
  disposeTouchSelect = null
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
      <div class="terminal-screen-wrap">
        <div ref="screen" class="terminal-screen" @pointerdown="focusTerm"></div>
        <div class="terminal-selection-bar">
          <button
            v-if="canCopySelection"
            type="button"
            class="terminal-copy-btn"
            @pointerdown.stop
            @click="copySelection"
          >
            复制
          </button>
          <button type="button" class="terminal-copy-btn" @pointerdown.stop @click="pasteSelection">
            {{ copyHint || '粘贴' }}
          </button>
        </div>
      </div>
      <p v-if="error" class="terminal-error" role="alert">{{ error }}</p>
    </template>
  </div>
</template>
