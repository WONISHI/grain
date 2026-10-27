import editorWorker from 'monaco-editor/editor/editor.worker?worker'
import cssWorker from 'monaco-editor/language/css/css.worker?worker'
import htmlWorker from 'monaco-editor/language/html/html.worker?worker'
import jsonWorker from 'monaco-editor/language/json/json.worker?worker'
import tsWorker from 'monaco-editor/language/typescript/ts.worker?worker'
import { emmetCSS, emmetHTML, emmetJSX } from 'emmet-monaco-es'
import * as monaco from 'monaco-editor'
import 'monaco-editor/editor.main.css'
import { languageId } from './languages'

export interface WorkspaceBuffer {
  path: string
  content: string
}

export interface EditorJump {
  path: string
  line: number
  column: number
}

const workspaceRoot = '/workspace/'

globalThis.MonacoEnvironment = {
  getWorker(_workerId, label) {
    if (label === 'json') return new jsonWorker()
    if (label === 'css' || label === 'scss' || label === 'less') return new cssWorker()
    if (label === 'html' || label === 'handlebars' || label === 'razor') return new htmlWorker()
    if (label === 'typescript' || label === 'javascript') return new tsWorker()
    return new editorWorker()
  },
}

let started = false
let openHandler: ((jump: EditorJump) => boolean) | null = null

export function ensureMonaco(): typeof monaco {
  if (started) return monaco
  started = true
  configureTypeScript()
  registerVue()
  defineTheme()
  // Emmet 需在创建编辑器实例之前注册（与 VS Code 类似的 html/css 缩写补全）
  emmetHTML(monaco, ['html', 'vue', 'php', 'handlebars', 'razor'])
  emmetCSS(monaco, ['css', 'scss', 'less'])
  emmetJSX(monaco, ['javascript', 'typescript'])
  monaco.editor.registerEditorOpener({
    openCodeEditor(_source, resource, selection) {
      const path = pathFromUri(resource)
      if (!path || !openHandler) return false
      return openHandler({ path, ...positionOf(selection) })
    },
  })
  return monaco
}

export function setEditorOpenHandler(handler: ((jump: EditorJump) => boolean) | null): void {
  openHandler = handler
}

export function fileUri(path: string): monaco.Uri {
  return monaco.Uri.from({ scheme: 'file', path: `${workspaceRoot}${path}` })
}

export function syncModels(files: WorkspaceBuffer[]): void {
  const api = ensureMonaco()
  const next = new Set<string>()
  for (const file of files) {
    const uri = fileUri(file.path)
    next.add(uri.toString())
    const language = languageId(file.path)
    const existing = api.editor.getModel(uri)
    if (!existing) {
      api.editor.createModel(file.content, language, uri)
      continue
    }
    if (existing.getLanguageId() !== language) api.editor.setModelLanguage(existing, language)
    if (existing.getValue() !== file.content) existing.setValue(file.content)
  }
  for (const model of api.editor.getModels()) {
    if (!model.uri.path.startsWith(workspaceRoot) || next.has(model.uri.toString())) continue
    model.dispose()
  }
}

function pathFromUri(uri: monaco.Uri): string | null {
  if (uri.scheme !== 'file' || !uri.path.startsWith(workspaceRoot)) return null
  const path = uri.path.slice(workspaceRoot.length)
  if (!path || path.split('/').some((part) => !part || part === '.' || part === '..')) return null
  return path
}

function positionOf(selection?: monaco.IRange | monaco.IPosition): { line: number; column: number } {
  if (!selection) return { line: 1, column: 1 }
  if ('startLineNumber' in selection) return { line: selection.startLineNumber, column: selection.startColumn }
  return { line: selection.lineNumber, column: selection.column }
}

function configureTypeScript(): void {
  const ts = monaco.typescript
  const options: monaco.typescript.CompilerOptions = {
    target: ts.ScriptTarget.ESNext,
    module: ts.ModuleKind.ESNext,
    moduleResolution: ts.ModuleResolutionKind.NodeJs,
    jsx: ts.JsxEmit.Preserve,
    allowJs: true,
    checkJs: false,
    allowNonTsExtensions: true,
    allowSyntheticDefaultImports: true,
    esModuleInterop: true,
    strict: true,
    skipLibCheck: true,
    noEmit: true,
    lib: ['esnext', 'dom'],
  }
  const typescript = ts.typescriptDefaults
  const javascript = ts.javascriptDefaults
  typescript.setCompilerOptions(options)
  javascript.setCompilerOptions({ ...options, checkJs: false })
  typescript.setDiagnosticsOptions({ noSemanticValidation: false, noSyntaxValidation: false, onlyVisible: true })
  javascript.setDiagnosticsOptions({ noSemanticValidation: true, noSyntaxValidation: false, onlyVisible: true })
  typescript.setEagerModelSync(true)
  javascript.setEagerModelSync(true)
  const vueLib = `
declare module 'vue' {
  export function ref<T>(value: T): { value: T }
  export function computed<T>(getter: () => T): { readonly value: T }
  export function watch<T>(source: T | (() => T), cb: (value: T, oldValue: T) => void): void
  export function onMounted(fn: () => void): void
  export function onBeforeUnmount(fn: () => void): void
  export function defineComponent(options: object): object
}
`
  typescript.addExtraLib(vueLib, 'file:///workspace/node_modules/@types/vue/index.d.ts')
  javascript.addExtraLib(vueLib, 'file:///workspace/node_modules/@types/vue/index.d.ts')
}

