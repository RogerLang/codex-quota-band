const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const test = require('node:test')
const vm = require('node:vm')
const protocol = require('../src/common/stage02-protocol.cjs')
const diagnosis = require('../src/common/stage02-diagnosis.cjs')

test('watch shows phone reply without waiting for send callbacks', () => {
  const source = fs.readFileSync(path.join(__dirname, '../src/pages/index/index.ux'), 'utf8')
  const script = source.match(/<script>([\s\S]*?)<\/script>/)[1]
    .replace(/^import .*$/gm, '')
    .replace('export default', 'module.exports =')
  const sent = []
  const connection = { send: (request) => sent.push(request.data) }
  const module = { exports: {} }
  const timers = new Set()
  vm.runInNewContext(script, {
    module, interconnect: { instance: () => connection }, protocol, diagnosis,
    setTimeout: (callback) => { timers.add(callback); return callback },
    clearTimeout: (callback) => { timers.delete(callback) },
    Math,
  })
  const page = { ...module.exports.private, ...module.exports }
  page.connected = true
  page.testBandToPhone()
  assert.equal(sent[0].type, 'stage02_band_ping')
  assert.equal(page.messageText, '检查中')
  assert.equal(timers.size, 1, 'reply timeout starts even without a send success callback')

  page.handleMessage(protocol.create('stage02_android_pong', sent[0].nonce))
  assert.equal(page.messageText, 'PASS')
  assert.equal(page.hintText, '手机已回复')
  assert.equal(sent[1].type, 'stage02_pong')
  assert.equal(timers.size, 0)
})
