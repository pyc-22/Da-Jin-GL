/**
 * 甲方口径：补金 = 成品实重 − 融后金重。
 * 成品里超出客户融后金的部分都由客户付（含店里下料），损耗（打磨屑）由店里承担、不进补金。
 * 成品比融后金轻时没有补金，多出来的金走回收屑抵扣（= 融后金重 − 成品实重）。
 * 口径与收银端 front-pc/src/cashier.js 的 goldBalance 保持一致；只用于预填与提示，柜面仍可手填。
 */
export function goldBalance({ melt, finished } = {}) {
  // 0g 是有效的融后金重（例如没有来料），不能和未填写混为一谈。
  const meltWeight = Number(melt)
  const finishedWeight = Number(finished)
  const meltMissing = melt === null || melt === undefined || melt === ''
  const finishedMissing = finished === null || finished === undefined || finished === ''
  if (meltMissing || finishedMissing || !Number.isFinite(meltWeight) || !Number.isFinite(finishedWeight)
    || meltWeight < 0 || !(finishedWeight > 0)) return { balance: null, topUp: 0, hint: '' }
  const balance = Math.round((finishedWeight - meltWeight) * 1000) / 1000
  if (balance > 0) return { balance, topUp: balance, hint: `差额 +${balance.toFixed(3)}g → 客户需补金 ${balance.toFixed(3)}g（成品实重 − 融后金重，已按此预填，可手改）` }
  return { balance, topUp: 0, hint: `差额 ${balance.toFixed(3)}g → 成品比融后金轻 ${Math.abs(balance).toFixed(3)}g：没有补金，多出的金走回收屑抵扣` }
}
