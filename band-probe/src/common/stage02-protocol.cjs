const TYPES = new Set(['stage02_ping', 'stage02_pong', 'stage02_band_ping', 'stage02_android_pong'])
const NONCE = /^[0-9a-f]{32}$/

function create(type, nonce) {
  if (!TYPES.has(type) || !NONCE.test(nonce)) throw new Error('invalid probe message')
  return { type, nonce }
}

function parse(value) {
  try {
    const obj = typeof value === 'string' ? JSON.parse(value) : value
    if (!obj || typeof obj !== 'object' || Array.isArray(obj)) return null
    if (Object.keys(obj).length !== 2 || !Object.prototype.hasOwnProperty.call(obj, 'type') || !Object.prototype.hasOwnProperty.call(obj, 'nonce')) return null
    return TYPES.has(obj.type) && typeof obj.nonce === 'string' && NONCE.test(obj.nonce) ? obj : null
  } catch (_) { return null }
}

function matches(value, type, nonce) {
  const message = parse(value)
  return !!message && message.type === type && message.nonce === nonce
}

function timedOut(nowMs, deadlineMs) { return nowMs >= deadlineMs }

module.exports = { create, parse, matches, timedOut }
