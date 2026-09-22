const fs = require('node:fs')
const path = require('node:path')
const { pathToFileURL, fileURLToPath } = require('node:url')

// Resolve the built HTML exactly as Electron loadFile does, without a Vite server.
const entry = path.resolve(__dirname, '..', 'dist', 'index.html')
const root = path.dirname(entry)
const html = fs.readFileSync(entry, 'utf8')
const references = [...html.matchAll(/(?:src|href)=["']([^"']+\.(?:js|css)(?:[?#][^"']*)?)["']/g)]
const failures = []

if (!references.some(([, ref]) => /\.js(?:[?#]|$)/.test(ref))) failures.push('Missing JavaScript entry')
if (!references.some(([, ref]) => /\.css(?:[?#]|$)/.test(ref))) failures.push('Missing stylesheet entry')

for (const [, reference] of references) {
  const resource = new URL(reference, pathToFileURL(entry))
  if (resource.protocol !== 'file:') {
    failures.push(`${reference}: expected a bundled local file`)
    continue
  }
  const file = fileURLToPath(resource)
  const relative = path.relative(root, file)
  if (relative === '..' || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative)) {
    failures.push(`${reference}: resolves outside dist (${file})`)
  } else if (!fs.existsSync(file) || !fs.statSync(file).isFile()) {
    failures.push(`${reference}: file missing (${file})`)
  } else {
    console.log(`PASS ${reference}`)
  }
}

if (failures.length) {
  console.error(`Electron file:// asset check failed:\n${failures.join('\n')}`)
  process.exitCode = 1
} else {
  console.log(`Electron file:// asset check passed (${references.length} resources)`)
}
