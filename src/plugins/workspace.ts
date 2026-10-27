import { Capacitor, registerPlugin, type PluginListenerHandle } from '@capacitor/core'

export interface WorkspaceEnvironment {
  platform: 'android'
  abi: string
  workspace: string
  nodePresent: boolean
  npmPresent: boolean
  shellAvailable: boolean
  runtimeVerified: boolean
}

export interface CommandOutput {
  id: string
  type: 'data' | 'exit' | 'error'
  text?: string
  code?: number
}

export interface WorkspaceFileEvent {
  events?: { type: 'create' | 'update' | 'delete'; path: string }[]
  error?: string
}

export interface WebConsoleEvent {
  level?: string
  text?: string
  source?: string
  line?: number
}

export interface WebNetworkEvent {
  method?: string
  url?: string
  status?: number
  size?: number
  duration?: number
  main?: boolean
  type?: string
  error?: string
}

export interface WorkspaceDirEntry {
  name: string
  path: string
  kind: 'file' | 'dir'
}

interface WorkspacePlugin {
  environment(): Promise<WorkspaceEnvironment>
  listFiles(): Promise<{ files: { path: string }[] }>
  listDirectory(options: { path: string }): Promise<{ entries: WorkspaceDirEntry[] }>
  watchWorkspace(): Promise<void>
  readFile(options: { path: string }): Promise<{ exists: boolean; content?: string }>
  writeFile(options: { path: string; content: string }): Promise<void>
  clipboardRead(): Promise<{ text: string }>
  clipboardWrite(options: { text: string }): Promise<void>
  deletePath(options: { path: string }): Promise<void>
  clearWorkspace(): Promise<void>
  promoteDirectory(options: { path: string }): Promise<void>
  renamePath(options: { from: string; to: string }): Promise<void>
  pickFiles(): Promise<{ files: { path: string; content: string }[] }>
  pickDirectory(): Promise<{ opened?: boolean; name?: string; files: { path: string; content: string }[] }>
  execute(options: { id: string; command: string }): Promise<void>
  input(options: { id: string; data: string }): Promise<void>
  stop(options: { id: string }): Promise<void>
  terminalOpen(options: { id?: string; cols: number; rows: number; cwd?: string }): Promise<{ id: string }>
  terminalWrite(options: { id: string; data: string }): Promise<void>
  terminalResize(options: { id: string; cols: number; rows: number }): Promise<void>
  terminalInterrupt(options: { id: string }): Promise<void>
  terminalClose(options: { id: string }): Promise<void>
  fetchUrl(options: { url: string }): Promise<{ status: number; text: string; contentType?: string }>
  searchContent(options: {
    query: string
    cwd?: string
    caseSensitive?: boolean
    wholeWord?: boolean
    isRegex?: boolean
    maxResults?: number
  }): Promise<{
    files: Array<{
      path: string
      matches: Array<{
        path: string
        line: number
        column: number
        text: string
        matchStart: number
        matchEnd: number
      }>
    }>
    truncated?: boolean
    engine?: string
  }>
  addListener(name: 'output', listener: (event: CommandOutput) => void): Promise<PluginListenerHandle>
  addListener(name: 'files', listener: (event: WorkspaceFileEvent) => void): Promise<PluginListenerHandle>
  addListener(name: 'webConsole', listener: (event: WebConsoleEvent) => void): Promise<PluginListenerHandle>
  addListener(name: 'webNetwork', listener: (event: WebNetworkEvent) => void): Promise<PluginListenerHandle>
}

export const isAndroidWorkspace = Capacitor.getPlatform() === 'android'
export const Workspace = registerPlugin<WorkspacePlugin>('GrainWorkspace')
