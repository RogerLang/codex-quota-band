const assert = require('node:assert/strict')
const test = require('node:test')
const protocol = require('../src/common/stage02-protocol.cjs')

test('ping and pong round trip', () => {
  const nonce = '00112233445566778899aabbccddeeff'
  const message = protocol.create('stage02_ping', nonce)
  assert.deepEqual(protocol.parse(message), message)
  assert.equal(protocol.matches(protocol.create('stage02_pong', nonce), 'stage02_pong', nonce), true)
})

test('wrong nonce and unknown message rejected', () => {
  const nonce = '00112233445566778899aabbccddeeff'
  assert.equal(protocol.matches(protocol.create('stage02_pong', nonce), 'stage02_pong', 'ffeeddccbbaa99887766554433221100'), false)
  assert.equal(protocol.parse({ type: 'quota_snapshot', nonce }), null)
  assert.equal(protocol.parse({ type: 'stage02_ping', nonce, data: 1 }), null)
})

test('deadline expires', () => {
  assert.equal(protocol.timedOut(999, 1000), false)
  assert.equal(protocol.timedOut(1000, 1000), true)
})
