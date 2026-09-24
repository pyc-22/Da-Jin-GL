// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import App from './App.vue'
import { request } from './api'
import { requestOrQueue } from './offline'
import { ElMessage } from 'element-plus'

vi.mock('./api', () => ({
  apiBase: () => 'http://localhost:18080', getToken: () => 'test-token',
  request: vi.fn(), login: vi.fn(), setApiBase: vi.fn(), setToken: vi.fn(), wsUrl: () => 'ws://localhost:18080/ws'
}))
vi.mock('./offline', () => ({
  cancelQueuedOrder: vi.fn(), enqueueWithId: vi.fn(), localConflicts: async () => [],
  localGold: async () => [], localMembers: async () => [], localProducts: async () => [],
  requestOrQueue: vi.fn(), resolveLocalConflict: vi.fn(), syncQueue: async () => ({ synced: 0 }),
  uuid: () => crypto.randomUUID()
}))
vi.mock('element-plus', () => ({
  ElMessage: { success: vi.fn(), warning: vi.fn(), error: vi.fn(), info: vi.fn() },
  ElMessageBox: { confirm: vi.fn(), prompt: vi.fn() }
}))

const product = { goods_id: 1, name: 'Test item', barcode: 'ITEM1', price_type: 2, sale_price: 100, stock: 1, available_stock: 1 }
const pending = { order_id: 42, order_no: 'SALE-42', status: 0, total_amount: 100, discount: 1, labor_fee: 0 }
const processing = { processing_order_id: 7, order_no: 'PROC-7', due_amount: 100, paid_amount: 0, status: 'PENDING' }
let wrapper, handovers, processings, pendingSales, failProcessing, requestHandler

