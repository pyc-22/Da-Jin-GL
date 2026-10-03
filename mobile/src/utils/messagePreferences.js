import { getStorage, setStorage } from './storage.js'

const defaults = { sound: true, approval: true, stock: true, visit: true, system: true }
const key = owner => `dajin-message:${owner}:preferences`
export function readMessagePreferences(owner) {
  try { return { ...defaults, ...JSON.parse(getStorage(key(owner), '{}')) } }
  catch { return { ...defaults } }
}
export function saveMessagePreferences(owner, settings) {
  if (owner) setStorage(key(owner), JSON.stringify({ ...defaults, ...settings }))
}
export function messageCategory(row) {
  const action = String(row.action || row.type || '').toUpperCase()
  if (action.includes('APPROVAL')) return 'approval'
  if (/STOCK|INVENTORY/.test(action)) return 'stock'
  if (/VISIT|BIRTHDAY/.test(action)) return 'visit'
  return 'system'
}
export const messageId = row => String(row.notification_id ?? row.id ?? row.log_id ?? '')
export const messageRead = row => row.read === true || String(row.action).toUpperCase() === 'READ'
