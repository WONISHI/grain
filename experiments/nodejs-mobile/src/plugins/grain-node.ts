import { Capacitor, type PluginListenerHandle } from '@capacitor/core'
import {
  Nodejs,
  type MessageArg,
  type MessageEvent,
} from '@capawesome/capacitor-nodejs'

/** Channel events handled by public/nodejs/index.js. */
export const GRAIN_PING_EVENT = 'grain:ping'
export const GRAIN_LOG_EVENT = 'grain:log'

export type { MessageArg, MessageEvent }

export type RuntimeKind = 'android' | 'ios' | 'browser'

export function runtimeKind(): RuntimeKind {
  const platform = Capacitor.getPlatform()
  if (platform === 'android' || platform === 'ios') return platform
  return 'browser'
}

export function isDeviceRuntime(): boolean {
  return runtimeKind() !== 'browser'
}

type MessageListener = (event: MessageEvent) => void

const stubListeners = new Set<MessageListener>()

function emitStub(eventName: string, args: MessageArg[]): void {
  const event: MessageEvent = { eventName, args }
  for (const listener of stubListeners) listener(event)
}

/**
 * Resolves true after the device script has required('bridge').
 * The browser build of the plugin throws Unimplemented, so this returns
 * false there without calling it.
 */
export async function waitReady(timeoutMs = 10_000): Promise<boolean> {
  if (!isDeviceRuntime()) return false

  const first = await Nodejs.isReady()
  if (first.ready) return true

  return new Promise((resolve, reject) => {
    let settled = false
    let handle: PluginListenerHandle | undefined

    const finish = (value: boolean) => {
      if (settled) return
      settled = true
      window.clearTimeout(timer)
      void handle?.remove()
      resolve(value)
    }

    const timer = window.setTimeout(() => finish(false), timeoutMs)

    Nodejs.addListener('ready', () => finish(true))
      .then(async (next) => {
        handle = next
        if (settled) {
          await next.remove()
          return
        }
        const again = await Nodejs.isReady()
        if (again.ready) finish(true)
      })
      .catch((error: unknown) => {
        if (settled) return
        settled = true
        window.clearTimeout(timer)
        void handle?.remove()
        reject(error)
      })
  })
}

/**
 * Manual start. On Android/iOS this calls Nodejs.start() once per launch.
 * In the browser it only emits a grain:log stub.
 */
export async function start(): Promise<void> {
  if (!isDeviceRuntime()) {
    emitStub(GRAIN_LOG_EVENT, [
      {
        level: 'warn',
        source: 'stub',
        message: '浏览器桩：没有调用 Nodejs.start()',
        note: 'Node 运行时只在 Android / iOS 上存在，且每次进程只能启动一次。',
      },
    ])
    return
  }

  await Nodejs.start()
}

/** Send a channel event. Browser grain:ping is echoed locally as grain:log. */
export async function send(eventName: string, args: MessageArg[] = []): Promise<void> {
  if (!isDeviceRuntime()) {
    if (eventName === GRAIN_PING_EVENT) {
      const echo = args[0] === undefined ? null : args[0]
      emitStub(GRAIN_LOG_EVENT, [
        {
          level: 'info',
          source: 'stub',
          message: 'pong',
          echo,
          note: '浏览器桩复现 grain:ping → grain:log。设备上由 public/nodejs/index.js 处理。',
        },
      ])
      return
    }

    emitStub(GRAIN_LOG_EVENT, [
      {
        level: 'warn',
        source: 'stub',
        message: `浏览器桩没有实现 ${eventName}`,
      },
    ])
    return
  }

  await Nodejs.send({ eventName, args })
}

/** Listen for channel messages. Returns an unlisten function. */
export async function addMessageListener(
  listener: MessageListener,
): Promise<() => Promise<void>> {
  if (!isDeviceRuntime()) {
    stubListeners.add(listener)
    return async () => {
      stubListeners.delete(listener)
    }
  }

  const handle = await Nodejs.addListener('message', listener)
  return () => handle.remove()
}
