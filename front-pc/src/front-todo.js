export function createLatestOnlyGuard() {
  let latest = 0
  return {
    begin() {
      latest += 1
      return latest
    },
    isCurrent(id) {
      return id === latest
    }
  }
}
