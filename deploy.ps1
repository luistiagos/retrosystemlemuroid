param(
    [switch]$LaunchAfterInstall = $false
)

$ErrorActionPreference = "Stop"

Write-Host "========================================================" -ForegroundColor Cyan
Write-Host "Iniciando build e deploy no dispositivo conectado..." -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location -Path $repoRoot

$gradleWrapper = Join-Path $repoRoot "gradlew.bat"

if (-not (Test-Path $gradleWrapper)) {
    Write-Error "gradlew.bat nao encontrado em $repoRoot"
    exit 1
}

# Executa a task do gradle para fazer o build e instalar o app (variante Free Bundle Debug)
Write-Host "Executando: .\gradlew.bat installFreeBundleDebug" -ForegroundColor Yellow
& $gradleWrapper installFreeBundleDebug

if ($LASTEXITCODE -ne 0) {
    Write-Host "`n[ERRO] Falha durante o build ou instalacao. Verifique se o dispositivo esta conectado e com a depuracao USB ativada." -ForegroundColor Red
    exit $LASTEXITCODE
}

Write-Host "`n[SUCESSO] App instalado com sucesso no dispositivo!" -ForegroundColor Green

if (-not $LaunchAfterInstall) {
    $prompt = Read-Host "Deseja iniciar o aplicativo agora? (s/n)"
    if ($prompt -match "^s") {
        $LaunchAfterInstall = $true
    }
}

if ($LaunchAfterInstall) {
    Write-Host "Iniciando o Lemuroid..." -ForegroundColor Yellow
    # Tenta usar o ADB do PATH
    $adbInPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbInPath) {
        & adb shell monkey -p com.swordfish.lemuroid.debug -c android.intent.category.LAUNCHER 1 | Out-Null
        if ($LASTEXITCODE -eq 0) {
            Write-Host "Aplicativo iniciado!" -ForegroundColor Green
        } else {
            Write-Host "Falha ao iniciar o aplicativo." -ForegroundColor Red
        }
    } else {
        Write-Host "[AVISO] ADB nao encontrado no PATH para iniciar o aplicativo automaticamente." -ForegroundColor Yellow
    }
}
