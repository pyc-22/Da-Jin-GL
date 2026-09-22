import { describe, expect, it } from 'vitest'
import printModule from '../electron/print.cjs'

const { printHtml, receiptHtml } = printModule

describe('Electron print pipeline', () => {
  it('renders receipt content for the configured paper width', () => {
    const html = receiptHtml({ paperWidth: 58, storeName: '测试门店', billNo: 'S001', items: [{ name: '足金手镯', qty: 1, amount: 100 }] })
    expect(html).toContain('size:58mm auto')
    expect(html).toContain('测试门店')
    expect(html).toContain('足金手镯')
  })

  it('shows the print host and sends a non-silent system print job', async () => {
    let instance
    class MockBrowserWindow {
      constructor(options) {
        instance = this
        this.options = options
        this.webContents = { print: (printOptions, callback) => { this.printOptions = printOptions; callback(true, '') } }
      }
      async loadURL(url) { this.url = url }
      showInactive() { this.shown = true }
      isDestroyed() { return false }
      close() { this.closed = true }
    }

    await expect(printHtml({}, '<html>票据</html>', { deviceName: 'TEST_PRINTER', silent: false }, MockBrowserWindow))
      .resolves.toEqual({ success: true, reason: '' })
    expect(instance.shown).toBe(true)
    expect(instance.printOptions).toMatchObject({ silent: false, printBackground: true, deviceName: 'TEST_PRINTER' })
    expect(instance.closed).toBe(true)
  })

  it('passes an explicit A4 page size when requested', async () => {
    let instance
    class MockBrowserWindow {
      constructor() {
        instance = this
        this.webContents = { print: (printOptions, callback) => { this.printOptions = printOptions; callback(true, '') } }
      }
      async loadURL() {}
      isDestroyed() { return false }
      close() {}
    }

    await expect(printHtml({}, '<html>加工工单</html>', { pageSize: 'A4', silent: true }, MockBrowserWindow))
      .resolves.toEqual({ success: true, reason: '' })
    expect(instance.printOptions).toMatchObject({ pageSize: 'A4', silent: true })
  })
})
