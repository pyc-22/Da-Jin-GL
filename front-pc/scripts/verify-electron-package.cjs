const { app } = require('electron')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')

// Use packaged code/assets and an isolated profile; never open the cashier's live database.
const archive = path.resolve(process.argv[2] || path.join(__dirname, '..', 'release', 'win-unpacked', 'resources', 'app.asar'))
const output = path.resolve(process.argv[3] || path.join(__dirname, '..', '..', 'runtime-logs', 'cashier-package-smoke'))
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'dajin-package-smoke-'))
fs.mkdirSync(output, { recursive: true })
app.setPath('userData', profile)
Object.defineProperty(app, 'isPackaged', { value: true })

const failures = []
let finished = false
function finish(result) {
  if (finished) return
  finished = true
  const report = { ...result, failures, archive, profile }
  fs.writeFileSync(path.join(output, 'report.json'), JSON.stringify(report, null, 2))
  console.log(JSON.stringify(report, null, 2))
  app.exit(result.passed ? 0 : 1)
}

setTimeout(() => finish({ passed: false, reason: 'Window/render timeout' }), 20000)
app.on('browser-window-created', (_event, window) => {
  window.hide()
  const contents = window.webContents
  contents.session.webRequest.onErrorOccurred(details => {
    if (details.url.startsWith('file:')) failures.push({ url: details.url, error: details.error })
  })
  contents.on('render-process-gone', (_event, details) => finish({ passed: false, reason: details.reason }))
  contents.on('did-fail-load', (_event, code, description, url) => failures.push({ code, description, url }))
  contents.once('did-finish-load', async () => {
    try {
      await new Promise(resolve => setTimeout(resolve, 1000))
      const state = await contents.executeJavaScript(`({
        url: location.href,
        mounted: Boolean(document.querySelector('#app .app-shell')),
        heading: document.querySelector('h1')?.textContent,
        cssLoaded: [...document.styleSheets].some(sheet => {
          try { return sheet.href?.startsWith('file:') && sheet.cssRules.length > 0 } catch { return false }
        }),
        preloadReady: typeof window.dajin?.config?.get === 'function',
        resources: performance.getEntriesByType('resource').filter(item => item.name.startsWith('file:')).map(item => item.name)
      })`)
      const screenshot = await contents.capturePage()
      fs.writeFileSync(path.join(output, 'window.png'), screenshot.toPNG())
      const passed = state.url.startsWith('file:') && state.mounted && state.cssLoaded && state.preloadReady && failures.length === 0
      finish({ passed, state })
    } catch (error) {
      finish({ passed: false, reason: error.message })
    }
  })
})

require(path.join(archive, 'electron', 'main.cjs'))
