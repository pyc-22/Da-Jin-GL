// @vitest-environment jsdom
import { mount, flushPromises } from '@vue/test-utils'
import { beforeEach, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import MessageSettings from './MessageSettings.vue'
import { useMessagesStore } from '../stores/messages.js'

vi.mock('../stores/app.js', () => ({ useAppStore: () => ({ wsStatus: 'connected' }) }))
vi.mock('../utils/messagePermissions.js', () => ({ isAndroidMessages: () => false, backgroundSettings: async () => ({ native: false }), openBackgroundSetting: vi.fn(), requestMessagePermission: vi.fn() }))
vi.mock('../utils/messageSound.js', () => ({ playMessageSound: async () => ({ played: true }) }))
beforeEach(() => { localStorage.clear(); setActivePinia(createPinia()); useMessagesStore().setOwner('1:7') })
it('starts with sound enabled, persists the accessible toggle per account and leaves notification categories on', async () => {
  const wrapper = mount(MessageSettings); await flushPromises()
  const toggle = wrapper.get('button[aria-label="声音提醒"]')
  expect(toggle.attributes('aria-checked')).toBe('true')
  await toggle.trigger('click')
  expect(toggle.attributes('aria-checked')).toBe('false')
  expect(useMessagesStore().settings.approval).toBe(true)
  useMessagesStore().setOwner('1:8'); expect(useMessagesStore().settings.sound).toBe(true)
  useMessagesStore().setOwner('1:7'); expect(useMessagesStore().settings.sound).toBe(false)
  wrapper.unmount()
})
