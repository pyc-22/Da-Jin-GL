// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { computed, defineComponent, h, inject, provide, reactive } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Member from './Member.vue'

const state = vi.hoisted(() => ({
  auth: null,
  app: null,
  memberApi: Object.fromEntries(['list', 'pool', 'create', 'update', 'detail', 'consume', 'balanceRecords', 'balance', 'assign', 'claim'].map(name => [name, vi.fn()])),
  staffList: vi.fn(),
  message: { success: vi.fn(), error: vi.fn(), warning: vi.fn() }
}))
vi.mock('../stores/auth', () => ({ useAuthStore: () => state.auth }))
vi.mock('../stores/app', () => ({ useAppStore: () => state.app }))
vi.mock('../api/modules', () => ({ memberApi: state.memberApi, staffApi: { list: state.staffList } }))
vi.mock('element-plus', () => ({ ElMessage: state.message }))

const tableRows = Symbol('member-test-rows')
const stubs = {
  ElButton: { props: ['disabled', 'loading'], template: '<button :disabled="disabled || loading"><slot /></button>' },
  ElInput: { props: ['modelValue', 'disabled'], template: '<input :value="modelValue" :disabled="disabled" @input="$emit(\'update:modelValue\', $event.target.value)" />' },
  ElSelect: { props: ['modelValue', 'disabled'], template: '<select :value="modelValue" :disabled="disabled" @change="$emit(\'update:modelValue\', $event.target.value)"><slot /></select>' },
  ElOption: { props: ['value', 'label'], template: '<option :value="value">{{ label }}</option>' },
  ElDatePicker: true, ElInputNumber: true,
  ElForm: { template: '<form><slot /></form>' },
  ElFormItem: { template: '<label><slot /></label>' },
  ElTabs: { props: ['modelValue'], template: '<div><button @click="$emit(\'update:modelValue\', \'pool\'); $emit(\'tab-change\', \'pool\')">公海池</button><slot /></div>' },
  ElTabPane: true,
  ElDialog: { props: ['modelValue', 'title'], template: '<section v-if="modelValue" :aria-label="title"><slot /><slot name="footer" /></section>' },
  ElDrawer: { props: ['modelValue'], template: '<aside v-if="modelValue"><slot /></aside>' },
  ElDescriptions: { template: '<div><slot /></div>' },
  ElDescriptionsItem: { template: '<span><slot /></span>' },
  ElTable: defineComponent({
    props: ['data'],
    setup(props, { slots }) {
      provide(tableRows, computed(() => props.data || []))
      return () => h('div', slots.default?.())
    }
  }),
  ElTableColumn: defineComponent({
    setup(props, { slots }) {
      const rows = inject(tableRows)
      return () => h('div', rows.value.map(row => slots.default?.({ row })))
    }
  })
}
const member = { member_id: 7, name: '会员甲', phone: '13800000000', sales_id: null }
let wrapper

function button(text) { return wrapper.findAll('button').find(item => item.text() === text) }
function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}
async function start(role = 'MANAGER', permissions = ['member:view', 'member:manage', 'member:create', 'staff:manage', 'member:follow']) {
  state.auth.role = role
  state.auth.permissions = permissions
  wrapper = mount(Member, { global: { stubs } })
  await flushPromises()
}
async function openPool() { await button('公海池').trigger('click'); await flushPromises() }
async function fillCreate() {
  await button('新增会员').trigger('click')
  const inputs = wrapper.get('section[aria-label="新增会员"]').findAll('input')
  await inputs[0].setValue(member.name)
  await inputs[1].setValue(member.phone)
}
beforeEach(() => {
  Object.values(state.memberApi).forEach(mock => mock.mockReset().mockResolvedValue(undefined))
  Object.values(state.message).forEach(mock => mock.mockReset())
  state.memberApi.list.mockResolvedValue({ records: [member] })
  state.memberApi.pool.mockResolvedValue({ records: [member] })
  state.memberApi.detail.mockResolvedValue({ member })
  state.memberApi.consume.mockResolvedValue([])
  state.memberApi.balanceRecords.mockResolvedValue([])
  state.staffList.mockReset().mockResolvedValue([{ user_id: 8, role_code: 'SALES', status: 1, store_id: 1, real_name: '本店销售' }])
  state.app = reactive({ eventVersion: 0, paymentChannels: [{ channel_id: 1, status: 1, channel_code: 'CASH', channel_name: '现金' }] })
  state.auth = reactive({ role: 'MANAGER', user: { store_id: 1 }, permissions: [], can: permission => state.auth.permissions.includes(permission) })
})
afterEach(() => { wrapper?.unmount(); wrapper = null })

