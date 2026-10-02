// @vitest-environment jsdom
import { mount } from '@vue/test-utils'
import { describe, it, expect } from 'vitest'
import Stepper from './Stepper.vue'
import PriceLockBadge from './PriceLockBadge.vue'
describe('shared design controls', () => {
  it('steps decimals without drift and respects bounds', async () => {
    const wrapper = mount(Stepper, { props: { modelValue: 0.2, step: 0.1, min: 0, max: 0.3, label: '称重' } })
    await wrapper.get('[aria-label="增加称重"]').trigger('click')
    expect(wrapper.emitted('update:modelValue')[0]).toEqual([0.3])
    await wrapper.setProps({ modelValue: 0.3 })
    expect(wrapper.get('[aria-label="增加称重"]').attributes('disabled')).toBeDefined()
    await wrapper.get('input').setValue('-2')
    expect(wrapper.emitted('change').at(-1)).toEqual([0])
  })
  it('does not label a draft as locked', async () => {
    const wrapper = mount(PriceLockBadge)
    expect(wrapper.text()).toBe('')
    await wrapper.setProps({ locked: true })
    expect(wrapper.text()).toBe('金价已锁')
  })
})
