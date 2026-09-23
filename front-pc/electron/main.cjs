const { app, BrowserWindow, ipcMain, shell } = require('electron')
const fs = require('fs')
const path = require('path')
const { createDb } = require('./db.cjs')
const { receiptEscPos, receiptHtml, openDrawerEscPos, printHtml } = require('./print.cjs')

let mainWindow
let db
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
  createWindow()
  app.on('activate', () => { if (BrowserWindow.getAllWindows().length === 0) createWindow() })
})
app.on('window-all-closed', () => { if (process.platform !== 'darwin') app.quit() })
