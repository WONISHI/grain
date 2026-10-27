<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ensureMonaco,
  fileUri,
  setEditorOpenHandler,
  syncModels,
  type EditorJump,
  type WorkspaceBuffer,
} from '../editor/monaco'
import { settings } from '../settings'

const props = defineProps<{
  path: string
  files: WorkspaceBuffer[]
  active: boolean
  reveal: (EditorJump & { id: number }) | null
}>()

const emit = defineEmits<{
  cursor: [position: { line: number; column: number }]
  change: [value: string]
  navigate: [target: EditorJump]
}>()

const host = ref<HTMLDivElement | null>(null)
let editor: ReturnType<typeof import('monaco-editor').editor.create> | undefined
let applying = false

function publishCursor() {
  const position = editor?.getPosition()
  if (!position) return
  emit('cursor', { line: position.lineNumber, column: position.column })
}

function show(path: string, jump?: { line: number; column: number }) {
  if (!editor) return
  const model = ensureMonaco().editor.getModel(fileUri(path))
  if (!model) return
  if (editor.getModel() !== model) editor.setModel(model)
  if (!jump) return
  const position = { lineNumber: jump.line, column: jump.column }
  editor.setPosition(position)
  editor.revealPositionInCenter(position)
  editor.focus()
}

onMounted(() => {
  if (!host.value) return
  const monaco = ensureMonaco()
  syncModels(props.files)
  setEditorOpenHandler((target) => {
    if (!props.files.some((file) => file.path === target.path)) return false
    emit('navigate', target)
    return true
  })
  editor = monaco.editor.create(host.value, {
    model: monaco.editor.getModel(fileUri(props.path)) ?? undefined,
    theme: 'grain-dark',
    fontFamily: settings['editor.fontFamily'],
    fontSize: settings['editor.fontSize'],
    lineHeight: settings['editor.lineHeight'],
    cursorWidth: 2,
    cursorBlinking: 'smooth',
    automaticLayout: true,
    minimap: { enabled: settings['editor.minimap.enabled'] },
    scrollBeyondLastLine: false,
    wordWrap: settings['editor.wordWrap'],
    tabSize: settings['editor.tabSize'],
    insertSpaces: true,
    padding: { top: 12 },
    folding: true,
    showFoldingControls: 'mouseover',
    matchBrackets: 'always',
    bracketPairColorization: { enabled: true },
    fixedOverflowWidgets: true,
    overviewRulerBorder: false,
    hideCursorInOverviewRuler: true,
    renderLineHighlight: 'line',
    smoothScrolling: true,
    stickyScroll: { enabled: false },
    scrollbar: { verticalScrollbarSize: 10, horizontalScrollbarSize: 10 },
    quickSuggestions: { other: true, comments: false, strings: true },
    suggestOnTriggerCharacters: true,
    wordBasedSuggestions: 'currentDocument',
    snippetSuggestions: 'inline',
    tabCompletion: 'on',
    acceptSuggestionOnEnter: 'on',
    suggest: {
      preview: true,
      showSnippets: true,
      showWords: true,
      snippetsPreventQuickSuggestions: false,
    },
    autoClosingBrackets: 'languageDefined',
    autoClosingQuotes: 'languageDefined',
    autoIndent: 'full',
    formatOnPaste: true,
    ariaLabel: '源代码编辑器',
  })
  // 触控设备上 Ctrl+Space 不便，额外提供显式触发补全的动作
  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Space, () => {
    void editor?.trigger('keyboard', 'editor.action.triggerSuggest', {})
  })
  editor.addAction({
    id: 'grain.goToDefinition',
    label: '转到定义',
    contextMenuGroupId: 'navigation',
    contextMenuOrder: 1.5,
    run: (current) => {
      void current.trigger('grain', 'editor.action.revealDefinition', null)
    },
  })
  editor.addAction({
    id: 'grain.peekDefinition',
    label: '速览定义',
    contextMenuGroupId: 'navigation',
    contextMenuOrder: 1.6,
    run: (current) => {
      void current.trigger('grain', 'editor.action.peekDefinition', null)
    },
  })
  editor.addAction({
    id: 'grain.findReferences',
    label: '查看引用',
    contextMenuGroupId: 'navigation',
    contextMenuOrder: 1.7,
    run: (current) => {
      void current.trigger('grain', 'editor.action.goToReferences', null)
    },
  })
  editor.onDidChangeCursorPosition(publishCursor)
  editor.onDidChangeModelContent(() => {
    if (applying || !editor) return
    emit('change', editor.getValue())
  })
  publishCursor()
})

watch(
  () => props.files,
  (files) => {
    applying = true
    syncModels(files)
    applying = false
    if (editor && editor.getModel()?.uri.toString() !== fileUri(props.path).toString()) show(props.path)
  },
)

watch(
  () => props.path,
  (path) => {
    const target = props.reveal
    show(path, target && target.path === path ? target : undefined)
  },
)

watch(
  () => props.reveal?.id,
  () => {
    const target = props.reveal
    if (!target || target.path !== props.path) return
    show(target.path, target)
  },
)

watch(
  () => props.active,
  (active) => {
    if (active) editor?.layout()
  },
)

watch(
  settings,
  () => {
    editor?.updateOptions({
      fontFamily: settings['editor.fontFamily'],
      fontSize: settings['editor.fontSize'],
      lineHeight: settings['editor.lineHeight'],
      wordWrap: settings['editor.wordWrap'],
      tabSize: settings['editor.tabSize'],
      minimap: { enabled: settings['editor.minimap.enabled'] },
    })
  },
  { deep: true },
)

onBeforeUnmount(() => {
  setEditorOpenHandler(null)
  editor?.dispose()
  editor = undefined
})
</script>

<template>
  <div ref="host" class="editor-host"></div>
</template>
