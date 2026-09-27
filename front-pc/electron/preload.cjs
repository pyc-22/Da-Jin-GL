const { contextBridge, ipcRenderer } = require('electron')
let nextSocketId = 0

contextBridge.exposeInMainWorld('dajin', {
  db: {
    products: keyword => ipcRenderer.invoke('db:products', keyword),
    gold: () => ipcRenderer.invoke('db:gold'),
    members: keyword => ipcRenderer.invoke('db:members', keyword),
    enqueue: item => ipcRenderer.invoke('db:enqueue', item),
    queue: () => ipcRenderer.invoke('db:queue'),
    cancelOrder: (clientRequestId, cancellation) => ipcRenderer.invoke('db:cancel-order', clientRequestId, cancellation),
    queueDone: (id, response) => ipcRenderer.invoke('db:queue-done', id, response),
    queueFailed: (id, message) => ipcRenderer.invoke('db:queue-failed', id, message),
    conflict: item => ipcRenderer.invoke('db:conflict', item),
    conflicts: () => ipcRenderer.invoke('db:conflicts'),
    resolveConflict: (id, resolution) => ipcRenderer.invoke('db:resolve-conflict', id, resolution),
    seed: payload => ipcRenderer.invoke('db:seed', payload)
  },
  print: {
    receipt: model => ipcRenderer.invoke('print:receipt', model),
    preview: model => ipcRenderer.invoke('print:preview', model),
    system: (html, options) => ipcRenderer.invoke('print:system', html, options),
    printers: () => ipcRenderer.invoke('print:printers'),
    log: item => ipcRenderer.invoke('print:log', item)
  },
  config: {
    get: () => ipcRenderer.invoke('config:get'),
    set: value => ipcRenderer.invoke('config:set', value)
  },
  network: {
    request: input => ipcRenderer.invoke('network:request', input),
    uploadPhoto: input => ipcRenderer.invoke('network:upload-photo', input),
    openSocket: (token, handlers) => {
      const id = `${Date.now()}-${++nextSocketId}`
      let closed = false
      const listener = (_event, payload) => {
        if (closed || payload.id !== id) return
        if (payload.type === 'open') handlers.onopen?.()
        if (payload.type === 'message') handlers.onmessage?.({ data: payload.data })
        if (payload.type === 'close') {
          closed = true
          ipcRenderer.removeListener('network:socket-event', listener)
          handlers.onclose?.()
        }
      }
      ipcRenderer.on('network:socket-event', listener)
      ipcRenderer.invoke('network:socket-connect', { token, id }).catch(error => {
        if (closed) return
        closed = true
        ipcRenderer.removeListener('network:socket-event', listener)
        handlers.onerror?.(error)
        handlers.onclose?.()
      })
      return {
        close: () => {
          if (closed) return
          closed = true
          ipcRenderer.removeListener('network:socket-event', listener)
          ipcRenderer.invoke('network:socket-close', id).catch(() => {})
        }
      }
    }
  }
})