function defineTheme(): void {
  monaco.editor.defineTheme('grain-dark', {
    base: 'vs-dark',
    inherit: true,
    rules: [
      { token: 'comment', foreground: '8b939e', fontStyle: 'italic' },
      { token: 'keyword', foreground: 'c586c0' },
      { token: 'string', foreground: 'ce9178' },
      { token: 'number', foreground: 'b5cea8' },
      { token: 'type', foreground: '4ec9b0' },
      { token: 'tag', foreground: '569cd6' },
      { token: 'attribute.name', foreground: '9cdcfe' },
    ],
    colors: {
      'editor.background': '#1e1e1e',
      'editor.foreground': '#d4d4d4',
      'editorCursor.foreground': '#ff5e3b',
      'editor.selectionBackground': '#5c332b',
      'editor.inactiveSelectionBackground': '#5c332b99',
      'editor.lineHighlightBackground': '#ffffff09',
      'editorLineNumber.foreground': '#6a6a6a',
      'editorLineNumber.activeForeground': '#aeafad',
      'editorGutter.background': '#1e1e1e',
      'editorWidget.background': '#252526',
      'editorWidget.border': '#3c3c3c',
      'editorSuggestWidget.background': '#252526',
      'editorSuggestWidget.border': '#3c3c3c',
      'editorSuggestWidget.selectedBackground': '#37373d',
      'editorHoverWidget.background': '#252526',
      'editorHoverWidget.border': '#3c3c3c',
      'editorOverviewRuler.border': '#00000000',
      'scrollbarSlider.background': '#3c3c3c88',
      'scrollbarSlider.hoverBackground': '#5c657088',
      'scrollbarSlider.activeBackground': '#5c6570aa',
    },
  })
}

function registerVue(): void {
  monaco.languages.register({ id: 'vue' })
  monaco.languages.setLanguageConfiguration('vue', {
    comments: { lineComment: '//', blockComment: ['/*', '*/'] },
    brackets: [
      ['{', '}'],
      ['[', ']'],
      ['(', ')'],
      ['<', '>'],
    ],
    autoClosingPairs: [
      { open: '{', close: '}' },
      { open: '[', close: ']' },
      { open: '(', close: ')' },
      { open: '"', close: '"', notIn: ['string'] },
      { open: "'", close: "'", notIn: ['string'] },
      { open: '`', close: '`', notIn: ['string'] },
    ],
  })
  monaco.languages.setMonarchTokensProvider('vue', {
    defaultToken: '',
    keywords: [
      'import', 'export', 'from', 'const', 'let', 'var', 'function', 'return', 'if', 'else',
      'for', 'while', 'type', 'interface', 'extends', 'implements', 'as', 'new', 'this',
      'async', 'await', 'class', 'public', 'private', 'protected', 'readonly', 'of', 'in',
      'typeof', 'instanceof', 'true', 'false', 'null', 'undefined', 'void', 'never', 'any',
      'unknown', 'string', 'number', 'boolean',
    ],
    tokenizer: {
      root: [
        [/<!--/, 'comment', '@comment'],
        [/<script\b[^>]*>/, 'tag', '@script'],
        [/<style\b[^>]*>/, 'tag', '@style'],
        [/<\/?[\w:-]+/, 'tag', '@tag'],
        [/[^<]+/, ''],
      ],
      comment: [
        [/-->/, 'comment', '@pop'],
        [/[^-]+/, 'comment'],
        [/./, 'comment'],
      ],
      tag: [
        [/\s+/, ''],
        [/[\w:-]+/, 'attribute.name'],
        [/=/, 'delimiter'],
        [/"[^"]*"/, 'string'],
        [/'[^']*'/, 'string'],
        [/\/?>/, 'tag', '@pop'],
      ],
      script: [
        [/<\/script>/, 'tag', '@pop'],
        [/\/\*/, 'comment', '@blockComment'],
        [/\/\/.*$/, 'comment'],
        [/"([^"\\]|\\.)*"/, 'string'],
        [/'([^'\\]|\\.)*'/, 'string'],
        [/`([^`\\]|\\.)*`/, 'string'],
        [/\b\d+(?:\.\d+)?\b/, 'number'],
        [/[A-Za-z_]\w*/, { cases: { '@keywords': 'keyword', '@default': 'identifier' } }],
        [/[{}()[\]]/, 'delimiter.bracket'],
        [/./, ''],
      ],
      style: [
        [/<\/style>/, 'tag', '@pop'],
        [/\/\*/, 'comment', '@blockComment'],
        [/[^<]+/, 'string'],
        [/./, ''],
      ],
      blockComment: [
        [/\*\//, 'comment', '@pop'],
        [/./, 'comment'],
      ],
    },
  })
}
