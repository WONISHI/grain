const fs = require('fs')
const path = require('path')
const watcher = require('@parcel/watcher')

const root = process.argv[2]
if (!root) {
  fs.writeSync(2, '缺少工作区路径\n')
  process.exit(1)
}

function emit(payload) {
  fs.writeSync(1, JSON.stringify(payload) + '\n')
}

function relative(file) {
  const rel = path.relative(root, file)
  if (!rel || rel.startsWith('..') || path.isAbsolute(rel)) return ''
  return rel.split(path.sep).join('/')
}

const ignore = [
  '**/.git/**',
  '**/dist/**',
  '**/coverage/**',
  '**/.cache/**',
  '**/.gradle/**',
  '**/.idea/**',
  '**/.nuxt/**',
  '**/.output/**',
  '**/.next/**',
]

watcher.subscribe(root, (err, events) => {
  if (err) {
    emit({ error: err.message || String(err) })
    return
  }
  const mapped = []
  for (const event of events) {
    const rel = relative(event.path)
    if (!rel || rel.split('/').some((part) => part === '.git')) continue
    mapped.push({ type: event.type, path: rel })
  }
  if (mapped.length) emit({ events: mapped })
}, { ignore }).then(() => {
  emit({ ready: true })
}).catch((err) => {
  emit({ error: err.message || String(err) })
  process.exit(1)
})
