// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import App from './App.vue'
import { request } from './api'
import { requestOrQueue } from './offline'
import { ElMessage } from 'element-plus'

vi.mock('./api', () => ({
  apiBase: () => 'http://localhost:18080', getToken: () => 'test-token',
  request: vi.fn(), login: vi.fn(), setApiBase: vi.fn(), setToken: vi.fn(), wsUrl: () => 'ws://localhost:18080/ws',
  fetchBackend: (path, options) => fetch(`http://localhost:18080${path}`, options),
  uploadBackendPhoto: vi.fn(), openBackendSocket: () => new WebSocket('ws://localhost:18080/ws')
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
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ status: 'UP' }), text: async () => '<html>Processing print</html>' })))
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
  it('opens account details for an active session and makes account switching explicit', async () => {
    localStorage.setItem('dajin_user', JSON.stringify({ real_name: '系统管理员', role_code: 'ADMIN' }))
    await start()
    await wrapper.get('.user-chip').trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain('当前账号')
    expect(wrapper.get('[role="dialog"]').text()).toContain('系统管理员')
    expect(wrapper.find('[role="dialog"] input[type="password"]').exists()).toBe(false)
    await button('切换账号', '[role="dialog"] button').trigger('click')
    expect(wrapper.get('[role="dialog"] input[type="password"]').exists()).toBe(true)
    await wrapper.get('.dialog-title .icon-button').trigger('click')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    await wrapper.get('.product-card').trigger('click')
    expect(wrapper.findAll('.cart-item')).toHaveLength(1)
  })

  it('shows connection settings without probing an unconfigured packaged backend', async () => {
    window.dajin.network = { request: vi.fn() }
    window.dajin.config = { get: vi.fn(async () => ({})) }
    await start()
    expect(wrapper.get('[role="dialog"]').text()).toContain('后端 API 地址')
    expect(fetch).not.toHaveBeenCalled()
    expect(window.dajin.network.request).not.toHaveBeenCalled()
  })

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

  it('settles a sale from entered actual receipt and does not offer partial collection', async () => {
    requestOrQueue.mockImplementation(async (path, options) => {
      if (path === '/api/order/create') return { orderId: 42, orderNo: 'SALE-42', status: 0 }
      expect(options.amount).toBe(90)
      expect(options.settlementMode).toBe('FULL')
      return { status: 1, actualPaid: 90, originalDue: 100, settlementDiscount: 10, remaining: 0 }
    })
    await start()
    await openSale()
    expect(wrapper.findAll('[role="dialog"] button').some(node => node.text().includes('部分收款'))).toBe(false)
    expect(wrapper.findAll('[role="dialog"] button').some(node => node.text().includes('议价结清'))).toBe(false)
    await wrapper.get('.payment-method').trigger('click')
    await wrapper.get('.payment-input input').setValue('90')
    await button('确认收款', '[role="dialog"] button').trigger('click')
    await flushPromises()
    const call = requestOrQueue.mock.calls.find(([path]) => path === '/api/pay/pay')
    expect(call[1]).toMatchObject({ amount: 90, settlementMode: 'FULL' })
    expect(wrapper.get('.receipt-preview').text()).toContain('实收: ¥90.00')
    expect(wrapper.get('.receipt-preview').text()).toContain('优惠: -¥10.00')
  })

  it('sends a deep sale discount to approval without printing or recording payment', async () => {
    localStorage.setItem('dajin_user', JSON.stringify({ real_name: '收银员', role_code: 'CASHIER' }))
    requestOrQueue.mockImplementation(async (path, options) => path === '/api/pay/pay'
      ? { approvalRequired: true, approvalId: 91, status: 0, actualPaid: 80, originalDue: 100, settlementDiscount: 20 }
      : path === '/api/order/create' ? { orderId: 42, orderNo: 'SALE-42', status: 0 } : { status: 1 })
    await start()
    await openSale()
    await wrapper.get('.payment-method').trigger('click')
    await wrapper.get('.payment-input input').setValue('80')
    expect(wrapper.get('[role="dialog"]').text()).toContain('优惠')
    expect(wrapper.get('[role="dialog"]').text()).toContain('20.00')
    await button('确认收款', '[role="dialog"] button').trigger('click')
    await flushPromises()
    const payCall = requestOrQueue.mock.calls.find(([path]) => path === '/api/pay/pay')
    expect(payCall[1]).toMatchObject({ amount: 80, settlementMode: 'FULL' })
    expect(requestOrQueue.mock.calls.filter(([path]) => path === '/api/pay/pay')).toHaveLength(1)
    expect(wrapper.get('[role="dialog"]').text()).toContain('优惠审批已提交')
    expect(wrapper.find('.receipt-preview').exists()).toBe(false)
  })

  it('moves a confirmed/printed processing order to the processing list and preserves it on refresh failure', async () => {
    handovers = [processing]
    await start()
    await openTodo()
    expect(wrapper.get('tbody').text()).toContain('PROC-7')
    await button('打印工单', 'tbody button').trigger('click')
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
    expect(dialog.text()).not.toContain('议价结清')
    await dialog.get('input[type="number"]').setValue('30')
    await button('确认收定金', '[role="dialog"] button').trigger('click')
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
    const submit = button('确认收定金', '[role="dialog"] button')
    expect(submit.element.disabled).toBe(true)
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
    expect(dialog.text()).not.toContain('议价结清')
    await dialog.get('input[type="number"]').setValue('180')
    await dialog.get('input[maxlength="100"]').setValue('DY-123')
    expect(dialog.text()).toContain('20.00')
    await button('确认实收', '[role="dialog"] button').trigger('click')
    await flushPromises()
    const call = request.mock.calls.find(([path]) => path === '/api/processing/orders/7/payments')
    expect(JSON.parse(call[1].body)).toMatchObject({ paymentType: 'BALANCE', payMethod: 'DOUYIN_GROUP', amount: 180, voucherNo: 'DY-123' })
  })

  it('submits an ordinary processing discount as a full actual-receipt request', async () => {
    localStorage.setItem('dajin_user', JSON.stringify({ real_name: '收银员', role_code: 'CASHIER' }))
    handovers = [{ ...processing, status: 'COMPLETED', due_amount: 200 }]
    const base = requestHandler
    requestHandler = (path, options) => path.includes('status=COMPLETED') ? handovers : base(path, options)
    await start()
    await openTodo()
    await button('待取货', '.todo-tabs button').trigger('click')
    await button('收尾款', 'tbody button').trigger('click')
    await wrapper.get('[role="dialog"] input[type="number"]').setValue('180')
    expect(wrapper.get('[role="dialog"]').text()).toContain('优惠 ¥20.00')
    await button('确认实收', '[role="dialog"] button').trigger('click')
    await flushPromises()
    const call = request.mock.calls.find(([path]) => path === '/api/processing/orders/7/payments')
    expect(JSON.parse(call[1].body)).toMatchObject({ paymentType: 'BALANCE', payMethod: 'CASH', amount: 180, settlementMode: 'FULL' })
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
    await button('打印工单', 'tbody button').trigger('click')
    await flushPromises()
    await button('打印工单', '[role="dialog"] button').trigger('click')
    await flushPromises()
    expect(ElMessage.warning).toHaveBeenCalledWith('打印机未响应')
    expect(request.mock.calls.filter(([path]) => path === '/api/processing/orders/7/status')).toHaveLength(0)
    expect(wrapper.get('[role="dialog"]').text()).toContain('打印工单')
  })

  it('does not print or advance a processing order when its document fails to load', async () => {
    handovers = [processing]
    await start()
    await openTodo()
    fetch.mockResolvedValue({ ok: false, status: 401 })
    await button('打印工单', 'tbody button').trigger('click')
    await flushPromises()
    expect(ElMessage.error).toHaveBeenCalledWith('加工工单加载失败，请检查登录状态后重试')
    expect(window.dajin.print.system).not.toHaveBeenCalled()
    expect(request.mock.calls.filter(([path]) => path === '/api/processing/orders/7/status')).toHaveLength(0)
  })

  it('confirms and starts a processing order without printing a work order', async () => {
    handovers = [processing]
    await start()
    await openTodo()
    await button('确认加工', 'tbody button').trigger('click')
    await flushPromises()
    expect(ElMessage.success).toHaveBeenCalledWith(expect.stringContaining('已确认开始加工'))
    const statusCalls = request.mock.calls.filter(([path]) => path === '/api/processing/orders/7/status')
    expect(statusCalls).toHaveLength(1)
    expect(JSON.parse(statusCalls[0][1].body).status).toBe('PROCESSING')
    expect(window.dajin.print.system).not.toHaveBeenCalled()
    expect(fetch.mock.calls.some(([url]) => String(url).includes('/print'))).toBe(false)
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })

  it('reprints a work order from the processing tab without advancing the status again', async () => {
    handovers = [processing]
    await start()
    await openTodo()
    await button('确认加工', 'tbody button').trigger('click')
    await flushPromises()
    await button('加工中', '.todo-tabs button').trigger('click')
    await flushPromises()
    const before = request.mock.calls.filter(([path]) => path === '/api/processing/orders/7/status').length
    expect(before).toBe(1)
    await button('打印工单', 'tbody button').trigger('click')
    await flushPromises()
    await button('打印工单', '[role="dialog"] button').trigger('click')
    await flushPromises()
    expect(window.dajin.print.system).toHaveBeenCalledTimes(1)
    expect(request.mock.calls.filter(([path]) => path === '/api/processing/orders/7/status')).toHaveLength(before)
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

  it('treats a gram-priced product as weighed-on-site instead of using its archived weight', async () => {
    const gram = { goods_id: 2, name: '按克手镯', barcode: 'GRAM1', price_type: 1, weight: 10, sale_price: 0, stock: 50, available_stock: 50 }
    const base = requestHandler
    requestHandler = (path, options) => path.startsWith('/api/goods/list') ? { records: [product, gram] } : base(path, options)
    await start()
    await wrapper.findAll('.product-card')[1].trigger('click')
    await flushPromises()
    expect(wrapper.get('.cart-item').text()).toContain('待称重录入')
    expect(Number(wrapper.get('.weight-input input').element.value)).toBe(0)
    await wrapper.get('.checkout-button').trigger('click')
    await flushPromises()
    expect(ElMessage.warning).toHaveBeenCalledWith(expect.stringContaining('请先录入'))
    expect(requestOrQueue.mock.calls.filter(([path]) => path === '/api/order/create')).toHaveLength(0)
  })

  it('does not accumulate a gram-priced product when it is scanned twice', async () => {
    const gram = { goods_id: 2, name: '按克手镯', barcode: 'GRAM1', price_type: 1, weight: 10, sale_price: 0, stock: 50, available_stock: 50 }
    const base = requestHandler
    requestHandler = (path, options) => path.startsWith('/api/goods/list') ? { records: [product, gram] } : base(path, options)
    await start()
    await wrapper.findAll('.product-card')[1].trigger('click')
    await wrapper.findAll('.product-card')[1].trigger('click')
    await flushPromises()
    expect(wrapper.findAll('.cart-item')).toHaveLength(1)
    expect(Number(wrapper.get('.weight-input input').element.value)).toBe(0)
    expect(ElMessage.info).toHaveBeenCalledWith(expect.stringContaining('录入实际克重'))
  })

  it('starts a stock-check row at zero grams instead of the archived weight', async () => {
    const gram = { goods_id: 2, id: 2, name: '按克手镯', barcode: 'GRAM1', price_type: 1, weight: 10, sale_price: 0, stock: 50, available_stock: 50 }
    const base = requestHandler
    requestHandler = (path, options) => path.startsWith('/api/goods/list') ? { records: [product, gram] } : base(path, options)
    await start()
    await button('盘点', '.sidebar nav button').trigger('click')
    await flushPromises()
    await wrapper.findAll('.scan-shortcuts button')[1].trigger('click')
    await flushPromises()
    expect(wrapper.get('tbody').text()).toContain('按克手镯')
    expect(Number(wrapper.get('tbody .number-input').element.value)).toBe(0)
  })

  it('requires the incoming old-gold weight before completing a processing order', async () => {
    processings = [{ ...processing, status: 'PROCESSING' }]
    await start()
    await openTodo()
    await button('加工中', '.todo-tabs button').trigger('click')
    await flushPromises()
    await button('完成加工', 'tbody button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="dialog"]').text()).toContain('来料')
    await button('确认完成加工', '[role="dialog"] button').trigger('click')
    await flushPromises()
    expect(ElMessage.warning).toHaveBeenCalledWith(expect.stringContaining('请先填写来料克重'))
    expect(request.mock.calls.filter(([path]) => path.endsWith('/7/status'))).toHaveLength(0)
  })

  it('falls back to no guide when a member is bound to a non-sales account', async () => {
    const base = requestHandler
    let created = null
    requestHandler = async (path, options) => {
      if (path.startsWith('/api/processing/items')) return [{ item_id: 5, name: '戒指', labor_fee: 30, pricing_unit: '按件', status: 1 }]
      if (path === '/api/processing/salespeople') return [{ user_id: 3, real_name: '销售演示' }]
      if (path.startsWith('/api/member/list')) return { records: [
        { member_id: 8200, id: 8200, name: '王小红', phone: '13800001234', sales_id: 1 },
        { member_id: 8300, id: 8300, name: '李小美', phone: '13800005678', sales_id: 3 }
      ] }
      if (path === '/api/processing/orders') { created = JSON.parse(options.body); return { processing_order_id: 9, order_no: 'PROC-9', due_amount: 30 } }
      return base(path, options)
    }
    await start()
    await button('加工开单', '.sidebar nav button').trigger('click')
    await flushPromises()
    const selects = wrapper.findAll('.processing-fields select')

    await selects[0].setValue('8200')
    await flushPromises()
    expect(wrapper.get('.processing-fields').text()).toContain('已停用或不是销售账号')
    expect(selects[3].element.value).toBe('')

    await selects[0].setValue('8300')
    await flushPromises()
    expect(wrapper.get('.processing-fields').text()).not.toContain('已停用或不是销售账号')
    expect(selects[3].element.value).toBe('3')

    await selects[0].setValue('8200')
    await selects[1].setValue('5')
    await flushPromises()
    await button('创建加工单并收定金').trigger('click')
    await flushPromises()
    expect(created).toBeTruthy()
    expect(created.salesId).toBeNull()
  })
})
