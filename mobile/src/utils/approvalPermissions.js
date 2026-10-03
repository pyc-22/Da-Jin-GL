export function canHandleApproval(auth, row) {
  return ['ADMIN', 'MANAGER'].includes(auth.role) && Number(row?.status) === 1 &&
    auth.can('approval:handle') && (row.type !== 'STOCK_CHECK' || auth.can('stock:check:approve'))
}
