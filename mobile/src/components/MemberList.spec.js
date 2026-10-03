// @vitest-environment jsdom
import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MemberList from './MemberList.vue'

const state = vi.hoisted(() => ({ claimMember: vi.fn(), toast: vi.fn() }))

vi.mock('../api/request.js', () => ({ api: { claimMember: state.claimMember } }))
vi.mock('../composables/useToast.js', () => ({ useToast: () => ({ toast: state.toast }) }))

const member = { member_id: 7, name: '王女士', phone: '13800000000', total_consume: 1200 }

beforeEach(() => {
  state.claimMember.mockReset()
  state.toast.mockReset()
})

describe('MemberList claim controls', () => {
  it('hides claim action unless the parent explicitly marks the list claimable', () => {
    const wrapper = mount(MemberList, { props: { members: [member], claimable: false } })
    expect(wrapper.find('button').exists()).toBe(false)
  })

  it('submits one claim at a time and restores the action after a failed request', async () => {
    let rejectRequest
    state.claimMember.mockReturnValueOnce(new Promise((resolve, reject) => { rejectRequest = reject }))
    const wrapper = mount(MemberList, { props: { members: [member], claimable: true } })
    const button = wrapper.get('button')

    await button.trigger('click')
    await button.trigger('click')
    expect(state.claimMember).toHaveBeenCalledTimes(1)
    expect(button.attributes('disabled')).toBeDefined()

    rejectRequest(new Error('会员已被其他销售认领'))
    await flushPromises()
    expect(state.toast).toHaveBeenCalledWith('会员已被其他销售认领')
    expect(button.attributes('disabled')).toBeUndefined()
    expect(button.text()).toContain('申请认领')
  })
})
