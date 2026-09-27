const { app, BrowserWindow, ipcMain, shell } = require('electron')
const fs = require('fs')
const path = require('path')
const WebSocket = require('ws')
const { createDb } = require('./db.cjs')
const { backendRequest, uploadPhoto, socketUrl } = require('./backend-network.cjs')
const { receiptEscPos, receiptHtml, openDrawerEscPos, printHtml } = require('./print.cjs')

let mainWindow
let db
const sockets = new Map()
function closeSocket(socket) {
  if (!socket) return
  if (socket.readyState === WebSocket.CONNECTING) socket.terminate()
  else socket.close()
}
app.setName('打金店收银台')
const hasSingleInstanceLock = app.requestSingleInstanceLock()
const configFile = () => path.join(app.getPath('userData'), 'config.json')
function normalizeConfig(value) {
  const next = value && typeof value === 'object' ? { ...value } : {}
  const base = next.apiBase || next.apiBaseUrl
  if (base) { next.apiBase = base; next.apiBaseUrl = base }
  return next
}
function readConfig() { try { return normalizeConfig(JSON.parse(fs.readFileSync(configFile(), 'utf8'))) } catch { return {} } }
function writeConfig(value) {
  const next = normalizeConfig({ ...readConfig(), ...(value || {}) })
  fs.mkdirSync(path.dirname(configFile()), { recursive: true })
  fs.writeFileSync(configFile(), JSON.stringify(next, null, 2), 'utf8')
  return next
}

function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1440,
    height: 900,
    minWidth: 1120,
    minHeight: 720,
    backgroundColor: '#f5f4f0',
    webPreferences: { preload: path.join(__dirname, 'preload.cjs'), contextIsolation: true, nodeIntegration: false }
  })
  // Relay renderer diagnostics to the Electron terminal so API/WS failures are observable in development.
  mainWindow.webContents.on('console-message', (_event, level, message, line, sourceId) => {
    console.info(`[dajin-renderer:${level}] ${message} (${sourceId}:${line})`)
  })
  const devUrl = process.env.VITE_DEV_SERVER_URL || 'http://127.0.0.1:5173'
  if (!app.isPackaged) mainWindow.loadURL(devUrl)
  else mainWindow.loadFile(path.join(__dirname, '..', 'dist', 'index.html'))
  mainWindow.webContents.setWindowOpenHandler(({ url }) => { shell.openExternal(url); return { action: 'deny' } })
}

if (!hasSingleInstanceLock) app.quit()

app.on('second-instance', () => {
  if (!mainWindow) return
  if (mainWindow.isMinimized()) mainWindow.restore()
  mainWindow.show()
  mainWindow.focus()
})

if (hasSingleInstanceLock) app.whenReady().then(async () => {
  console.info('[dajin-config] userData:', app.getPath('userData'))
  console.info('[dajin-config] configFile:', configFile())
  db = await createDb(path.join(app.getPath('userData'), 'dajin-local.sqlite'))
  ipcMain.handle('db:products', (_, keyword = '') => db.products(keyword))
  ipcMain.handle('db:gold', () => db.gold())
  ipcMain.handle('db:members', (_, keyword = '') => db.members(keyword))
  ipcMain.handle('db:enqueue', (_, item) => db.enqueue(item))
  ipcMain.handle('db:queue', () => db.queue())
  ipcMain.handle('db:cancel-order', (_, clientRequestId, cancellation) => db.cancelOrder(clientRequestId, cancellation))
  ipcMain.handle('db:queue-done', (_, id, response) => db.queueDone(id, response))
  ipcMain.handle('db:queue-failed', (_, id, message) => db.queueFailed(id, message))
  ipcMain.handle('db:conflict', (_, item) => db.conflict(item))
  ipcMain.handle('db:conflicts', () => db.conflicts())
  ipcMain.handle('db:resolve-conflict', (_, id, resolution) => db.resolveConflict(id, resolution))
  ipcMain.handle('db:seed', (_, payload) => db.seed(payload))
  ipcMain.handle('print:receipt', async (_, model = {}) => ({ ...receiptEscPos(model), print: await printHtml(mainWindow, receiptHtml(model), model), drawer: openDrawerEscPos() }))
  ipcMain.handle('print:preview', (_, model) => receiptEscPos(model))
  ipcMain.handle('print:system', (_, html, options = {}) => printHtml(mainWindow, html, options))
  ipcMain.handle('print:printers', () => mainWindow.webContents.getPrintersAsync())
  ipcMain.handle('print:log', (_, item) => db.printLog(item))
  ipcMain.handle('config:get', () => readConfig())
  ipcMain.handle('config:set', (_, value) => writeConfig(value))
  ipcMain.handle('network:request', (_, input) => backendRequest(readConfig().apiBase, input))
  ipcMain.handle('network:upload-photo', (_, input) => uploadPhoto(readConfig().apiBase, input))
  ipcMain.handle('network:socket-connect', (event, { token, id }) => {
    if (typeof id !== 'string' || !/^\d+-\d+$/.test(id)) throw new Error('连接标识无效')
    const sender = event.sender
    const previous = sockets.get(sender.id)
    const socket = new WebSocket(socketUrl(readConfig().apiBase, token), { handshakeTimeout: 10000 })
    socket.dajinId = id
    sockets.set(sender.id, socket)
    closeSocket(previous)
    const relay = payload => { if (!sender.isDestroyed() && sockets.get(sender.id) === socket) sender.send('network:socket-event', { ...payload, id }) }
    socket.on('open', () => relay({ type: 'open' }))
    socket.on('message', data => relay({ type: 'message', data: data.toString() }))
    socket.on('error', error => console.warn('[dajin-ws] connection error:', error.message))
    socket.on('close', () => {
      relay({ type: 'close' })
      if (sockets.get(sender.id) === socket) sockets.delete(sender.id)
    })
    const onDestroyed = () => { if (sockets.get(sender.id) === socket) closeSocket(socket) }
    sender.once('destroyed', onDestroyed)
    socket.once('close', () => sender.removeListener('destroyed', onDestroyed))
  })
  ipcMain.handle('network:socket-close', (event, id) => {
    const socket = sockets.get(event.sender.id)
    if (socket?.dajinId !== id) return
    sockets.delete(event.sender.id)
    closeSocket(socket)
  })
  createWindow()
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow() })
})
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
