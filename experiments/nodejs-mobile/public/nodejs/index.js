/**
 * Grain on-device Node entry.
 *
 * @capawesome/capacitor-nodejs marks the runtime ready when this file
 * requires the built-in `bridge` module. `channel` talks to the Vue shell:
 *   channel.on(eventName, ...args)   messages from Nodejs.send()
 *   channel.post(eventName, ...args) messages to the `message` listener
 *
 * This process is nodejs-mobile, not a desktop Node. Do not call
 * child_process (including `npm install`). Persistent files belong in
 * app.datadir(); the project directory is replaced on app update.
 * The runtime can be started only once per app launch.
 */

const { app, channel } = require('bridge')

const dataDir = app.datadir()

channel.post('grain:log', {
  level: 'info',
  message: 'Grain Node 已连接 bridge',
  datadir: dataDir,
})

channel.on('grain:ping', (payload) => {
  channel.post('grain:log', {
    level: 'info',
    message: 'pong',
    echo: payload === undefined ? null : payload,
  })
})

app.on('pause', (pauseLock) => {
  pauseLock.release()
})