describe('member permissions and mutation feedback', () => {
  it.each(['ADMIN', 'MANAGER'])('does not show the SALES-only claim action for %s', async role => {
    await start(role)
    await openPool()
    expect(button('申请认领')).toBeUndefined()
    expect(button('分配')).toBeDefined()
  })

  it('hides management actions for SALES even when management permissions are present', async () => {
    await start('SALES')
    await openPool()
    expect(button('申请认领')).toBeDefined()
    expect(button('编辑')).toBeUndefined()
    expect(button('分配')).toBeUndefined()
    expect(state.staffList).not.toHaveBeenCalled()
    await button('详情').trigger('click')
    await flushPromises()
    expect(button('储值充值')).toBeUndefined()
    expect(button('分配销售')).toBeUndefined()
    state.auth.permissions = ['member:view']
    await flushPromises()
    expect(button('申请认领')).toBeUndefined()
  })

  it('requires both member management and staff management for assignment', async () => {
    await start('MANAGER', ['member:view', 'member:manage'])
    await openPool()
    expect(button('编辑')).toBeDefined()
    expect(button('分配')).toBeUndefined()
    expect(state.staffList).not.toHaveBeenCalled()
  })

  it('only offers active SALES staff from the current store, independent of role IDs', async () => {
    state.staffList.mockResolvedValue([
      { user_id: 8, role_id: 99, role_code: 'SALES', status: 1, store_id: 1, real_name: '本店销售' },
      { user_id: 9, role_id: 3, role_code: 'CASHIER', status: 1, store_id: 1, real_name: '收银员' },
      { user_id: 10, role_id: 4, role_code: 'SALES', status: 0, store_id: 1, real_name: '离职销售' },
      { user_id: 11, role_id: 4, role_code: 'SALES', status: 1, store_id: 2, real_name: '外店销售' }
    ])
    await start()
    await openPool()
    await button('分配').trigger('click')
    expect(wrapper.get('section[aria-label="分配销售"]').findAll('option').map(option => option.text())).toEqual(['本店销售'])
  })

  it('allows creation with member:create without granting member:manage', async () => {
    await start('SALES', ['member:view', 'member:create'])
    await fillCreate()
    await button('保存').trigger('click')
    await flushPromises()
    expect(state.memberApi.create).toHaveBeenCalledTimes(1)
    expect(state.message.success).toHaveBeenCalledWith('会员已保存')
  })

  it('locks creation and preserves the form on failure before allowing a retry', async () => {
    const request = deferred()
    state.memberApi.create.mockReturnValueOnce(request.promise)
    await start()
    await fillCreate()
    await button('保存').trigger('click')
    await button('保存').trigger('click')
    expect(state.memberApi.create).toHaveBeenCalledTimes(1)
    expect(button('保存').element.disabled).toBe(true)
    request.reject(new Error('手机号已登记'))
    await flushPromises()
    expect(state.message.error).toHaveBeenCalledWith('手机号已登记')
    expect(state.message.success).not.toHaveBeenCalled()
    expect(wrapper.get('section[aria-label="新增会员"]').findAll('input')[1].element.value).toBe(member.phone)
    expect(button('保存').element.disabled).toBe(false)
    await button('保存').trigger('click')
    await flushPromises()
    expect(state.memberApi.create).toHaveBeenCalledTimes(2)
  })

  it('locks assignment and keeps the selection on rejection without success feedback', async () => {
    const request = deferred()
    state.memberApi.assign.mockReturnValueOnce(request.promise)
    await start()
    await openPool()
    await button('分配').trigger('click')
    await wrapper.get('section[aria-label="分配销售"] select').setValue('8')
    await button('确认分配').trigger('click')
    await button('确认分配').trigger('click')
    expect(state.memberApi.assign).toHaveBeenCalledTimes(1)
    request.reject(new Error('会员已被认领'))
    await flushPromises()
    expect(state.message.error).toHaveBeenCalledWith('会员已被认领')
    expect(state.message.success).not.toHaveBeenCalled()
    expect(wrapper.get('section[aria-label="分配销售"] select').element.value).toBe('8')
    expect(button('确认分配').element.disabled).toBe(false)
  })

  it('submits claims once, reports approval pending, and keeps unassigned membership', async () => {
    const request = deferred()
    state.memberApi.claim.mockReturnValueOnce(request.promise)
    await start('SALES', ['member:view', 'member:follow'])
    await openPool()
    await button('申请认领').trigger('click')
    await button('申请认领').trigger('click')
    expect(state.memberApi.claim).toHaveBeenCalledExactlyOnceWith(7)
    request.resolve({ approvalRequired: true })
    await flushPromises()
    expect(state.message.success).toHaveBeenCalledWith('认领申请已提交，等待店长或管理员审批')
    expect(member.sales_id).toBeNull()
  })

  it('shows claim rejection and allows retry without a success message', async () => {
    state.memberApi.claim.mockRejectedValueOnce(new Error('该会员已有待审批认领申请'))
    await start('SALES', ['member:view', 'member:follow'])
    await openPool()
    await button('申请认领').trigger('click')
    await flushPromises()
    expect(state.message.error).toHaveBeenCalledWith('该会员已有待审批认领申请')
    expect(state.message.success).not.toHaveBeenCalled()
    expect(button('申请认领').element.disabled).toBe(false)
  })
})