function deferred() {
  let resolve
  const promise = new Promise(done => { resolve = done })
  return { promise, resolve }
}
function button(text, selector = 'button') {
  const found = wrapper.findAll(selector).find(node => node.text().includes(text))
  expect(found, `Button not found: ${text}`).toBeTruthy()
  return found
}
async function start() {
  // Only the library dialog shell is stubbed; App's actual template and handlers run.
  wrapper = mount(App, { global: { stubs: {
    ElConfigProvider: { props: ['locale'], template: '<slot />' },
    ElDialog: { props: ['modelValue'], template: '<section v-if="modelValue" role="dialog"><slot name="header" /><slot /></section>' },
    ElDatePicker: true
  } } })
  await flushPromises()
}
async function openTodo() {
  await button('\u524d\u53f0\u5f85\u529e', '.sidebar nav button').trigger('click')
  await wrapper.get('.page-heading > button').trigger('click')
  await flushPromises()
}
async function openSale() {
  await wrapper.get('.product-card').trigger('click')
  await wrapper.get('.checkout-button').trigger('click')
  await flushPromises()
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.clearAllMocks()
  localStorage.clear()
  handovers = []
  processings = []
  pendingSales = []
  failProcessing = false
  requestHandler = async (path, options) => {
    if (path.startsWith('/api/goods/list')) return { records: [product] }
    if (path.startsWith('/api/member/list')) return { records: [] }
    if (path === '/api/pay/methods') return [{ channel_id: 1, channel_code: 'CASH', channel_name: '现金', status: 1 }]
    if (path === '/api/processing/handovers') return handovers
    if (path.includes('status=PROCESSING')) {
      if (failProcessing) throw new Error('Processing refresh failed')
      return processings
    }
    if (path.startsWith('/api/order/list')) return { records: pendingSales }
    if (path === '/api/order/42') return { order: pending, items: [{ goods_id: 1, item_name: product.name, qty: 1, unit_price: 100, subtotal: 100 }] }
    if (path === '/api/processing/orders/7/status') {
      expect(JSON.parse(options.body).status).toBe('PROCESSING')
      handovers = []
      processings = [{ ...processing, status: 'PROCESSING' }]
      return processings[0]
    }
    return []
  }
  request.mockImplementation((...args) => requestHandler(...args))
  requestOrQueue.mockImplementation(async path => path === '/api/order/create'
    ? { orderId: 42, orderNo: 'SALE-42', status: 0 } : { status: 1 })
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, text: async () => '<html>Processing print</html>' })))
  vi.stubGlobal('WebSocket', class { close() {} })
  window.dajin = { print: { system: vi.fn(async () => ({ success: true })) } }
})
afterEach(() => {
  wrapper?.unmount()
  wrapper = null
  delete window.dajin
  vi.clearAllTimers()
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

describe('cashier mounted checkout and processing flows', () => {
  it('opens payment on the first checkout click and submits creation/payment only once while pending', async () => {
    const creation = deferred()
    requestOrQueue.mockImplementationOnce(() => creation.promise)
    await start()
    await wrapper.get('.product-card').trigger('click')
    await wrapper.get('.checkout-button').trigger('click')
    await wrapper.get('.checkout-button').trigger('click')
    expect(requestOrQueue).toHaveBeenCalledTimes(1)
    expect(wrapper.get('.checkout-button').element.disabled).toBe(true)
    creation.resolve({ orderId: 42, orderNo: 'SALE-42', status: 0 })
    await flushPromises()
    expect(wrapper.get('[role="dialog"] .payment-total').text()).toContain('100.00')
    expect(wrapper.get('.sidebar nav button.active').text()).toContain('\u5f00\u5355')
    await wrapper.get('.payment-method').trigger('click')
    const payment = deferred()
    requestOrQueue.mockImplementationOnce(() => payment.promise)
    await wrapper.get('.dialog-actions .primary-button').trigger('click')
    await wrapper.get('.dialog-actions .primary-button').trigger('click')
    expect(requestOrQueue.mock.calls.filter(([path]) => path === '/api/pay/pay')).toHaveLength(1)
    expect(wrapper.get('.dialog-actions .secondary-button').element.disabled).toBe(true)
    payment.resolve({ status: 1 })
    await flushPromises()
    expect(wrapper.find('.receipt-preview').exists()).toBe(true)
    expect(request.mock.calls.filter(([path]) => path.endsWith('/cancel'))).toHaveLength(0)
  })

  it('cancels the pending server order before clearing the cart and closing payment', async () => {
    await start()
    await openSale()
    await wrapper.get('.dialog-actions .secondary-button').trigger('click')
    await flushPromises()
    expect(request).toHaveBeenCalledWith('/api/order/42/cancel', expect.objectContaining({ method: 'POST' }))
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(wrapper.findAll('.cart-item')).toHaveLength(0)
  })

  it('retains payment and its order when cancellation fails, so cancellation can be retried', async () => {
    await start()
    await openSale()
    const base = requestHandler
    requestHandler = (path, options) => path.endsWith('/cancel') ? Promise.reject(new Error('Cancellation failed')) : base(path, options)
    await wrapper.get('.dialog-actions .secondary-button').trigger('click')
    await flushPromises()
    expect(wrapper.find('.payment-total').exists()).toBe(true)
    expect(wrapper.findAll('.cart-item')).toHaveLength(1)
    expect(ElMessage.error).toHaveBeenCalledWith('Cancellation failed')
    requestHandler = base
    await wrapper.get('.dialog-actions .secondary-button').trigger('click')
    await flushPromises()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('opens a transferred pending sale directly in payment without creating another order', async () => {
    pendingSales = [pending]
    await start()
    await openTodo()
    await button('\u5f85\u6536\u6b3e', '.todo-tabs button').trigger('click')
    await wrapper.get('tbody .primary-button').trigger('click')
    await flushPromises()
    expect(wrapper.get('.payment-total').text()).toContain('100.00')
    expect(requestOrQueue).not.toHaveBeenCalled()
  })

  it('moves a confirmed/printed processing order to the processing list and preserves it on refresh failure', async () => {
    handovers = [processing]
    await start()
    await openTodo()
    expect(wrapper.get('tbody').text()).toContain('PROC-7')
    await wrapper.get('tbody .primary-button').trigger('click')
    await flushPromises()
    await wrapper.get('.dialog-actions .primary-button').trigger('click')
    await flushPromises()
    expect(window.dajin.print.system).toHaveBeenCalledTimes(1)
    expect(request).toHaveBeenCalledWith('/api/processing/orders/7/status', expect.objectContaining({ method: 'PATCH' }))
    await button('\u52a0\u5de5\u4e2d', '.todo-tabs button').trigger('click')
    expect(wrapper.get('tbody').text()).toContain('PROC-7')
    failProcessing = true
    await wrapper.get('.page-heading > button').trigger('click')
    await flushPromises()
    expect(wrapper.get('tbody').text()).toContain('PROC-7')
    expect(ElMessage.error).toHaveBeenCalledWith('Processing refresh failed')
  })

  it('collects a partial deposit on a pending processing order', async () => {
    handovers = [processing]
    await start()
    await openTodo()
    await button('收定金', 'tbody button').trigger('click')
    const dialog = wrapper.get('[role="dialog"]')
    await dialog.get('input[type="number"]').setValue('30')
    await button('确认收款', '[role="dialog"] button').trigger('click')
    await flushPromises()
    expect(request).toHaveBeenCalledWith('/api/processing/orders/7/payments', expect.objectContaining({
      method: 'POST', body: expect.stringContaining('"paymentType":"DEPOSIT"')
    }))
    const call = request.mock.calls.find(([path]) => path === '/api/processing/orders/7/payments')
    expect(JSON.parse(call[1].body)).toMatchObject({ amount: 30, payMethod: 'CASH' })
    expect(request.mock.calls.filter(([path]) => path === '/api/processing/handovers').length).toBeGreaterThan(1)
  })

  it('rejects an excessive deposit before requesting payment', async () => {
    handovers = [processing]
    await start()
    await openTodo()
    await button('收定金', 'tbody button').trigger('click')
    await wrapper.get('[role="dialog"] input[type="number"]').setValue('101')
    await button('确认收款', '[role="dialog"] button').trigger('click')
    expect(ElMessage.warning).toHaveBeenCalled()
    expect(request.mock.calls.filter(([path]) => path === '/api/processing/orders/7/payments')).toHaveLength(0)
  })

  it('collects a discounted group balance only on completed processing orders', async () => {
    handovers = [{ ...processing, status: 'COMPLETED', due_amount: 200 }]
    const base = requestHandler
    requestHandler = (path, options) => {
      if (path === '/api/pay/methods') return [
        { channel_id: 1, channel_code: 'CASH', channel_name: '现金', status: 1 },
        { channel_id: 2, channel_code: 'DOUYIN_GROUP', channel_name: '抖音团购', status: 1 }
      ]
      if (path.includes('status=COMPLETED')) return handovers
      return base(path, options)
    }
    await start()
    await openTodo()
    await button('待取货', '.todo-tabs button').trigger('click')
    await button('收尾款', 'tbody button').trigger('click')
    const dialog = wrapper.get('[role="dialog"]')
    expect(dialog.text()).not.toContain('团购优惠')
    await dialog.get('select').setValue('DOUYIN_GROUP')
    await dialog.get('input[type="number"]').setValue('180')
    await dialog.get('input[maxlength="100"]').setValue('DY-123')
    expect(dialog.text()).toContain('20.00')
    await button('确认收款', '[role="dialog"] button').trigger('click')
    await flushPromises()
    const call = request.mock.calls.find(([path]) => path === '/api/processing/orders/7/payments')
    expect(JSON.parse(call[1].body)).toMatchObject({ paymentType: 'BALANCE', payMethod: 'DOUYIN_GROUP', amount: 180, voucherNo: 'DY-123' })
  })

  it('does not offer a second group redemption on a promoted processing order', async () => {
    handovers = [{ ...processing, status: 'COMPLETED', due_amount: 230, paid_amount: 180, promotion_channel: 'DOUYIN_GROUP' }]
    const base = requestHandler
    requestHandler = (path, options) => {
      if (path === '/api/pay/methods') return [
        { channel_id: 1, channel_code: 'CASH', channel_name: '现金', status: 1 },
        { channel_id: 2, channel_code: 'DOUYIN_GROUP', channel_name: '抖音团购', status: 1 }
      ]
      if (path.includes('status=COMPLETED')) return handovers
      return base(path, options)
    }
    await start()
    await openTodo()
    await button('待取货', '.todo-tabs button').trigger('click')
    await button('收尾款', 'tbody button').trigger('click')
    expect(wrapper.get('[role="dialog"] select').text()).not.toContain('抖音团购')
  })

  it('does not advance a processing order when its print job fails', async () => {
    handovers = [processing]
    window.dajin.print.system.mockResolvedValue({ success: false, reason: '打印机未响应' })
    await start()
    await openTodo()
    await button('确认加工', 'tbody button').trigger('click')
    await flushPromises()
    await button('打印加工工单', '[role="dialog"] button').trigger('click')
    await flushPromises()
    expect(ElMessage.warning).toHaveBeenCalledWith('打印机未响应')
    expect(request.mock.calls.filter(([path]) => path === '/api/processing/orders/7/status')).toHaveLength(0)
    expect(wrapper.get('[role="dialog"]').text()).toContain('打印加工工单')
  })

  it('does not print or advance a processing order when its document fails to load', async () => {
    handovers = [processing]
    await start()
    await openTodo()
    fetch.mockResolvedValue({ ok: false, status: 401 })
    await button('确认加工', 'tbody button').trigger('click')
    await flushPromises()
    expect(ElMessage.error).toHaveBeenCalledWith('加工工单加载失败，请检查登录状态后重试')
    expect(window.dajin.print.system).not.toHaveBeenCalled()
    expect(request.mock.calls.filter(([path]) => path === '/api/processing/orders/7/status')).toHaveLength(0)
  })

  it('prints the processing warranty through Electron without opening a browser popup', async () => {
    handovers = [{ ...processing, status: 'COMPLETED', paid_amount: 100 }]
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const base = requestHandler
    requestHandler = (path, options) => path.includes('status=COMPLETED') ? handovers : base(path, options)
    await start()
    await openTodo()
    await button('待取货', '.todo-tabs button').trigger('click')
    await button('预览并打质保单', 'tbody button').trigger('click')
    await flushPromises()
    await button('确认打印', '[role="dialog"] button').trigger('click')
    await flushPromises()
    expect(window.dajin.print.system).toHaveBeenCalledWith('<html>Processing print</html>', expect.objectContaining({ pageSize: 'A4' }))
    expect(open).not.toHaveBeenCalled()
    open.mockRestore()
  })

  it('retains the processing warranty preview when the printer fails', async () => {
    handovers = [{ ...processing, status: 'COMPLETED', paid_amount: 100 }]
    window.dajin.print.system.mockResolvedValue({ success: false, reason: '打印机未响应' })
    const base = requestHandler
    requestHandler = (path, options) => path.includes('status=COMPLETED') ? handovers : base(path, options)
    await start()
    await openTodo()
    await button('待取货', '.todo-tabs button').trigger('click')
    await button('预览并打质保单', 'tbody button').trigger('click')
    await flushPromises()
    await button('确认打印', '[role="dialog"] button').trigger('click')
    await flushPromises()
    expect(ElMessage.warning).toHaveBeenCalledWith('打印机未响应')
    expect(wrapper.get('[role="dialog"]').text()).toContain('加工质保单')
  })

  it('ignores an older empty processing response that arrives after a newer list', async () => {
    processings = [{ ...processing, status: 'PROCESSING' }]
    await start()
    await openTodo()
    await button('\u52a0\u5de5\u4e2d', '.todo-tabs button').trigger('click')
    const older = deferred()
    const base = requestHandler
    let delayNext = true
    requestHandler = (path, options) => {
      if (path.includes('status=PROCESSING') && delayNext) {
        delayNext = false
        return older.promise
      }
      return base(path, options)
    }
    await wrapper.get('.page-heading > button').trigger('click')
    await flushPromises()
    await wrapper.get('.page-heading > button').trigger('click')
    await flushPromises()
    expect(wrapper.get('tbody').text()).toContain('PROC-7')
    older.resolve([])
    await flushPromises()
    expect(wrapper.get('tbody').text()).toContain('PROC-7')
  })
})
