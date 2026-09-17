function diagnosisStatus(code) {
  if (code === 0) return 'OK'
  if (code === 204) return 'TIMEOUT'
  if (code === 1001) return 'APP_UNINSTALLED'
  return 'OTHER'
}

module.exports = { diagnosisStatus }
