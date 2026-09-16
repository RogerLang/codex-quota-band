const assert = require('node:assert/strict')
const test = require('node:test')
const { diagnosisStatus } = require('../src/common/stage02-diagnosis.cjs')

test('official diagnosis statuses use fixed labels', () => {
  assert.equal(diagnosisStatus(0), 'OK')
  assert.equal(diagnosisStatus(204), 'TIMEOUT')
  assert.equal(diagnosisStatus(1001), 'APP_UNINSTALLED')
  assert.equal(diagnosisStatus(1000), 'OTHER')
  assert.equal(diagnosisStatus(12345), 'OTHER')
  assert.equal(diagnosisStatus(undefined), 'OTHER')
})
