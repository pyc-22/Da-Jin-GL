// Debug emulator only. Forward its webview_devtools_remote socket to 9223 first.
const expression = process.argv[2]
if (!expression) throw new Error('Supply a JavaScript inspection expression')
const pages = await (await fetch('http://127.0.0.1:9223/json')).json()
const page = pages.find(item => item.url?.startsWith('https://m.xinchengjinjiang.com/'))
if (!page) throw new Error('Dajin debug WebView not found')
const socket = new WebSocket(page.webSocketDebuggerUrl)
const timeout = setTimeout(() => { socket.close(); console.error('WebView inspection timed out'); process.exitCode = 1 }, 20000)
socket.addEventListener('open', () => socket.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression, returnByValue: true, awaitPromise: true } })))
socket.addEventListener('message', event => {
  const message = JSON.parse(event.data)
  if (message.id !== 1) return
  clearTimeout(timeout)
  if (message.error || message.result?.exceptionDetails) { console.error(JSON.stringify(message)); process.exitCode = 1 }
  else console.log(JSON.stringify(message.result?.result?.value ?? message.result?.result))
  socket.close()
})
