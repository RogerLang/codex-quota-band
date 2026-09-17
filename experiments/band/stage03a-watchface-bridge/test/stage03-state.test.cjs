const assert = require('node:assert/strict')
const test = require('node:test')
const stage03 = require('../src/common/stage03-state.cjs')

test('strictly parses stage03 state and creates ack', () => {
  const state = { type: 'stage03_state', version: 1, sequence: 42, nonce: '0123456789abcdef0123456789abcdef' }
  assert.deepEqual(stage03.parseState(state), state)
  assert.deepEqual(stage03.createAck(state, true), {
    type: 'stage03_state_ack', version: 1, sequence: 42,
    nonce: state.nonce, persisted: true,
  })
})

test('rejects unknown fields and invalid sequence', () => {
  const base = { type: 'stage03_state', version: 1, sequence: 42, nonce: '0123456789abcdef0123456789abcdef' }
  assert.equal(stage03.parseState({ ...base, extra: true }), null)
  assert.equal(stage03.parseState({ ...base, sequence: -1 }), null)
  assert.equal(stage03.parseState({ ...base, nonce: 'short' }), null)
})

test('persisted state contains only bridge metadata', () => {
  const state = { type: 'stage03_state', version: 1, sequence: 43, nonce: 'fedcba9876543210fedcba9876543210' }
  assert.deepEqual(JSON.parse(stage03.persistedState(state, 1234)), { version: 1, sequence: 43, updatedAt: 1234 })
})
