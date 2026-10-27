import { spawn } from 'node:child_process'
import { createRequire } from 'node:module'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { resolve as resolvePath, sep as pathSep } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Connect, Plugin } from 'vite'

const root = fileURLToPath(new URL('..', import.meta.url))
const require = createRequire(import.meta.url)

function rgBinary(): string {
  try {
    const mod = require('@vscode/ripgrep') as { rgPath?: string }
    if (mod.rgPath) return mod.rgPath
  } catch {
    /* fall through */
  }
  return 'rg'
}

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

function sendJson(res: ServerResponse, status: number, body: unknown) {
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.end(JSON.stringify(body))
}

async function readBody(req: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = []
  for await (const chunk of req) chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk))
  return Buffer.concat(chunks).toString('utf8')
}

function runRg(options: {
  query: string
  cwd: string
  caseSensitive?: boolean
  wholeWord?: boolean
  isRegex?: boolean
  maxResults?: number
}): Promise<{ files: SearchFileGroup[]; truncated: boolean; engine: string }> {
  const maxResults = Math.min(Math.max(options.maxResults ?? 2000, 1), 5000)
  const args = [
    '--json',
    '--line-number',
    '--column',
    '--no-heading',
    '--color', 'never',
    '--glob', '!**/node_modules/**',
    '--glob', '!**/.git/**',
    '--glob', '!**/.grain/**',
    '--glob', '!**/dist/**',
    '--glob', '!**/build/**',
    '--glob', '!**/.next/**',
    '--glob', '!**/.nuxt/**',
    '--glob', '!**/coverage/**',
  ]
  if (!options.caseSensitive) args.push('--ignore-case')
  if (options.wholeWord) args.push('--word-regexp')
  if (!options.isRegex) args.push('--fixed-strings')
  args.push('--', options.query, '.')

  return new Promise((resolve, reject) => {
    const child = spawn(rgBinary(), args, {
      cwd: options.cwd,
      env: { ...process.env },
    })
    let stdout = ''
    let stderr = ''
    child.stdout.setEncoding('utf8')
    child.stderr.setEncoding('utf8')
    child.stdout.on('data', (chunk: string) => { stdout += chunk })
    child.stderr.on('data', (chunk: string) => { stderr += chunk })
    child.on('error', (error) => reject(error))
    child.on('close', (code) => {
      // rg exits 1 when no matches, 2 on error
      if (code != null && code > 1) {
        reject(new Error(stderr.trim() || `ripgrep 退出码 ${code}`))
        return
      }
      const byPath = new Map<string, SearchHit[]>()
      let total = 0
      let truncated = false
      for (const line of stdout.split('\n')) {
        if (!line.trim()) continue
        let row: { type?: string; data?: {
          path?: { text?: string }
          line_number?: number
          absolute_offset?: number
          submatches?: { start: number; end: number; match: { text?: string } }[]
          lines?: { text?: string }
        } }
        try {
          row = JSON.parse(line) as typeof row
        } catch {
          continue
        }
        if (row.type !== 'match' || !row.data) continue
        const rel = row.data.path?.text
        if (!rel) continue
        const lineNumber = row.data.line_number ?? 1
        const text = (row.data.lines?.text ?? '').replace(/\r?\n$/, '')
        const sub = row.data.submatches?.[0]
        const matchStart = sub?.start ?? 0
        const matchEnd = sub?.end ?? matchStart
        const hit: SearchHit = {
          path: rel.replaceAll('\\', '/'),
          line: lineNumber,
          column: matchStart + 1,
          text,
          matchStart,
          matchEnd,
        }
        const list = byPath.get(hit.path) ?? []
        list.push(hit)
        byPath.set(hit.path, list)
        total += 1
        if (total >= maxResults) {
          truncated = true
          break
        }
      }
      const files: SearchFileGroup[] = [...byPath.entries()].map(([path, matches]) => ({ path, matches }))
      resolve({ files, truncated, engine: 'ripgrep' })
    })
  })
}

export function devSearch(): Plugin {
  const serve: Connect.NextHandleFunction = (req, res, next) => {
    const pathname = (req.url ?? '').split('?')[0]
    if (pathname !== '/__grain/search') return next()
    if (req.method !== 'POST') {
      sendJson(res, 405, { error: 'Method Not Allowed' })
      return
    }
    void (async () => {
      try {
        const body = JSON.parse(await readBody(req) || '{}') as {
          query?: string
          cwd?: string
          caseSensitive?: boolean
          wholeWord?: boolean
          isRegex?: boolean
          maxResults?: number
        }
        const query = (body.query ?? '').trim()
        if (!query) {
          sendJson(res, 200, { files: [], truncated: false, engine: 'ripgrep' })
          return
        }
        let cwd = root
        if (body.cwd && typeof body.cwd === 'string' && body.cwd.trim()) {
          const candidate = resolvePath(root, body.cwd.trim())
          if (candidate === root || candidate.startsWith(root + pathSep)) cwd = candidate
        }
        const result = await runRg({
          query,
          cwd,
          caseSensitive: !!body.caseSensitive,
          wholeWord: !!body.wholeWord,
          isRegex: !!body.isRegex,
          maxResults: body.maxResults,
        })
        sendJson(res, 200, result)
      } catch (error) {
        sendJson(res, 500, { error: error instanceof Error ? error.message : String(error) })
      }
    })()
  }

  return {
    name: 'grain-dev-search',
    configureServer(server) {
      server.middlewares.use(serve)
    },
    configurePreviewServer(server) {
      server.middlewares.use(serve)
    },
  }
}
