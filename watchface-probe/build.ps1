param(
  [string]$OutputDir
)

$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
if (-not $OutputDir) { $OutputDir = Join-Path $repo 'out\stage03a' }
$source = Join-Path $repo 'watchface-probe'
$temp = Join-Path $repo '.temp_stage03a_watchface'
$template = Join-Path $temp 'LuaDevTemplate'
$work = Join-Path $temp 'work'
$pinned = '0eb8346ce0c9c11f2316c6b154ed91fd4a0d419d'
$faceId = '491552739'
$faceName = 'CodexQuota-Stage03A-watchface.face'

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
Copy-Item -LiteralPath (Join-Path $template 'watchface\fprj\images\preview.png') -Destination (Join-Path $work 'images\preview.png') -Force

# EasyFace-compatible projects in the community examples are UTF-16LE XML.
$xml = Get-Content -LiteralPath (Join-Path $source 'Stage03AProbe.fprj') -Raw -Encoding UTF8
$xml = $xml -replace 'encoding="utf-8"', 'encoding="utf-16"'
[System.IO.File]::WriteAllText((Join-Path $work 'Stage03AProbe.fprj'), $xml, [System.Text.Encoding]::Unicode)

$compiler = Join-Path $template 'watchface\tools\Compiler.exe'
if (-not (Test-Path $compiler)) { throw 'Pinned template does not contain Compiler.exe.' }
& $compiler -b (Join-Path $work 'Stage03AProbe.fprj') $OutputDir $faceName $faceId
if ($LASTEXITCODE -ne 0) { throw "Watchface compiler failed with exit code $LASTEXITCODE." }

$face = Join-Path $OutputDir $faceName
if (-not (Test-Path $face)) { throw "Expected watchface artifact not found: $face" }
Write-Output "Built $face"
Write-Output "LuaDevTemplate commit: $pinned"
Write-Output "Watchface ID: $faceId"
