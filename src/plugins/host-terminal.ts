export interface HostEnvironment {
  cwd: string
  shell: string
  node: string | null
  npm: string | null
}

export interface HostEvent {
  type: 'data' | 'exit' | 'error'
  text?: string
  code?: number
}

export async function hostEnvironment(): Promise<HostEnvironment> {
  const response = await fetch('/__grain/terminal/environment')
  if (!response.ok) throw new Error('本机终端不可用')
  return response.json() as Promise<HostEnvironment>
}

export async function hostTerminalOpen(
  cols: number,
  rows: number,
  onEvent: (event: HostEvent) => void,
  signal: AbortSignal,
  cwd?: string,
): Promise<string> {
  const response = await fetch('/__grain/terminal/open', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ cols, rows, cwd: cwd || undefined }),
    signal,
  })
  if (!response.ok || !response.body) {
    const body = await response.json().catch(() => ({ error: response.statusText })) as { error?: string }
    throw new Error(body.error || '终端没有启动')
  }
  const id = response.headers.get('X-Grain-Terminal') ?? ''
  if (!id) throw new Error('终端没有返回会话')
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  void (async () => {
    try {
      while (true) {
        const { done, value } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        const lines = buffer.split('\n')
        buffer = lines.pop() ?? ''
        for (const line of lines) {
          if (!line.trim()) continue
          onEvent(JSON.parse(line) as HostEvent)
        }
      }
    } catch (error) {
      if (!signal.aborted) onEvent({ type: 'error', text: error instanceof Error ? error.message : String(error) })
    }
  })()
  return id
}

export async function hostTerminalWrite(id: string, data: string): Promise<void> {
  const response = await fetch('/__grain/terminal/write', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id, data }),
  })
  if (!response.ok) throw new Error('无法写入终端')
}

export async function hostTerminalResize(id: string, cols: number, rows: number): Promise<void> {
  await fetch('/__grain/terminal/resize', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id, cols, rows }),
  })
}

export async function hostTerminalInterrupt(id: string): Promise<void> {
  await fetch('/__grain/terminal/interrupt', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id }),
  })
}

export async function hostTerminalClose(id: string): Promise<void> {
  await fetch('/__grain/terminal/close', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id }),
  })
}

export async function hostStop(id: string): Promise<void> {
  await fetch('/__grain/terminal/stop', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id }),
  })
}

export async function hostExecute(
  id: string,
  command: string,
  onEvent: (event: HostEvent) => void,
): Promise<void> {
  const response = await fetch('/__grain/terminal/exec', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ id, command }),
  })
  if (!response.ok || !response.body) {
    const body = await response.json().catch(() => ({ error: response.statusText })) as { error?: string }
    throw new Error(body.error || '命令没有启动')
  }
  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() ?? ''
    for (const line of lines) {
      if (!line.trim()) continue
      onEvent(JSON.parse(line) as HostEvent)
    }
  }
}
