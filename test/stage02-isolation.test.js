import assert from 'node:assert/strict'
import { execFileSync } from 'node:child_process'
import { readFileSync } from 'node:fs'
import path from 'node:path'
import test from 'node:test'

const root = path.resolve(import.meta.dirname, '..')
const read = (p) => readFileSync(path.join(root, p), 'utf8')

test('probe and validation APK use the same isolated application identity', () => {
  const manifest = JSON.parse(read('experiments/band/base-probe/src/manifest.json'))
  const gradle = read('android-app/app/build.gradle.kts')
  assert.equal(manifest.package, 'io.github.rogerlang.codexquota.validation')
  assert.equal(manifest.config.designWidth, 336)
  assert.match(gradle, /applicationId = "io.github.rogerlang.codexquota"/)
  assert.match(gradle, /applicationIdSuffix = "\.validation"/)
  assert.match(gradle, /signingConfig = signingConfigs\.findByName\("validation"\)/)
})

test('Stage 02 protocol and UI remain outside production Android sources', () => {
  const source = execFileSync('rg', ['--files', 'android-app/app/src/main', 'android-app/app/src/debug'], { cwd: root, encoding: 'utf8' })
  assert.doesNotMatch(source, /Stage02|Stage02D|validation[\\/]ValidationActivity/)
  assert.doesNotMatch(read('android-app/app/src/main/AndroidManifest.xml'), /ValidationActivity|Stage02D|Stage02/)
})

test('all local signing material is gitignored', () => {
  for (const file of [
    'android-app/local.properties',
    'android-app/stage02-validation.p12',
    'experiments/band/base-probe/sign/release/private.pem',
    'experiments/band/base-probe/sign/release/certificate.pem',
  ]) {
    const ignored = execFileSync('git', ['check-ignore', file], { cwd: root, encoding: 'utf8' }).trim()
    assert.equal(ignored.replaceAll('\\', '/'), file)
  }
})
