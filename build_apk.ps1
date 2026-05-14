param(
    [string]$Task = ":lemuroid-app:assembleFreeBundleRelease"
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradleWrapper = Join-Path $repoRoot "gradlew.bat"
$apkOutputDir = Join-Path $repoRoot "lemuroid-app\build\outputs\apk"
$distDir = Join-Path $repoRoot "dist"

if (-not (Test-Path $gradleWrapper)) {
    throw "Gradle wrapper nao encontrado em $gradleWrapper"
}

# Redireciona o cache do Gradle para E: pois C: pode estar sem espaco livre.
# GRADLE_USER_HOME afeta cache de builds, daemons e dependencias baixadas.
$env:GRADLE_USER_HOME = "E:\.gradle"
New-Item -ItemType Directory -Path $env:GRADLE_USER_HOME -Force | Out-Null

New-Item -ItemType Directory -Path $distDir -Force | Out-Null

Write-Host "Executando $Task..."
& $gradleWrapper $Task

if ($LASTEXITCODE -ne 0) {
    throw "Build falhou com codigo $LASTEXITCODE"
}

$apkFile = Get-ChildItem -Path $apkOutputDir -Recurse -Filter "*.apk" |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1

if (-not $apkFile) {
    throw "Nenhum APK foi encontrado em $apkOutputDir"
}

$desiredApkName = "retro-game-system.apk"
$distApkPath = Join-Path $distDir $desiredApkName
Copy-Item -Path $apkFile.FullName -Destination $distApkPath -Force

Write-Host "APK gerado em: $distApkPath"
