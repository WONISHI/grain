import { isAndroidWorkspace, Workspace } from '../plugins/workspace'

export interface SearchHit {
  path: string
  line: number
  column: number
  text: string
  matchStart: number
  matchEnd: number
}

export interface SearchFileGroup {
  path: string
  matches: SearchHit[]
}

export interface SearchOptions {
  query: string
  cwd?: string
  caseSensitive?: boolean
  wholeWord?: boolean
  isRegex?: boolean
  maxResults?: number
}

export interface SearchResult {
  files: SearchFileGroup[]
  truncated: boolean
  engine: string
}

const SKIP = new Set([
  'node_modules', '.git', '.grain', 'dist', 'build', 'coverage',
  '.cache', '.gradle', '.idea', '.nuxt', '.output', '.next',
])

const BINARY_EXT = /\.(png|jpe?g|gif|webp|ico|bmp|pdf|zip|gz|tgz|rar|7z|woff2?|ttf|eot|mp3|mp4|webm|wasm|so|apk|jar|class|exe|dll|dylib|bin|o|a|bcmap|pak)$/i

function compileMatcher(options: SearchOptions): { test: (line: string) => { start: number; end: number } | null } {
  const query = options.query
  if (options.isRegex) {
    try {
      const flags = options.caseSensitive ? 'g' : 'gi'
      const source = options.wholeWord ? `\\b(?:${query})\\b` : query
      const re = new RegExp(source, flags)
      return {
        test(line) {
          re.lastIndex = 0
          const m = re.exec(line)
          if (!m) return null
          return { start: m.index, end: m.index + m[0].length }
        },
      }
    } catch {
      throw new Error('正则表达式无效')
    }
  }
  const needle = options.caseSensitive ? query : query.toLowerCase()
  return {
    test(line) {
      const hay = options.caseSensitive ? line : line.toLowerCase()
      let from = 0
      while (from <= hay.length) {
        const idx = hay.indexOf(needle, from)
        if (idx < 0) return null
        const end = idx + needle.length
        if (options.wholeWord) {
          const before = idx === 0 ? '' : hay[idx - 1]!
          const after = end >= hay.length ? '' : hay[end]!
          const okBefore = !before || /[^0-9a-z_]/i.test(before)
          const okAfter = !after || /[^0-9a-z_]/i.test(after)
          if (okBefore && okAfter) return { start: idx, end }
          from = idx + 1
          continue
        }
        return { start: idx, end }
      }
      return null
    },
  }
}

async function searchHost(options: SearchOptions): Promise<SearchResult> {
  const response = await fetch('/__grain/search', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      query: options.query,
      cwd: options.cwd || undefined,
      caseSensitive: !!options.caseSensitive,
      wholeWord: !!options.wholeWord,
      isRegex: !!options.isRegex,
      maxResults: options.maxResults ?? 2000,
    }),
  })
  const body = await response.json() as SearchResult & { error?: string }
  if (!response.ok) throw new Error(body.error || '搜索失败')
  return {
    files: body.files ?? [],
    truncated: !!body.truncated,
    engine: body.engine || 'ripgrep',
  }
}

async function searchAndroidNative(options: SearchOptions): Promise<SearchResult | null> {
  try {
    const result = await Workspace.searchContent({
      query: options.query,
      cwd: options.cwd || '',
      caseSensitive: !!options.caseSensitive,
      wholeWord: !!options.wholeWord,
      isRegex: !!options.isRegex,
      maxResults: options.maxResults ?? 2000,
    })
    return {
      files: result.files ?? [],
      truncated: !!result.truncated,
      engine: result.engine || 'android',
    }
  } catch {
    return null
  }
}

async function searchAndroidFallback(options: SearchOptions): Promise<SearchResult> {
  const matcher = compileMatcher(options)
  const maxResults = Math.min(Math.max(options.maxResults ?? 2000, 1), 5000)
  const scope = (options.cwd || '').trim().replace(/^\/+|\/+$/g, '')
  const listed = await Workspace.listFiles()
  let paths = (listed.files ?? []).map((item) => item.path)
  if (scope) {
    const prefix = `${scope}/`
    paths = paths.filter((path) => path === scope || path.startsWith(prefix))
  }
  paths = paths.filter((path) => {
    if (BINARY_EXT.test(path)) return false
    return !path.split('/').some((part) => SKIP.has(part))
  })

  const byPath = new Map<string, SearchHit[]>()
  let total = 0
  let truncated = false

  for (const path of paths) {
    if (total >= maxResults) {
      truncated = true
      break
    }
    let content = ''
    try {
      const file = await Workspace.readFile({ path })
      if (!file.exists || file.content == null) continue
      content = file.content
    } catch {
      continue
    }
    if (content.includes('\0')) continue
    const lines = content.split(/\r?\n/)
    for (let i = 0; i < lines.length; i += 1) {
      const line = lines[i] ?? ''
      const match = matcher.test(line)
      if (!match) continue
      const hit: SearchHit = {
        path,
        line: i + 1,
        column: match.start + 1,
        text: line.length > 240 ? `${line.slice(0, 240)}…` : line,
        matchStart: match.start,
        matchEnd: match.end,
      }
      const list = byPath.get(path) ?? []
      list.push(hit)
      byPath.set(path, list)
      total += 1
      if (total >= maxResults) {
        truncated = true
        break
      }
    }
  }

  return {
    files: [...byPath.entries()].map(([path, matches]) => ({ path, matches })),
    truncated,
    engine: 'workspace',
  }
}

export async function searchContent(options: SearchOptions): Promise<SearchResult> {
  const query = options.query.trim()
  if (!query) return { files: [], truncated: false, engine: 'none' }

  if (isAndroidWorkspace) {
    const native = await searchAndroidNative(options)
    if (native) return native
    return searchAndroidFallback(options)
  }

  try {
    return await searchHost(options)
  } catch {
    // 浏览器预览等无 host 搜索时，退回已打开缓冲
    return { files: [], truncated: false, engine: 'none' }
  }
}
