const fs = require('fs')
const path = require('path')
const initSqlJs = require('sql.js')

async function createDb(file) {
  const distDir = path.dirname(require.resolve('sql.js/dist/sql-wasm.js'))
  const SQL = await initSqlJs({ locateFile: name => path.join(distDir, name) })
  const database = fs.existsSync(file) ? new SQL.Database(fs.readFileSync(file)) : new SQL.Database()
  const persist = () => fs.writeFileSync(file, Buffer.from(database.export()))
  const all = (sql, params = []) => {
    const statement = database.prepare(sql)
    try {
      statement.bind(params)
      const rows = []
      while (statement.step()) rows.push(statement.getAsObject())
      return rows
    } finally { statement.free() }
  }
  const run = (sql, params = []) => { database.run(sql, params); persist() }

  database.run(`
    CREATE TABLE IF NOT EXISTS products (id INTEGER PRIMARY KEY, barcode TEXT UNIQUE, name TEXT NOT NULL, category TEXT, weight REAL, sale_price REAL, cost_price REAL, price_type INTEGER DEFAULT 1, stock REAL DEFAULT 0, image TEXT, updated_at TEXT DEFAULT CURRENT_TIMESTAMP);
    CREATE TABLE IF NOT EXISTS gold_price (price_type TEXT PRIMARY KEY, price REAL NOT NULL, updated_at TEXT DEFAULT CURRENT_TIMESTAMP);
    CREATE TABLE IF NOT EXISTS members (id INTEGER PRIMARY KEY, name TEXT, phone TEXT, birthday TEXT, gender TEXT, balance REAL DEFAULT 0, points INTEGER DEFAULT 0, total_consume REAL DEFAULT 0, source TEXT, sales_id INTEGER, tags TEXT, updated_at TEXT DEFAULT CURRENT_TIMESTAMP);
    CREATE TABLE IF NOT EXISTS sync_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, client_request_id TEXT UNIQUE NOT NULL, method TEXT NOT NULL, path TEXT NOT NULL, payload TEXT NOT NULL, version INTEGER, status TEXT DEFAULT 'PENDING', attempts INTEGER DEFAULT 0, last_error TEXT, created_at TEXT DEFAULT CURRENT_TIMESTAMP, synced_at TEXT, server_response TEXT);
    CREATE TABLE IF NOT EXISTS sync_conflict (id INTEGER PRIMARY KEY AUTOINCREMENT, queue_id INTEGER, client_request_id TEXT, path TEXT, local_payload TEXT, server_payload TEXT, reason TEXT, resolved INTEGER DEFAULT 0, created_at TEXT DEFAULT CURRENT_TIMESTAMP);
    CREATE TABLE IF NOT EXISTS print_log (id INTEGER PRIMARY KEY AUTOINCREMENT, bill_no TEXT, print_type TEXT, copies INTEGER DEFAULT 1, is_reprint INTEGER DEFAULT 0, created_at TEXT DEFAULT CURRENT_TIMESTAMP);
  `)
  // Databases created before the birthday field was introduced need an additive migration.
  try { database.run('ALTER TABLE members ADD COLUMN birthday TEXT') } catch {}
  try { database.run('ALTER TABLE members ADD COLUMN gender TEXT') } catch {}
  try { database.run('ALTER TABLE members ADD COLUMN points INTEGER DEFAULT 0') } catch {}
  try { database.run('ALTER TABLE members ADD COLUMN total_consume REAL DEFAULT 0') } catch {}
  try { database.run('ALTER TABLE members ADD COLUMN source TEXT') } catch {}
  try { database.run('ALTER TABLE members ADD COLUMN sales_id INTEGER') } catch {}
  // Keep databases created by older front-end builds compatible with conflict resolution.
  try { database.run('ALTER TABLE sync_conflict ADD COLUMN resolution TEXT') } catch {}
  try { database.run('ALTER TABLE sync_conflict ADD COLUMN resolved_at TEXT') } catch {}
  const insertProduct = row => database.run('INSERT OR REPLACE INTO products (id,barcode,name,category,weight,sale_price,cost_price,price_type,stock,image,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)', [row.id, row.barcode, row.name, row.category || row.category_name || '其他', Number(row.weight || 0), Number(row.salePrice ?? row.sale_price ?? 0), Number(row.costPrice ?? row.cost_price ?? 0), Number(row.priceType ?? row.price_type ?? 1), Number(row.stock || 0), row.image || ''])
  const insertMember = row => database.run('INSERT OR REPLACE INTO members (id,name,phone,birthday,gender,balance,points,total_consume,source,sales_id,tags,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)', [row.id ?? row.member_id ?? Date.now(), row.name || '', row.phone || '', row.birthday || null, row.gender || null, Number(row.balance || 0), Number(row.points || 0), Number(row.total_consume ?? row.totalConsume ?? 0), row.source || null, row.sales_id ?? row.salesId ?? null, typeof row.tags === 'string' ? row.tags : JSON.stringify(row.tags || [])])
  if (!all('SELECT count(*) c FROM products')[0].c) [
    { id: 1, barcode: '697000000001', name: '古法传承手镯', category: '成品黄金', weight: 32.5, salePrice: 612, costPrice: 530, priceType: 1, stock: 8 },
    { id: 2, barcode: '697000000002', name: '足金素圈戒指', category: '成品黄金', weight: 5.2, salePrice: 612, costPrice: 530, priceType: 1, stock: 23 },
    { id: 3, barcode: '697000000003', name: '幸运转运珠', category: '成品黄金', weight: 2.1, salePrice: 698, costPrice: 560, priceType: 2, stock: 15 },
    { id: 4, barcode: '697000000004', name: 'S925银耳钉', category: '银饰', weight: 1.8, salePrice: 268, costPrice: 120, priceType: 2, stock: 31 },
    { id: 5, barcode: '697000000005', name: '手工编绳加工', category: '加工', weight: 0, salePrice: 58, costPrice: 10, priceType: 2, stock: 999 },
    { id: 6, barcode: '697000000006', name: '18K金项链', category: 'K金', weight: 8.6, salePrice: 428, costPrice: 310, priceType: 1, stock: 6 }
  ].forEach(insertProduct)
  if (!all('SELECT count(*) c FROM gold_price')[0].c) [['足金', 612], ['回收金价', 578], ['18K', 428], ['铂金', 386]].forEach(row => database.run('INSERT OR REPLACE INTO gold_price (price_type,price) VALUES (?,?)', row))
  persist()

  return {
    products: keyword => all('SELECT * FROM products WHERE name LIKE ? OR barcode LIKE ? ORDER BY id', [`%${keyword}%`, `%${keyword}%`]),
    gold: () => all('SELECT * FROM gold_price ORDER BY price_type'),
    members: keyword => all('SELECT * FROM members WHERE name LIKE ? OR phone LIKE ? ORDER BY id DESC', [`%${keyword}%`, `%${keyword}%`]),
    enqueue: item => { run('INSERT OR IGNORE INTO sync_queue (client_request_id,method,path,payload,version) VALUES (?,?,?,?,?)', [item.clientRequestId, item.method, item.path, JSON.stringify(item.payload ?? {}), item.version ?? null]); return all('SELECT id FROM sync_queue WHERE client_request_id=?', [item.clientRequestId])[0]?.id },
    queue: () => all("SELECT * FROM sync_queue WHERE status IN ('PENDING','RETRY') ORDER BY id").map(row => ({ ...row, payload: JSON.parse(row.payload) })),
    cancelOrder: (orderClientRequestId, cancellation) => {
      database.run('BEGIN')
      try {
        const pending = all("SELECT id,client_request_id,payload FROM sync_queue WHERE status <> 'DONE'")
        for (const row of pending) {
          const payload = JSON.parse(row.payload)
          if (row.client_request_id === orderClientRequestId || payload.orderClientRequestId === orderClientRequestId) {
            database.run("UPDATE sync_queue SET status='CANCELLED' WHERE id=?", [row.id])
            database.run("UPDATE sync_conflict SET resolved=1,resolution='CANCELLED' WHERE queue_id=?", [row.id])
          }
        }
        database.run('INSERT OR IGNORE INTO sync_queue(client_request_id,method,path,payload) VALUES (?,?,?,?)', [cancellation.clientRequestId, cancellation.method, cancellation.path, JSON.stringify(cancellation.payload)])
        database.run('COMMIT')
        persist()
        return true
      } catch (error) { database.run('ROLLBACK'); throw error }
    },
    queueDone: (id, response) => run("UPDATE sync_queue SET status='DONE',synced_at=CURRENT_TIMESTAMP,last_error=NULL,server_response=? WHERE id=?", [JSON.stringify(response ?? {}), id]),
    queueFailed: (id, message) => run("UPDATE sync_queue SET attempts=attempts+1,last_error=? WHERE id=?", [String(message || '同步失败').slice(0, 500), id]),
    conflict: item => {
      if (item.queueId != null) run("UPDATE sync_queue SET status='CONFLICT',last_error=? WHERE id=?", [item.reason ?? '冲突', item.queueId])
      run('INSERT INTO sync_conflict (queue_id,client_request_id,path,local_payload,server_payload,reason) VALUES (?,?,?,?,?,?)', [item.queueId ?? null, item.clientRequestId ?? null, item.path ?? '', typeof item.localPayload === 'string' ? item.localPayload : JSON.stringify(item.localPayload ?? {}), item.serverPayload ?? '', item.reason ?? '冲突'])
      return all('SELECT id FROM sync_conflict WHERE client_request_id=? ORDER BY id DESC LIMIT 1', [item.clientRequestId ?? null])[0]?.id
    },
    conflicts: () => all('SELECT * FROM sync_conflict WHERE resolved=0 ORDER BY id DESC'),
    resolveConflict: (id, resolution) => {
      const row = all('SELECT * FROM sync_conflict WHERE id=? AND resolved=0', [id])[0]
      if (!row) return false
      if (resolution === 'LOCAL' && row.queue_id != null) run("UPDATE sync_queue SET status='RETRY',attempts=0,last_error=NULL WHERE id=?", [row.queue_id])
      if (resolution === 'CLOUD' && row.queue_id != null) run("UPDATE sync_queue SET status='DONE',synced_at=CURRENT_TIMESTAMP,last_error=NULL,server_response=? WHERE id=?", [row.server_payload || '{}', row.queue_id])
      run('UPDATE sync_conflict SET resolved=1,resolution=?,resolved_at=CURRENT_TIMESTAMP WHERE id=?', [resolution, id])
      return true
    },
    printLog: item => run('INSERT INTO print_log (bill_no,print_type,copies,is_reprint) VALUES (?,?,?,?)', [item.billNo, item.printType, item.copies || 1, item.isReprint || 0]),
    seed: payload => {
      database.run('BEGIN')
      try {
        if (payload?.replaceProducts) database.run('DELETE FROM products')
        if (payload?.replaceMembers) database.run('DELETE FROM members')
        ;(payload?.products || []).forEach(insertProduct)
        ;(payload?.gold || []).forEach(row => database.run('INSERT OR REPLACE INTO gold_price (price_type,price,updated_at) VALUES (?,?,CURRENT_TIMESTAMP)', [row.priceType || row.price_type, Number(row.price)]))
        ;(payload?.members || []).forEach(insertMember)
        database.run('COMMIT')
        persist()
        return true
      } catch (error) {
        try { database.run('ROLLBACK') } catch {}
        throw error
      }
    }
  }
}
module.exports = { createDb }
