import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import path from 'node:path'
import { randomBytes, createHash } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const privateDir = process.env.DAJIN_ANDROID_SIGNING_DIR || path.join(homedir(), '.dajin', 'android-signing')
const configFile = path.join(privateDir, 'signing.json')
const sdk = process.env.ANDROID_HOME
const java = process.env.JAVA_HOME
if (!sdk || !java) throw new Error('Set ANDROID_HOME and JAVA_HOME before signing')
const tools = path.join(sdk, 'build-tools', '35.0.0')
const unsigned = path.join(root, 'android/app/build/outputs/apk/release/app-release-unsigned.apk')
if (!existsSync(unsigned)) throw new Error('Build assembleRelease first')
const metadata = JSON.parse(readFileSync(path.join(path.dirname(unsigned), 'output-metadata.json'), 'utf8'))
const version = metadata.elements[0].versionName
const outputDir = path.resolve(process.env.DAJIN_ANDROID_OUTPUT || path.join(root, '../../releases/android'))
mkdirSync(outputDir, { recursive: true })

function run(command, args, env = process.env) {
  const result = spawnSync(command, args, { env, encoding: 'utf8', windowsHide: true })
  if (result.status !== 0) throw new Error(`Tool failed: ${path.basename(command)}\n${result.stderr || result.error || ''}`)
  return result.stdout || ''
}

mkdirSync(privateDir, { recursive: true, mode: 0o700 })
if (process.platform === 'win32') {
  const account = run('whoami.exe', []).trim()
  run('icacls.exe', [privateDir, '/inheritance:r', '/grant:r', `${account}:(OI)(CI)F`, 'SYSTEM:(OI)(CI)F'])
}
if (!existsSync(configFile)) {
  if (!process.argv.includes('--init-signing')) throw new Error('Signing config missing. For FIRST release only, use --init-signing; otherwise restore the original signing backup.')
  const keyStore = path.join(privateDir, 'dajin-mobile-release.p12')
  if (existsSync(keyStore)) throw new Error('Existing keystore found: restore its config; do not replace its identity')
  writeFileSync(configFile, JSON.stringify({ keyStore, alias: 'dajin-mobile', password: randomBytes(32).toString('base64url') }, null, 2), { mode: 0o600, flag: 'wx' })
}
const config = JSON.parse(readFileSync(configFile, 'utf8'))
const signEnv = { ...process.env, DAJIN_KEY_PASSWORD: config.password }
if (!existsSync(config.keyStore)) {
  if (!process.argv.includes('--init-signing')) throw new Error('Original signing keystore missing: restore signing backup')
  run(path.join(java, 'bin/keytool.exe'), ['-genkeypair', '-keystore', config.keyStore, '-storetype', 'PKCS12', '-storepass:env', 'DAJIN_KEY_PASSWORD', '-keypass:env', 'DAJIN_KEY_PASSWORD', '-alias', config.alias, '-keyalg', 'RSA', '-keysize', '3072', '-validity', '10000', '-dname', 'CN=Dajin Mobile, OU=Internal Distribution, O=Xinchengjinjiang, C=CN', '-noprompt'], signEnv)
}
const apk = path.join(outputDir, `dajin-mobile-${version}.apk`)
const aligned = path.join(path.dirname(unsigned), 'app-release-aligned.apk')
run(path.join(tools, 'zipalign.exe'), ['-f', '-p', '4', unsigned, aligned])
const signerJar = path.join(tools, 'lib/apksigner.jar')
run(path.join(java, 'bin/java.exe'), ['-jar', signerJar, 'sign', '--ks', config.keyStore, '--ks-key-alias', config.alias, '--ks-pass', 'env:DAJIN_KEY_PASSWORD', '--key-pass', 'env:DAJIN_KEY_PASSWORD', '--out', apk, aligned], signEnv)
const verification = run(path.join(java, 'bin/java.exe'), ['-jar', signerJar, 'verify', '--verbose', '--print-certs', apk])
run(path.join(tools, 'zipalign.exe'), ['-c', '-p', '4', apk])
writeFileSync(`${apk}.verification.txt`, verification)
const sha = createHash('sha256').update(readFileSync(apk)).digest('hex')
writeFileSync(`${apk}.sha256`, `${sha}  ${path.basename(apk)}\n`)
console.log(`Signed APK: ${apk}\nSHA256: ${sha}\nPrivate signing backup required: ${privateDir}`)
