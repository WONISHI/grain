import { execFileSync, spawn, type ChildProcess } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { existsSync, statSync } from 'node:fs'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { resolve as resolvePath, sep as pathSep } from 'node:path'
import { fileURLToPath } from 'node:url'
import type { Connect, Plugin } from 'vite'

const root = fileURLToPath(new URL('..', import.meta.url))
const processes = new Map<string, ChildProcess>()
const sessions = new Map<string, ChildProcess>()

function version(command: string): string | null {
  try {
    return execFileSync(command, ['-v'], { encoding: 'utf8', timeout: 5000, cwd: root }).trim()
  } catch {
    return null
  }
}

const environment = {
  cwd: root,
  shell: process.env.SHELL || '/bin/sh',
  node: version('node'),
  npm: version('npm'),
}

function isLoopback(req: IncomingMessage): boolean {
  const ip = req.socket.remoteAddress
  return ip === '127.0.0.1' || ip === '::1' || ip === '::ffff:127.0.0.1'
}

function readBody(req: IncomingMessage): Promise<string> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = []
    let size = 0
    req.on('data', (chunk: Buffer) => {
      size += chunk.length
      if (size > 65_536) {
        reject(new Error('请求过大'))
        req.destroy()
        return
      }
      chunks.push(chunk)
    })
    req.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')))
    req.on('error', reject)
  })
}

function killTree(child: ChildProcess) {
  const pid = child.pid
  if (pid == null) return
  try {
    process.kill(-pid, 'SIGTERM')
  } catch {
    child.kill('SIGTERM')
  }
  const timer = setTimeout(() => {
    try { process.kill(-pid, 'SIGKILL') } catch { /* already exited */ }
  }, 1500)
  timer.unref()
}

function sendJson(res: ServerResponse, status: number, body: unknown) {
  res.statusCode = status
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  res.end(JSON.stringify(body))
}

