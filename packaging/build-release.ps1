# build-release.ps1 — Construye los artefactos de release de FenixHub (Windows)
# Uso: .\packaging\build-release.ps1
# Requisitos: bun, Rust/Cargo, el instalador en D:\instalador

param(
    [string]$Version = "",
    [string]$InstallerRepo = "D:\instalador",
    [string]$OutputDir = ".\packaging\release-output"
)

$ErrorActionPreference = 'Stop'
$root = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path

# ── Leer versión del tauri.conf.json si no se pasa ────────────────────────
if (-not $Version) {
    $conf = Get-Content (Join-Path $root 'src-tauri\tauri.conf.json') | ConvertFrom-Json
    $Version = $conf.version
}
Write-Host "=== FenixHub release build v$Version ===" -ForegroundColor Cyan

# ── Crear directorio de output ─────────────────────────────────────────────
$out = Join-Path $root $OutputDir
New-Item -ItemType Directory -Force -Path $out | Out-Null

# ── 1) Build Tauri ──────────────────────────────────────────────────────────
Write-Host "`n[1/3] Building Tauri app..." -ForegroundColor Yellow
Set-Location $root
bun tauri build
if ($LASTEXITCODE -ne 0) { throw "tauri build failed" }

# ── 2) Copiar exe al stage ──────────────────────────────────────────────────
Write-Host "`n[2/3] Copying executable to installer stage..." -ForegroundColor Yellow
$exeSrc  = Join-Path $root 'target\release\fenix-hub.exe'
$stageApp = Join-Path $root 'packaging\windows-stage\app'
$exeStageName = 'FenixHub.exe'
$exeDst = Join-Path $stageApp $exeStageName

if (-not (Test-Path $exeSrc)) { throw "FenixHub.exe not found at $exeSrc" }

# Limpiar y repoblar el directorio app/ del stage
Remove-Item -Recurse -Force $stageApp -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path $stageApp | Out-Null
Copy-Item $exeSrc $exeDst
Write-Host "  staged executable -> $exeDst"

# Copiar DLLs/recursos extra si existen junto al exe
$extras = @('WebView2Loader.dll', 'resources')
foreach ($e in $extras) {
    $src = Join-Path (Split-Path $exeSrc) $e
    if (Test-Path $src) { Copy-Item -Recurse $src $stageApp }
}

# Actualizar versión en installer_config.json
$configPath = Join-Path $root 'packaging\windows-stage\installer_config.json'
$cfg = Get-Content $configPath | ConvertFrom-Json
$cfg.version = $Version
$cfg | ConvertTo-Json -Depth 10 | Set-Content $configPath
Write-Host "  installer_config.json version → $Version"

# ── 3) Build instalador custom ─────────────────────────────────────────────
Write-Host "`n[3/3] Building custom installer..." -ForegroundColor Yellow
$stageDir  = Join-Path $root 'packaging\windows-stage'
$outputExe = Join-Path $out "FenixHub_${Version}_x64-Setup.exe"

$packExe = if (Test-Path (Join-Path $InstallerRepo 'src-tauri\target\release\pack.exe')) {
    Join-Path $InstallerRepo 'src-tauri\target\release\pack.exe'
} else {
    Join-Path $InstallerRepo 'pack.exe'
}
$shellExe = if (Test-Path (Join-Path $InstallerRepo 'src-tauri\target\release\fenix-installer.exe')) {
    Join-Path $InstallerRepo 'src-tauri\target\release\fenix-installer.exe'
} else {
    Join-Path $InstallerRepo 'fenix-installer.exe'
}
$uninsExe = if (Test-Path (Join-Path $InstallerRepo 'src-tauri\target\release\unins000.exe')) {
    Join-Path $InstallerRepo 'src-tauri\target\release\unins000.exe'
} else {
    Join-Path $InstallerRepo 'unins000.exe'
}

if (Test-Path $uninsExe) {
    Copy-Item $uninsExe (Join-Path $stageDir 'unins000.exe') -Force
}

$manifestPath = Join-Path $stageDir 'installer_config.json'
Write-Host "  Shell:  $shellExe"
Write-Host "  Stage:  $stageDir"
Write-Host "  Output: $outputExe"

# pack.exe no funciona con & en PowerShell (problema con stderr); usar cmd /c
cmd /c "`"$packExe`" --installer `"$shellExe`" --stage `"$stageDir`" --config `"$manifestPath`" --output `"$outputExe`""

if ($LASTEXITCODE -ne 0) { throw "Installer build failed (pack.exe exit code $LASTEXITCODE)" }

Write-Host "`n=== Done ===" -ForegroundColor Green
Write-Host "Output: $outputExe" -ForegroundColor Green
