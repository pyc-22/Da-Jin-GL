// @vitest-environment jsdom
import { beforeEach, expect, it } from 'vitest'
import { getStorage, setStorage, removeStorage, scopedStorage } from './storage.js'
const login = (store, user) => setStorage('dajin-user', JSON.stringify({ store_id: store, user_id: user }))
beforeEach(() => localStorage.clear())
it('isolates drafts and pending records by employee and store, retaining each original draft', () => {
  login(1, 7); setStorage('dajin-inbound-queue', '[{"id":"A"}]'); setStorage('dajin-stock-check-draft', 'draft A')
  login(1, 8); expect(getStorage('dajin-inbound-queue', '[]')).toBe('[]')
  expect(getStorage('dajin-stock-check-draft', '')).toBe('')
  setStorage('dajin-inbound-queue', '[{"id":"B"}]')
  login(2, 7); expect(getStorage('dajin-inbound-queue', '[]')).toBe('[]')
  login(1, 7); expect(getStorage('dajin-inbound-queue')).toContain('A')
  removeStorage('dajin-user'); expect(getStorage('dajin-inbound-queue', '[]')).toBe('[]')
})
it('does not assign ownerless old drafts to the next user logging in', () => {
  localStorage.setItem('dajin-inbound-draft', 'old private draft')
  login(1, 9)
  expect(getStorage('dajin-inbound-draft', '')).toBe('')
  expect(localStorage.getItem('dajin-inbound-draft')).toBe('old private draft')
})
it('keeps asynchronous writes attached to the original employee after switching', () => {
  login(1, 7); const cache = scopedStorage()
  login(1, 8); cache.set('dajin-inbound-history', 'late response A')
  expect(cache.current()).toBe(false)
  expect(getStorage('dajin-inbound-history', '')).toBe('')
  login(1, 7); expect(getStorage('dajin-inbound-history')).toBe('late response A')
})
