'use strict'

const VERSION = 1
const STATE = 'stage03_state'
const ACK = 'stage03_state_ack'
const noncePattern = /^[0-9a-f]{32}$/

function exactKeys(obj, keys) {
  const actual = Object.keys(obj).sort()
  const expected = [...keys].sort()
  return actual.length === expected.length && actual.every((key, index) => key === expected[index])
}

function parseState(raw) {
  let obj = raw
  if (typeof raw === 'string') {
    try { obj = JSON.parse(raw) } catch (_) { return null }
  }
  if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return null
  if (!exactKeys(obj, ['type', 'version', 'sequence', 'nonce'])) return null
  if (obj.type !== STATE || obj.version !== VERSION) return null
  if (!Number.isInteger(obj.sequence) || obj.sequence < 0 || obj.sequence > 999999) return null
  if (typeof obj.nonce !== 'string' || !noncePattern.test(obj.nonce)) return null
  return { type: STATE, version: VERSION, sequence: obj.sequence, nonce: obj.nonce }
}

function createAck(message, persisted) {
  return {
    type: ACK,
    version: VERSION,
    sequence: message.sequence,
    nonce: message.nonce,
    persisted: persisted === true,
  }
}

function persistedState(message, updatedAt) {
  return JSON.stringify({ version: VERSION, sequence: message.sequence, updatedAt: Number(updatedAt) || 0 })
}

module.exports = { VERSION, STATE, ACK, parseState, createAck, persistedState }
