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

$distApkPath = Join-Path $distDir $apkFile.Name
Copy-Item -Path $apkFile.FullName -Destination $distApkPath -Force

Write-Host "APK gerado em: $distApkPath"