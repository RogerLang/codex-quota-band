param(
  [string]$OutputDir
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $OutputDir) { $OutputDir = Join-Path $repo 'out\stage03e' }
$source = Join-Path $repo 'watchface-stage03e-probe'
$temp = Join-Path $repo '.temp_stage03e_watchface'
$template = Join-Path $temp 'LuaDevTemplate'
$work = Join-Path $temp 'work'
$pinned = '0eb8346ce0c9c11f2316c6b154ed91fd4a0d419d'
$faceId = '491552740'
$faceName = 'CodexQuota-Stage03E-notify-storage.face'

if (Test-Path $temp) { Remove-Item -LiteralPath $temp -Recurse -Force }
New-Item -ItemType Directory -Path $temp,$work,$OutputDir -Force | Out-Null

git clone --quiet https://github.com/FangAiden/LuaDevTemplate.git $template
if ($LASTEXITCODE -ne 0) { throw 'Failed to clone pinned watchface build template.' }
git -C $template checkout --quiet $pinned
if ($LASTEXITCODE -ne 0) { throw 'Failed to checkout pinned watchface build template.' }
$actual = (git -C $template rev-parse HEAD).Trim()
if ($actual -ne $pinned) { throw "Unexpected LuaDevTemplate revision: $actual" }

New-Item -ItemType Directory -Path (Join-Path $work 'app\lua'),(Join-Path $work 'images') -Force | Out-Null
Copy-Item -LiteralPath (Join-Path $source 'app\lua\main.lua') -Destination (Join-Path $work 'app\lua\main.lua') -Force

# The pinned Compiler.exe reports a 230x328 preview requirement for DeviceType 367.
# This bitmap is package metadata only; the Lua widget itself is 336x480.
$preview = Join-Path $work 'images\preview.png'
Add-Type -AssemblyName System.Drawing
$bitmap = New-Object System.Drawing.Bitmap 230,328
$graphics = [System.Drawing.Graphics]::FromImage($bitmap)
try {
  $graphics.Clear([System.Drawing.Color]::Black)
  $fontTitle = New-Object System.Drawing.Font('Arial', 18, [System.Drawing.FontStyle]::Bold)
  $fontBody = New-Object System.Drawing.Font('Arial', 13, [System.Drawing.FontStyle]::Regular)
  $white = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::White)
  $green = New-Object System.Drawing.SolidBrush([System.Drawing.Color]::FromArgb(114,214,166))
  try {
    $graphics.DrawString('CodexQuota', $fontTitle, $white, 48, 86)
    $graphics.DrawString('Stage 03E', $fontBody, $green, 76, 129)
    $graphics.DrawString('Notify storage probe', $fontBody, $white, 43, 171)
    $graphics.DrawString('FOUND / NOT FOUND', $fontBody, $white, 42, 207)
  } finally {
    $fontTitle.Dispose()
    $fontBody.Dispose()
    $white.Dispose()
    $green.Dispose()
  }
  $bitmap.Save($preview, [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
  $graphics.Dispose()
  $bitmap.Dispose()
}

$fprj = Join-Path $work 'stage03e_notify_storage_probe.fprj'
$xml = Get-Content -LiteralPath (Join-Path $source 'stage03e_notify_storage_probe.fprj') -Raw -Encoding UTF8
$xml = $xml -replace 'encoding="utf-8"', 'encoding="utf-16"'
[System.IO.File]::WriteAllText($fprj, $xml, [System.Text.Encoding]::Unicode)

$compiler = Join-Path $template 'watchface\tools\Compiler.exe'
if (-not (Test-Path $compiler)) { throw 'Pinned template does not contain Compiler.exe.' }
$stdoutPath = Join-Path $temp 'compiler.stdout.txt'
$stderrPath = Join-Path $temp 'compiler.stderr.txt'
$process = Start-Process -FilePath $compiler -ArgumentList @('-b', $fprj, $OutputDir, $faceName, $faceId) `
  -NoNewWindow -Wait -PassThru -RedirectStandardOutput $stdoutPath -RedirectStandardError $stderrPath
$compilerExit = $process.ExitCode
$compilerStdout = if (Test-Path $stdoutPath) { Get-Content -LiteralPath $stdoutPath -Raw } else { '' }
$compilerStderr = if (Test-Path $stderrPath) { Get-Content -LiteralPath $stderrPath -Raw } else { '' }
if ($compilerStdout) { Write-Output $compilerStdout.TrimEnd() }
if ($compilerStderr) { Write-Warning $compilerStderr.TrimEnd() }

$face = Join-Path $OutputDir $faceName
$compilerText = $compilerStdout + "`n" + $compilerStderr
$reportedReady = $compilerText -match 'Watchface:\s+.+\bis ready\b'
$reportedNoErrors = $compilerText -match '\bNo Errors\b'
$knownPostSuccessClrExit = -532462766 # 0xE0434352, generic unhandled .NET exception.

if ($compilerExit -ne 0) {
  $postSuccessCrash =
    $compilerExit -eq $knownPostSuccessClrExit -and
    (Test-Path $face) -and
    $reportedReady -and
    $reportedNoErrors
  if ($postSuccessCrash) {
    Write-Warning "Compiler.exe produced the watchface and reported success, then exited with known post-success CLR code $compilerExit; accepting artifact for Stage 03E validation."
  } else {
    throw "Watchface compiler failed with exit code $compilerExit."
  }
}

if (-not (Test-Path $face)) { throw "Expected watchface artifact not found: $face" }
$faceInfo = Get-Item -LiteralPath $face
if ($faceInfo.Length -lt 4096) { throw "Watchface artifact is unexpectedly small: $($faceInfo.Length) bytes." }
$faceHash = (Get-FileHash -LiteralPath $face -Algorithm SHA256).Hash
Write-Output "Built $face"
Write-Output "Size: $($faceInfo.Length) bytes"
Write-Output "SHA-256: $faceHash"
Write-Output "Compiler exit: $compilerExit"
Write-Output "LuaDevTemplate commit: $pinned"
Write-Output "Watchface ID: $faceId"
