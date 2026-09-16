const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')

const root = path.resolve(__dirname, '..')
const read = (file) => fs.readFileSync(path.join(root, file), 'utf8')

test('Stage 03C exposes a black relay route and terminates after ACK send success', () => {
  const manifest = JSON.parse(read('src/manifest.json'))
  assert.equal(manifest.router.pages['pages/relay'].component, 'relay')
  const relay = read('src/pages/relay/relay.ux')
  assert.match(relay, /background-color:\s*#000000/i)
  const app = read('src/app.ux')
  assert.match(app, /import app from '@system\.app'/)
  assert.match(app, /success:\s*returnToWatchfaceSoon/)
  assert.match(app, /app\.terminate\(\)/)
})
