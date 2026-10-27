/** 内置静态文件服务脚本（CommonJS，兼容 Grain 内置 Node） */
export const STATIC_SERVER_PATH = '.grain/static-server.cjs'

export const STATIC_SERVER_SOURCE = `#!/usr/bin/env node
'use strict'
const http = require('http')
const fs = require('fs')
const path = require('path')

const port = Number(process.argv[2] || 5173)
const host = process.argv[3] || '127.0.0.1'
const root = process.cwd()

const mime = {
  '.html': 'text/html; charset=utf-8',
  '.htm': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.mjs': 'text/javascript; charset=utf-8',
  '.cjs': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.map': 'application/json; charset=utf-8',
  '.png': 'image/png',
  '.jpg': 'image/jpeg',
  '.jpeg': 'image/jpeg',
  '.gif': 'image/gif',
  '.webp': 'image/webp',
  '.svg': 'image/svg+xml',
  '.ico': 'image/x-icon',
  '.woff': 'font/woff',
  '.woff2': 'font/woff2',
  '.ttf': 'font/ttf',
  '.txt': 'text/plain; charset=utf-8',
  '.wasm': 'application/wasm',
}

function safeResolve(urlPath) {
  const decoded = decodeURIComponent(String(urlPath || '/').split('?')[0])
  const joined = path.normalize(path.join(root, decoded))
  if (joined !== root && !joined.startsWith(root + path.sep)) return null
  return joined
}

function send(res, status, type, body) {
  res.writeHead(status, {
    'Content-Type': type || 'text/plain; charset=utf-8',
    'Cache-Control': 'no-store',
    'Access-Control-Allow-Origin': '*',
  })
  res.end(body)
}

function serveFile(file, res) {
  fs.readFile(file, (error, data) => {
    if (error) {
      send(res, 404, 'text/plain; charset=utf-8', 'Not found')
      return
    }
    const ext = path.extname(file).toLowerCase()
    send(res, 200, mime[ext] || 'application/octet-stream', data)
  })
}

const server = http.createServer((req, res) => {
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'GET,HEAD,OPTIONS',
      'Access-Control-Allow-Headers': '*',
    })
    res.end()
    return
  }
  let urlPath = req.url || '/'
  if (urlPath === '/') urlPath = '/index.html'
  else if (urlPath.endsWith('/')) urlPath += 'index.html'
  const target = safeResolve(urlPath)
  if (!target) {
    send(res, 403, 'text/plain; charset=utf-8', 'Forbidden')
    return
  }
  fs.stat(target, (error, stats) => {
    if (!error && stats.isDirectory()) {
      serveFile(path.join(target, 'index.html'), res)
      return
    }
    if (!error && stats.isFile()) {
      serveFile(target, res)
      return
    }
    send(res, 404, 'text/plain; charset=utf-8', 'Not found')
  })
})

server.on('error', (error) => {
  console.error('[Grain] static server error:', error && error.message ? error.message : error)
  process.exit(1)
})

server.listen(port, host, () => {
  const url = 'http://' + host + ':' + port + '/'
  console.log('[Grain] static server ready')
  console.log('[Grain] ' + url)
})
`
