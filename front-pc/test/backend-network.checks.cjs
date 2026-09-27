const { test } = require('node:test')
const assert = require('node:assert/strict')
const { EventEmitter } = require('node:events')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { backendUrl, backendRequest, uploadPhoto, socketUrl } = require('../electron/backend-network.cjs')

const BASE = 'https://admin.xinchengjinjiang.com'

test('requests stay on configured backend and preserve status/body', async () => {
  let called
  const result = await backendRequest(BASE, {
    path: '/api/auth/login', method: 'POST',
    headers: { 'Content-Type': 'application/json' }, body: '{"username":"test"}'
  }, async (url, options) => {
    called = { url: url.toString(), options }
    return new Response('{"code":401,"message":"bad credentials"}', { status: 401 })
  })
  assert.equal(called.url, `${BASE}/api/auth/login`)
  assert.equal(called.options.redirect, 'manual')
  assert.equal(called.options.headers.origin, undefined)
  assert.equal(called.options.body, '{"username":"test"}')
  assert.deepEqual(result, { status: 401, body: '{"code":401,"message":"bad credentials"}' })
})

test('health check is allowed but external and non-API paths are rejected', () => {
  assert.equal(backendUrl(BASE, '/actuator/health').toString(), `${BASE}/actuator/health`)
  for (const path of ['https://other.example/api/x', '//other.example/api/x', '/uploads/file', '/api/../admin', '/api/x#fragment']) {
    assert.throws(() => backendUrl(BASE, path))
  }
  assert.throws(() => backendUrl('file:///tmp/app', '/api/x'))
  assert.throws(() => backendUrl('https://user:pass@example.com', '/api/x'))
})

test('upload sends multipart image and does not expose other destinations', async () => {
  const result = await uploadPhoto(BASE, {
    bytes: Uint8Array.from([1, 2, 3]), filename: 'photo.jpg', type: 'image/jpeg',
    orderNo: 'PROC-1', authorization: 'Bearer test-token'
  }, async (url, options) => {
    assert.equal(url.toString(), `${BASE}/api/upload`)
    assert.equal(options.headers.authorization, 'Bearer test-token')
    assert.equal(options.body.get('bizType'), 'processing')
    assert.equal(options.body.get('orderNo'), 'PROC-1')
    assert.equal(options.body.get('file').size, 3)
    return new Response('{"code":200}', { status: 200 })
  })
  assert.equal(result.status, 200)
  await assert.rejects(uploadPhoto(BASE, { bytes: Uint8Array.from([1]), type: 'text/plain' }), /图片/)
})

test('rejects oversized responses, including upload responses without content-length', async () => {
  const oversized = () => new Response('x'.repeat(10 * 1024 * 1024 + 1), { status: 200 })
  await assert.rejects(backendRequest(BASE, { path: '/api/test' }, oversized), /响应内容过大/)
  await assert.rejects(uploadPhoto(BASE, {
    bytes: Uint8Array.from([1]), type: 'image/jpeg'
  }, oversized), /响应内容过大/)
})

test('websocket targets the same backend and carries the session token', () => {
  const url = new URL(socketUrl(BASE, 'abc.123'))
  assert.equal(url.origin, 'wss://admin.xinchengjinjiang.com')
  assert.equal(url.pathname, '/ws')
  assert.equal(url.searchParams.get('token'), 'abc.123')
  assert.throws(() => socketUrl(BASE, ''), /凭据/)
})

test('preload socket only forwards its own events and closes its own connection', async () => {
  const ipc = new EventEmitter()
  const calls = []
  ipc.invoke = async (...args) => { calls.push(args) }
  let exposed
  const source = readFileSync(path.join(__dirname, '../electron/preload.cjs'), 'utf8')
  vm.runInNewContext(source, {
    require: name => name === 'electron'
      ? { contextBridge: { exposeInMainWorld: (_name, value) => { exposed = value } }, ipcRenderer: ipc }
      : require(name)
  })
  const received = []
  const socket = exposed.network.openSocket('token', { onmessage: event => received.push(event.data) })
  await new Promise(resolve => setImmediate(resolve))
  const id = calls[0][1].id
  ipc.emit('network:socket-event', {}, { id: 'other', type: 'message', data: 'stale' })
  ipc.emit('network:socket-event', {}, { id, type: 'message', data: 'current' })
  assert.deepEqual(received, ['current'])
  socket.close()
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(calls.at(-1), ['network:socket-close', id])
  assert.equal(ipc.listenerCount('network:socket-event'), 0)
})
