export type LogLevel = 'info' | 'warn' | 'error'
export type LogSource = 'device' | 'stub' | 'shell'

export interface LogLine {
  id: number
  time: string
  source: LogSource
  level: LogLevel
  eventName: string
  message: string
  detail: string
}
