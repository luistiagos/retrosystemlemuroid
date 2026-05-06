param(
    [string]$OutputDir = "",
    [switch]$Install = $false
)

$ErrorActionPreference = "Stop"

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "Build Release APK (FreeBundleRelease)..." -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -Path $repoRoot

$gradleWrapper = Join-Path $repoRoot "gradlew.bat"

if (-not (Test-Path $gradleWrapper)) {
    Write-Error "gradlew.bat nao encontrado em $repoRoot"
    exit 1
}

$keystorePath = Join-Path $repoRoot "release.jks"
if (-not (Test-Path $keystorePath)) {
    Write-Host "[AVISO] release.jks nao encontrado em $repoRoot" -ForegroundColor Yellow
    Write-Host "         Build vai falhar na etapa de assinatura." -ForegroundColor Yellow
}

Write-Host "Executando: .\gradlew.bat assembleFreeBundleRelease" -ForegroundColor Yellow
& $gradleWrapper assembleFreeBundleRelease

if ($LASTEXITCODE -ne 0) {
    Write-Host "`n[ERRO] Falha durante o build. Verifique os logs acima." -ForegroundColor Red
    exit $LASTEXITCODE
}

$apkDir = Join-Path $repoRoot "lemuroid-app\build\outputs\apk\freeBundle\release"
$apkFile = Get-ChildItem -Path $apkDir -Filter "*.apk" -ErrorAction SilentlyContinue | Sort-Object LastWriteTime -Descending | Select-Object -First 1

if ($apkFile) {
    Write-Host "`n[SUCESSO] APK gerado:" -ForegroundColor Green
    Write-Host "  $($apkFile.FullName)" -ForegroundColor White
    Write-Host "  Tamanho: $([math]::Round($apkFile.Length / 1MB, 2)) MB" -ForegroundColor White

    # Copy with stable name to dist/
    $distDir = if ($OutputDir -ne "") { $OutputDir } else { Join-Path $repoRoot "dist" }
    if (-not (Test-Path $distDir)) { New-Item -ItemType Directory -Path $distDir | Out-Null }
    $stableApkName = "retro-game-system.apk"
    $destApk = Join-Path $distDir $stableApkName
    Copy-Item -Path $apkFile.FullName -Destination $destApk -Force
    Write-Host "  Copiado para: $destApk" -ForegroundColor Cyan
} else {
    Write-Host "`n[AVISO] APK nao encontrado em $apkDir" -ForegroundColor Yellow
}

if ($Install) {
    $adbCmd = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $adbCmd) {
        Write-Host "[AVISO] ADB nao encontrado no PATH. Nao foi possivel instalar." -ForegroundColor Yellow
    } elseif ($apkFile) {
        Write-Host "`nInstalando APK no dispositivo..." -ForegroundColor Yellow
        & adb install -r $destApk
        if ($LASTEXITCODE -eq 0) {
            Write-Host "[SUCESSO] APK instalado!" -ForegroundColor Green
        } else {
            Write-Host "[ERRO] Falha ao instalar APK." -ForegroundColor Red
        }
    }
}
