const fs = require('fs')
const initSqlJs = require('sql.js')

async function main() {
  const file = process.argv[2]
  if (!file || !fs.existsSync(file)) throw new Error(`SQLite file not found: ${file}`)
  const distDir = require('path').dirname(require.resolve('sql.js/dist/sql-wasm.js'))
  const SQL = await initSqlJs({ locateFile: name => require('path').join(distDir, name) })
  const db = new SQL.Database(fs.readFileSync(file))
  const query = sql => {
    const statement = db.prepare(sql)
    const rows = []
    while (statement.step()) rows.push(statement.getAsObject())
    statement.free()
    return rows
  }
  console.log(JSON.stringify({
    queue: query("select status,count(*) count from sync_queue group by status"),
    pending: query("select id,client_request_id,path,status,attempts,last_error,payload from sync_queue where status in ('PENDING','RETRY') order by id"),
    done: query("select id,path,status,server_response from sync_queue where status='DONE' order by id"),
    conflicts: query('select count(*) count from sync_conflict where resolved=0'),
    printLogs: query('select count(*) count from print_log')
  }))
}

main().catch(error => { console.error(error.message); process.exitCode = 1 })