async function handle(req: IncomingMessage, res: ServerResponse) {
  if (!isLoopback(req)) {
    sendJson(res, 403, { error: '本机终端只接受本机连接' })
    return
  }
  const pathname = (req.url ?? '').split('?')[0]
  if (req.method === 'GET' && pathname === '/__grain/terminal/environment') {
    sendJson(res, 200, environment)
    return
  }
  if (req.method === 'POST' && pathname === '/__grain/terminal/open') {
    const body = JSON.parse(await readBody(req) || '{}') as { cols?: number; rows?: number; cwd?: string }
    const id = randomUUID()
    let cwd = root
    if (body.cwd && typeof body.cwd === 'string' && body.cwd.trim()) {
      const candidate = resolvePath(root, body.cwd.trim())
      if ((candidate === root || candidate.startsWith(root + pathSep)) && existsSync(candidate) && statSync(candidate).isDirectory()) {
        cwd = candidate
      }
    }
    const bridge = [
      'import os, pty, select, sys, signal, fcntl, termios, struct',
      'cols = int(os.environ.get("COLUMNS") or "80")',
      'rows = int(os.environ.get("LINES") or "24")',
      'pid, fd = pty.fork()',
      'if pid == 0:',
      '    os.execv("/bin/sh", ["sh", "-i"])',
      'fcntl.ioctl(fd, termios.TIOCSWINSZ, struct.pack("HHHH", rows, cols, 0, 0))',
      'try:',
      '    while True:',
      '        readable, _, _ = select.select([fd, sys.stdin], [], [])',
      '        if fd in readable:',
      '            try:',
      '                data = os.read(fd, 4096)',
      '            except OSError:',
      '                break',
      '            if not data:',
      '                break',
      '            os.write(sys.stdout.fileno(), data)',
      '        if sys.stdin in readable:',
      '            data = os.read(sys.stdin.fileno(), 4096)',
      '            if not data:',
      '                break',
      '            os.write(fd, data)',
      'finally:',
      '    try: os.kill(pid, signal.SIGHUP)',
      '    except OSError: pass',
      '    try: os.waitpid(pid, 0)',
      '    except OSError: pass',
    ].join('\n')
    const child = spawn('python3', ['-c', bridge], {
      cwd,
      env: {
        ...process.env,
        TERM: 'xterm-256color',
        GRAIN_ROOT: root,
        COLUMNS: String(body.cols || 80),
        LINES: String(body.rows || 24),
      },
      detached: true,
      stdio: ['pipe', 'pipe', 'pipe'],
    })
    sessions.set(id, child)
    res.statusCode = 200
    res.setHeader('Content-Type', 'application/x-ndjson; charset=utf-8')
    res.setHeader('Cache-Control', 'no-cache')
    res.setHeader('X-Accel-Buffering', 'no')
    res.setHeader('X-Grain-Terminal', id)
    const send = (event: { type: string; text?: string; code?: number }) => {
      if (!res.writableEnded) res.write(`${JSON.stringify(event)}\n`)
    }
    const data = (chunk: Buffer) => send({ type: 'data', text: chunk.toString('utf8') })
    child.stdout?.on('data', data)
    child.stderr?.on('data', data)
    child.on('error', (error) => send({ type: 'error', text: error.message }))
    child.on('close', (code) => {
      sessions.delete(id)
      send({ type: 'exit', code: code ?? 0 })
      res.end()
    })
    res.on('close', () => {
      if (sessions.get(id) === child) killTree(child)
    })
    return
  }
  if (req.method === 'POST' && pathname === '/__grain/terminal/write') {
    const body = JSON.parse(await readBody(req)) as { id?: string; data?: string }
    const child = body.id ? sessions.get(body.id) : undefined
    if (!child?.stdin) {
      sendJson(res, 404, { error: '终端会话不存在' })
      return
    }
    child.stdin.write(body.data ?? '')
    sendJson(res, 200, { ok: true })
    return
  }
  if (req.method === 'POST' && pathname === '/__grain/terminal/resize') {
    sendJson(res, 200, { ok: true })
    return
  }
  if (req.method === 'POST' && pathname === '/__grain/terminal/interrupt') {
    const body = JSON.parse(await readBody(req)) as { id?: string }
    const child = body.id ? sessions.get(body.id) : undefined
    child?.stdin?.write('\x03')
    sendJson(res, 200, { ok: true })
    return
  }
  if (req.method === 'POST' && pathname === '/__grain/terminal/close') {
    const body = JSON.parse(await readBody(req)) as { id?: string }
    const child = body.id ? sessions.get(body.id) : undefined
    if (child) killTree(child)
    sendJson(res, 200, { ok: true })
    return
  }
  if (req.method === 'POST' && pathname === '/__grain/terminal/stop') {
    const body = JSON.parse(await readBody(req)) as { id?: string }
    const child = body.id ? processes.get(body.id) : undefined
    if (child) killTree(child)
    sendJson(res, 200, { ok: true })
    return
  }
  if (req.method !== 'POST' || pathname !== '/__grain/terminal/exec') {
    sendJson(res, 404, { error: '未知终端接口' })
    return
  }

  const body = JSON.parse(await readBody(req)) as { id?: string; command?: string }
  const id = body.id?.trim() ?? ''
  const command = body.command?.trim() ?? ''
  if (!id || !command) {
    sendJson(res, 400, { error: '缺少命令或任务编号' })
    return
  }
  if ([...processes.values()].some((child) => child.exitCode == null && !child.killed)) {
    sendJson(res, 409, { error: '已有命令正在执行，请先停止' })
    return
  }

  const child = spawn('/bin/sh', ['-c', command], {
    cwd: root,
    env: process.env,
    detached: true,
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  processes.set(id, child)
  res.statusCode = 200
  res.setHeader('Content-Type', 'application/x-ndjson; charset=utf-8')
  res.setHeader('Cache-Control', 'no-cache')
  res.setHeader('X-Accel-Buffering', 'no')

  let settled = false
  const finish = (event: { type: string; text?: string; code?: number }) => {
    if (settled) return
    settled = true
    processes.delete(id)
    res.write(`${JSON.stringify(event)}\n`)
    res.end()
  }
  const data = (chunk: Buffer) => {
    if (!settled) res.write(`${JSON.stringify({ type: 'data', text: chunk.toString('utf8') })}\n`)
  }
  child.stdout?.on('data', data)
  child.stderr?.on('data', data)
  child.on('error', (error) => finish({ type: 'error', text: error.message }))
  child.on('close', (code) => finish({ type: 'exit', code: code ?? 0 }))
  res.on('close', () => {
    if (!settled) killTree(child)
  })
}

/** Dev-server shell. Loopback only, so the LAN-facing Vite host cannot run commands. */
export function devTerminal(): Plugin {
  const serve: Connect.NextHandleFunction = (req, res, next) => {
    const pathname = req.url?.split('?')[0] ?? ''
    if (!pathname.startsWith('/__grain/terminal')) return next()
    handle(req, res).catch((error: unknown) => {
      if (!res.headersSent) sendJson(res, 500, { error: error instanceof Error ? error.message : String(error) })
    })
  }

  return {
    name: 'grain-dev-terminal',
    configureServer(server) {
      server.middlewares.use(serve)
      server.httpServer?.on('close', () => {
        for (const child of processes.values()) killTree(child)
        for (const child of sessions.values()) killTree(child)
        processes.clear()
        sessions.clear()
      })
    },
  }
}
