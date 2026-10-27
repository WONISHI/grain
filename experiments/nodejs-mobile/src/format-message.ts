import type { MessageArg, MessageEvent } from '@capawesome/capacitor-nodejs'
import type { LogLevel, LogSource } from './types'

type ArgRecord = { [key: string]: MessageArg }

function isRecord(value: MessageArg | undefined): value is ArgRecord {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function stringifyArg(value: MessageArg | undefined): string {
  if (typeof value === 'string') return value
  if (value === undefined) return ''
  return JSON.stringify(value)
}

function readLevel(record: ArgRecord | null): LogLevel {
  const level = record?.level
  if (level === 'info' || level === 'warn' || level === 'error') return level
  return 'info'
}

function detailFrom(record: ArgRecord | null): string {
  if (!record) return ''
  const parts: string[] = []
  if (typeof record.note === 'string') parts.push(record.note)
  if ('echo' in record) parts.push(`echo ${stringifyArg(record.echo)}`)
  if (typeof record.datadir === 'string') parts.push(record.datadir)
  return parts.join(' · ')
}

export function messageToLog(event: MessageEvent): {
  source: LogSource
  level: LogLevel
  eventName: string
  message: string
  detail: string
} {
  const record = isRecord(event.args[0]) ? event.args[0] : null
  const message =
    record && typeof record.message === 'string'
      ? record.message
      : event.args.map((arg) => stringifyArg(arg)).join(' ')

  return {
    source: record?.source === 'stub' ? 'stub' : 'device',
    level: readLevel(record),
    eventName: event.eventName,
    message,
    detail: detailFrom(record),
  }
}
