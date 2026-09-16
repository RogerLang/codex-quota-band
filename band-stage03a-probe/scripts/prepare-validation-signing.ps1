$ErrorActionPreference = 'Stop'

$repo = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$source = Join-Path $repo 'band-probe\sign\release'
$target = Join-Path $repo 'band-stage03a-probe\sign\release'
$prepare = Join-Path $repo 'band-probe\scripts\prepare-validation-signing.ps1'

& $prepare
if (-not (Test-Path (Join-Path $source 'private.pem')) -or -not (Test-Path (Join-Path $source 'certificate.pem'))) {
  throw 'Stage 02 validation signing material is unavailable.'
}
New-Item -ItemType Directory -Path $target -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $source 'private.pem') -Destination (Join-Path $target 'private.pem') -Force
Copy-Item -LiteralPath (Join-Path $source 'certificate.pem') -Destination (Join-Path $target 'certificate.pem') -Force
Write-Output 'Stage 03A probe uses the existing validation identity.'
