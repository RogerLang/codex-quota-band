$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$signDir = Join-Path $repo 'band-probe\sign\release'
$store = Join-Path $repo 'android-app\stage02-validation.p12'
$propertiesPath = Join-Path $repo 'android-app\local.properties'
$openssl = 'C:\Program Files\Git\usr\bin\openssl.exe'
if (-not (Test-Path $openssl)) { throw 'OpenSSL is unavailable.' }
if (Test-Path $store) {
  if (-not (Test-Path (Join-Path $signDir 'private.pem')) -or -not (Test-Path (Join-Path $signDir 'certificate.pem'))) {
    throw 'Existing Stage 02 store has no matching RPK signing material; refusing to replace identity.'
  }
  Write-Output 'Existing local Stage 02 identity retained.'
  exit 0
}

New-Item -ItemType Directory -Path $signDir -Force | Out-Null
$key = Join-Path $signDir 'private.pem'
$cert = Join-Path $signDir 'certificate.pem'
$password = [Convert]::ToBase64String([System.Security.Cryptography.RandomNumberGenerator]::GetBytes(32))
try {
  & $openssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 3650 -keyout $key -out $cert -subj '/CN=CodexQuota Stage 02 Validation' 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Certificate generation failed.' }
  & $openssl pkcs12 -export -inkey $key -in $cert -out $store -name stage02 -passout "pass:$password" 2>$null | Out-Null
  if ($LASTEXITCODE -ne 0) { throw 'Android PKCS12 store generation failed.' }
  if (Test-Path $propertiesPath) {
    $existing = Get-Content -LiteralPath $propertiesPath -Raw
    if ($existing -match 'codexQuotaValidationStoreFile=') { throw 'Validation signing already configured; refusing to replace it.' }
  } else {
    $existing = ''
  }
  $lines = @(
    'codexQuotaValidationStoreFile=stage02-validation.p12'
    "codexQuotaValidationStorePassword=$password"
    'codexQuotaValidationKeyAlias=stage02'
    "codexQuotaValidationKeyPassword=$password"
  )
  $separator = if ($existing -and -not $existing.EndsWith("`n")) { "`n" } else { '' }
  [System.IO.File]::AppendAllText($propertiesPath, $separator + ($lines -join "`n") + "`n")
  Write-Output 'Created ignored local Stage 02 signing identity for APK and RPK.'
} catch {
  if (Test-Path $store) { Remove-Item -LiteralPath $store }
  throw
}
