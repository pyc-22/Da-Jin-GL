const native = () => typeof uni !== 'undefined' && typeof uni.getStorageSync === 'function'
const readRaw = key => native() ? uni.getStorageSync(key) : localStorage.getItem(key)
const scoped = key => /^(dajin-inbound-|dajin-stock-check-)/.test(key) || key === 'dajin-gold'
export function storageOwner() {
  try {
    const user = JSON.parse(readRaw('dajin-user') || 'null')
    const store = user?.store_id ?? user?.storeId, id = user?.user_id ?? user?.userId
    return store != null && id != null ? `${encodeURIComponent(store)}:${encodeURIComponent(id)}` : ''
  } catch { return '' }
}
function resolvedKey(key, owner = storageOwner()) { return scoped(key) ? (owner ? `dajin-user-cache:${owner}:${key}` : null) : key }
export function hasLegacyInboundData() {
  try { return ['dajin-inbound-queue', 'dajin-inbound-draft', 'dajin-stock-check-draft'].some(key => { const value = readRaw(key); return value && !['[]', '{}', 'null'].includes(value) }) } catch { return false }
}
// Keep an in-flight operation bound to its original owner, even if the user signs out.
export function scopedStorage() {
  const owner = storageOwner()
  return {
    owner,
    current: () => Boolean(owner) && owner === storageOwner(),
    get: (key, fallback = '') => getStorage(key, fallback, owner),
    set: (key, value) => setStorage(key, value, owner)
  }
}
export function getStorage(key, fallback = '', owner = storageOwner()) {
  try { const actual = resolvedKey(key, owner); if (!actual) return fallback; const value = readRaw(actual); return value == null || value === '' ? fallback : value } catch { return fallback }
}
export function setStorage(key, value, owner = storageOwner()) { const actual = resolvedKey(key, owner); if (!actual) return; native() ? uni.setStorageSync(actual, value) : localStorage.setItem(actual, value) }
export function removeStorage(key) { try { const actual = resolvedKey(key); if (actual) native() ? uni.removeStorageSync(actual) : localStorage.removeItem(actual) } catch {} }
