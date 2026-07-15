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
$apkFiles = @(Get-ChildItem -Path $apkDir -Filter "*.apk" -ErrorAction SilentlyContinue)

# Splits por ABI: um APK por ABI, com nome estavel em dist/ por sufixo.
# arm64-v8a cobre praticamente todo celular/tablet atual; armeabi-v7a cobre
# Smart TVs e TV boxes baratas (32-bit), que sao o publico com pouco storage.
$abiMap = [ordered]@{ "arm64-v8a" = "arm64"; "armeabi-v7a" = "armv7" }
$deviceLabel = [ordered]@{ "arm64-v8a" = "Celular / tablet"; "armeabi-v7a" = "Smart TV / TV box antiga" }
$distApks = @()

if ($apkFiles.Count -gt 0) {
    Write-Host "`n[SUCESSO] APKs gerados:" -ForegroundColor Green
    $distDir = if ($OutputDir -ne "") { $OutputDir } else { Join-Path $repoRoot "dist" }
    if (-not (Test-Path $distDir)) { New-Item -ItemType Directory -Path $distDir | Out-Null }
    foreach ($abi in $abiMap.Keys) {
        $apkFile = $apkFiles | Where-Object { $_.Name -match [regex]::Escape($abi) } | Select-Object -First 1
        if (-not $apkFile) {
            Write-Host "  [AVISO] Split $abi nao encontrado em $apkDir" -ForegroundColor Yellow
            continue
        }
        Write-Host "  $($apkFile.FullName)" -ForegroundColor White
        Write-Host "  Tamanho: $([math]::Round($apkFile.Length / 1MB, 2)) MB" -ForegroundColor White
        $destApk = Join-Path $distDir "retro-game-system-$($abiMap[$abi]).apk"
        Copy-Item -Path $apkFile.FullName -Destination $destApk -Force
        Write-Host "  Copiado para: $destApk" -ForegroundColor Cyan
        $distApks += $destApk
    }
    Write-Host "`n========================================================" -ForegroundColor Cyan
    Write-Host "Resumo" -ForegroundColor Cyan
    Write-Host "========================================================" -ForegroundColor Cyan
    foreach ($abi in $abiMap.Keys) {
        $destApk = $distApks | Where-Object { $_ -match "-$($abiMap[$abi])\.apk$" } | Select-Object -First 1
        if ($destApk) {
            $sizeMb = [math]::Round((Get-Item $destApk).Length / 1MB, 1)
            Write-Host "  $($deviceLabel[$abi]): $destApk ($sizeMb MB)" -ForegroundColor White
        }
    }
} else {
    Write-Host "`n[AVISO] APK nao encontrado em $apkDir" -ForegroundColor Yellow
}

if ($Install) {
    $adbCmd = Get-Command adb -ErrorAction SilentlyContinue
    if (-not $adbCmd) {
        Write-Host "[AVISO] ADB nao encontrado no PATH. Nao foi possivel instalar." -ForegroundColor Yellow
    } elseif ($distApks.Count -gt 0) {
        # Escolhe o APK do split compativel com o ABI do device conectado.
        $deviceAbi = (& adb shell getprop ro.product.cpu.abi).Trim()
        $suffix = if ($abiMap.Contains($deviceAbi)) { $abiMap[$deviceAbi] } else { "armv7" }
        $destApk = $distApks | Where-Object { $_ -match "-$suffix\.apk$" } | Select-Object -First 1
        if (-not $destApk) {
            Write-Host "[AVISO] APK do split $suffix nao encontrado em dist. Nao foi possivel instalar." -ForegroundColor Yellow
        } else {
            Write-Host "`nInstalando APK no dispositivo ($deviceAbi): $destApk..." -ForegroundColor Yellow
            & adb install -r $destApk
            if ($LASTEXITCODE -eq 0) {
                Write-Host "[SUCESSO] APK instalado!" -ForegroundColor Green
            } else {
                Write-Host "[ERRO] Falha ao instalar APK." -ForegroundColor Red
            }
        }
    }
}
