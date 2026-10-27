import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import vue from '@vitejs/plugin-vue'
import type { Connect, Plugin } from 'vite'
import { defineConfig } from 'vite'
import { devSearch } from './server/dev-search.js'
import { devTerminal } from './server/dev-terminal.js'

const materialIconDir = fileURLToPath(new URL('./node_modules/material-icon-theme/icons', import.meta.url))

function materialIcons(): Plugin {
  const serve: Connect.NextHandleFunction = (req, res, next) => {
    const pathname = req.url?.split('?')[0] ?? ''
    if (!pathname.startsWith('/material-icons/')) return next()
    const name = path.basename(decodeURIComponent(pathname))
    if (!name.endsWith('.svg')) return next()
    res.setHeader('Content-Type', 'image/svg+xml')
    res.setHeader('Cache-Control', 'public, max-age=31536000, immutable')
    fs.createReadStream(path.join(materialIconDir, name))
      .on('error', () => next())
      .pipe(res)
  }

  return {
    name: 'grain-material-icons',
    configureServer(server) {
      server.middlewares.use(serve)
    },
    configurePreviewServer(server) {
      server.middlewares.use(serve)
    },
    closeBundle() {
      fs.cpSync(materialIconDir, path.resolve('dist/material-icons'), { recursive: true })
    },
  }
}

export default defineConfig({
  plugins: [vue(), materialIcons(), devTerminal(), devSearch()],
  resolve: {
    alias: {
      'monaco-editor/editor.main.css': fileURLToPath(new URL('./node_modules/monaco-editor/min/vs/editor/editor.main.css', import.meta.url)),
    },
  },
  build: {
    chunkSizeWarningLimit: 8000,
  },
  server: {
    host: '0.0.0.0',
    port: 43127,
    strictPort: true,
  },
  preview: {
    host: '0.0.0.0',
    port: 43128,
    strictPort: true,
  },
})
