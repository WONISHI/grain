import { cpSync, mkdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { execFileSync } from 'node:child_process'

const root = join(dirname(fileURLToPath(import.meta.url)), '..')
const staging = join(root, 'android/app/build/watcher-staging')
const output = join(root, 'android/app/src/main/assets/watcher.zip')
const binding = join(root, 'android/app/src/main/watcher/watcher.node')
const runtime = join(root, 'android/app/src/main/watcher/libc++_shared.so')

rmSync(staging, { recursive: true, force: true })
mkdirSync(join(staging, 'node_modules/@parcel'), { recursive: true })
mkdirSync(join(staging, 'lib'), { recursive: true })

function copyPackage(name, files) {
  const from = join(root, 'node_modules', name)
  const to = join(staging, 'node_modules', name)
  mkdirSync(to, { recursive: true })
  for (const file of files) cpSync(join(from, file), join(to, file), { recursive: true })
}

copyPackage('@parcel/watcher', ['package.json', 'index.js', 'wrapper.js'])
copyPackage('picomatch', ['package.json', 'index.js', 'posix.js', 'lib'])
copyPackage('is-glob', ['package.json', 'index.js'])
copyPackage('is-extglob', ['package.json', 'index.js'])

const androidPackage = join(staging, 'node_modules/@parcel/watcher-android-arm64')
mkdirSync(androidPackage, { recursive: true })
writeFileSync(join(androidPackage, 'package.json'), JSON.stringify({
  name: '@parcel/watcher-android-arm64',
  version: '2.6.0',
  main: 'index.js',
}, null, 2))
writeFileSync(join(androidPackage, 'index.js'), `const library = process.env.GRAIN_WATCHER
if (!library) throw new Error('缺少 GRAIN_WATCHER')
const binding = { exports: {}, filename: library, id: '@parcel/watcher-android-arm64' }
process.dlopen(binding, library)
module.exports = binding.exports
`)

writeFileSync(join(staging, 'watch.js'), readFileSync(join(root, 'scripts/watcher-entry.js')))
cpSync(binding, join(staging, 'lib/watcher.node'))
cpSync(runtime, join(staging, 'lib/libc++_shared.so'))

execFileSync('zip', ['-qr', '-X', output, '.'], { cwd: staging })
console.log(`wrote ${output}`)
