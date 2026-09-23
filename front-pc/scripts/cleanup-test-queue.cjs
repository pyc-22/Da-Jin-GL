const fs = require('fs')
const path = require('path')
const initSqlJs = require('sql.js')

const file = process.argv[2]
const staleOrderKeys = new Set([
  '8adf7445-bc21-42fe-b1dd-06e20cc69491',
  '58765097-c147-4f45-a472-593c201f52d3'
])

async function main() {
  if (!file || !fs.existsSync(file)) throw new Error(`SQLite file not found: ${file}`)
  const distDir = path.dirname(require.resolve('sql.js/dist/sql-wasm.js'))
  const SQL = await initSqlJs({ locateFile: name => path.join(distDir, name) })
  const db = new SQL.Database(fs.readFileSync(file))
  const statement = db.prepare("select id, payload from sync_queue where status='PENDING'")
  const ids = []
  while (statement.step()) {
    const row = statement.getAsObject()
    let payload = {}
    try { payload = JSON.parse(row.payload) } catch { /* keep malformed test rows untouched */ }
    if (staleOrderKeys.has(payload.orderClientRequestId)) ids.push(row.id)
  }
  statement.free()
  ids.forEach(id => db.run('delete from sync_queue where id=?', [id]))
  fs.writeFileSync(file, Buffer.from(db.export()))
  console.log(JSON.stringify({ deleted: ids.length, ids }))
}

main().catch(error => { console.error(error.message); process.exitCode = 1 })
