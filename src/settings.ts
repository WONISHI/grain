import { reactive, watch } from 'vue'

export type WordWrap = 'on' | 'off'

export interface GrainSettings {
  'editor.fontSize': number
  'editor.fontFamily': string
  'editor.lineHeight': number
  'editor.tabSize': number
  'editor.wordWrap': WordWrap
  'editor.minimap.enabled': boolean
  'terminal.integrated.fontSize': number
  'terminal.integrated.fontFamily': string
}

export const SETTINGS_DEFAULTS: GrainSettings = {
  'editor.fontSize': 13,
  'editor.fontFamily': '"JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, Consolas, monospace',
  'editor.lineHeight': 22,
  'editor.tabSize': 2,
  'editor.wordWrap': 'on',
  'editor.minimap.enabled': false,
  'terminal.integrated.fontSize': 13,
  'terminal.integrated.fontFamily': 'JetBrains Mono, ui-monospace, monospace',
}

const STORAGE_KEY = 'grain.settings'

function clampNumber(value: unknown, fallback: number, min: number, max: number): number {
  const n = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(n)) return fallback
  return Math.min(max, Math.max(min, Math.round(n)))
}

function loadSettings(): GrainSettings {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return { ...SETTINGS_DEFAULTS }
    const parsed = JSON.parse(raw) as Partial<GrainSettings>
    return {
      'editor.fontSize': clampNumber(parsed['editor.fontSize'], SETTINGS_DEFAULTS['editor.fontSize'], 8, 40),
      'editor.fontFamily': typeof parsed['editor.fontFamily'] === 'string' && parsed['editor.fontFamily'].trim()
        ? parsed['editor.fontFamily']
        : SETTINGS_DEFAULTS['editor.fontFamily'],
      'editor.lineHeight': clampNumber(parsed['editor.lineHeight'], SETTINGS_DEFAULTS['editor.lineHeight'], 12, 60),
      'editor.tabSize': clampNumber(parsed['editor.tabSize'], SETTINGS_DEFAULTS['editor.tabSize'], 1, 8),
      'editor.wordWrap': parsed['editor.wordWrap'] === 'off' ? 'off' : 'on',
      'editor.minimap.enabled': parsed['editor.minimap.enabled'] === true,
      'terminal.integrated.fontSize': clampNumber(
        parsed['terminal.integrated.fontSize'],
        SETTINGS_DEFAULTS['terminal.integrated.fontSize'],
        8,
        40,
      ),
      'terminal.integrated.fontFamily': typeof parsed['terminal.integrated.fontFamily'] === 'string'
        && parsed['terminal.integrated.fontFamily'].trim()
        ? parsed['terminal.integrated.fontFamily']
        : SETTINGS_DEFAULTS['terminal.integrated.fontFamily'],
    }
  } catch {
    return { ...SETTINGS_DEFAULTS }
  }
}

export const settings = reactive<GrainSettings>(loadSettings())

watch(settings, () => {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ ...settings }))
  } catch {
    /* ignore quota */
  }
}, { deep: true })

export function resetSetting<K extends keyof GrainSettings>(key: K) {
  settings[key] = SETTINGS_DEFAULTS[key]
}

export function resetAllSettings() {
  Object.assign(settings, SETTINGS_DEFAULTS)
}

export interface SettingField {
  id: keyof GrainSettings
  category: string
  title: string
  description: string
  type: 'number' | 'string' | 'boolean' | 'enum'
  min?: number
  max?: number
  step?: number
  options?: { value: string; label: string }[]
}

export const SETTING_FIELDS: SettingField[] = [
  {
    id: 'editor.fontSize',
    category: '编辑器',
    title: 'Font Size',
    description: '控制编辑器字号（像素）。',
    type: 'number',
    min: 8,
    max: 40,
    step: 1,
  },
  {
    id: 'editor.fontFamily',
    category: '编辑器',
    title: 'Font Family',
    description: '控制编辑器字体系列。',
    type: 'string',
  },
  {
    id: 'editor.lineHeight',
    category: '编辑器',
    title: 'Line Height',
    description: '控制编辑器行高（像素）。设为 0 时由 Monaco 自动计算。',
    type: 'number',
    min: 0,
    max: 60,
    step: 1,
  },
  {
    id: 'editor.tabSize',
    category: '编辑器',
    title: 'Tab Size',
    description: '一个制表符等于的空格数。',
    type: 'number',
    min: 1,
    max: 8,
    step: 1,
  },
  {
    id: 'editor.wordWrap',
    category: '编辑器',
    title: 'Word Wrap',
    description: '控制是否自动换行。',
    type: 'enum',
    options: [
      { value: 'on', label: 'on' },
      { value: 'off', label: 'off' },
    ],
  },
  {
    id: 'editor.minimap.enabled',
    category: '编辑器',
    title: 'Minimap',
    description: '控制是否显示缩略图。',
    type: 'boolean',
  },
  {
    id: 'terminal.integrated.fontSize',
    category: '终端',
    title: 'Font Size',
    description: '控制终端字号（像素）。',
    type: 'number',
    min: 8,
    max: 40,
    step: 1,
  },
  {
    id: 'terminal.integrated.fontFamily',
    category: '终端',
    title: 'Font Family',
    description: '控制终端字体系列。',
    type: 'string',
  },
]
