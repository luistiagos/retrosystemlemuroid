param(
    [string]$Task = ":lemuroid-app:assembleFreeBundleRelease",
    [switch]$Debug,                # alias rápido para assembleFreeBundleDebug
    [switch]$SkipPrebuiltCheck,    # pula validação dos pré-requisitos do prebuilt DB
    [switch]$Install               # instala via ADB no fim
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradleWrapper = Join-Path $repoRoot "gradlew.bat"
$apkOutputDir = Join-Path $repoRoot "lemuroid-app\build\outputs\apk"
$distDir = Join-Path $repoRoot "dist"

# Caminhos consumidos pela Gradle task `generatePrebuiltDb` (buildSrc/PrebuiltDbGenerator.kt).
# Se algum faltar, o build falha tarde dentro do Gradle com erro pouco amigável — validamos cedo.
$catalogManifest = Join-Path $repoRoot "lemuroid-app\src\main\assets\catalog_manifest.txt"
$roomSchemaJson  = Join-Path $repoRoot "retrograde-app-shared\schemas\com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase\23.json"

if ($Debug) {
    $Task = ":lemuroid-app:assembleFreeBundleDebug"
}

if (-not (Test-Path $gradleWrapper)) {
    throw "Gradle wrapper nao encontrado em $gradleWrapper"
}

# Redireciona o cache do Gradle para E: pois C: pode estar sem espaco livre.
# GRADLE_USER_HOME afeta cache de builds, daemons e dependencias baixadas.
$env:GRADLE_USER_HOME = "E:\.gradle"
New-Item -ItemType Directory -Path $env:GRADLE_USER_HOME -Force | Out-Null

New-Item -ItemType Directory -Path $distDir -Force | Out-Null

# ── Pré-requisitos do prebuilt DB ───────────────────────────────────────────
# A task generatePrebuiltDb (registrada em lemuroid-app/build.gradle.kts) gera
# `assets/retrograde-prebuilt.db` durante o build a partir do manifest + schema.
# Sem esses arquivos a task falha; checar antes economiza ~1 minuto de build.
if (-not $SkipPrebuiltCheck) {
    Write-Host "Validando pre-requisitos do prebuilt DB..."

    if (-not (Test-Path $catalogManifest)) {
        throw "catalog_manifest.txt nao encontrado em $catalogManifest. Rode o script Python em E:\fetchimagers\cleantitles\clean_titles.py para gerar."
    }
    $manifestSizeMb = [math]::Round((Get-Item $catalogManifest).Length / 1MB, 2)
    $manifestLines = (Get-Content $catalogManifest | Measure-Object -Line).Lines
    Write-Host "  catalog_manifest.txt: $manifestLines linhas ($manifestSizeMb MB)"

    if (-not (Test-Path $roomSchemaJson)) {
        Write-Warning "schemas/23.json ausente em $roomSchemaJson"
        Write-Warning "  isso normalmente significa que o kapt ainda nao gerou. Rode um build basico primeiro:"
        Write-Warning "    .\gradlew.bat :retrograde-app-shared:kaptDebugKotlin"
        throw "Pre-requisito faltando: schemas/23.json"
    }
    $schemaIdentityHash = (Select-String -Path $roomSchemaJson -Pattern '"identityHash":\s*"([a-f0-9]+)"' |
        Select-Object -First 1).Matches.Groups[1].Value
    Write-Host "  schemas/23.json identityHash: $schemaIdentityHash"
}

Write-Host ""
Write-Host "Executando $Task..."
& $gradleWrapper $Task

if ($LASTEXITCODE -ne 0) {
    throw "Build falhou com codigo $LASTEXITCODE"
}

# ── Localiza o APK gerado ───────────────────────────────────────────────────
$apkFile = Get-ChildItem -Path $apkOutputDir -Recurse -Filter "*.apk" |
    Sort-Object LastWriteTimeUtc -Descending |
    Select-Object -First 1

if (-not $apkFile) {
    throw "Nenhum APK foi encontrado em $apkOutputDir"
}

$apkSizeMb = [math]::Round($apkFile.Length / 1MB, 2)
Write-Host ""
Write-Host "APK gerado: $($apkFile.FullName) ($apkSizeMb MB)"

# ── Confirma que o prebuilt DB foi empacotado dentro do APK ─────────────────
# Isso protege contra dependsOn mal configurada na task generatePrebuiltDb —
# se o asset nao estiver no APK, a "tela preparando ambiente" volta no primeiro boot.
if (-not $SkipPrebuiltCheck) {
    # PowerShell 5.1 nao carrega System.IO.Compression.FileSystem por padrao.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $apkAsZip = [System.IO.Compression.ZipFile]::OpenRead($apkFile.FullName)
    try {
        $prebuiltEntry = $apkAsZip.Entries | Where-Object { $_.FullName -eq "assets/retrograde-prebuilt.db" } | Select-Object -First 1
        if ($prebuiltEntry) {
            $prebuiltMb = [math]::Round($prebuiltEntry.Length / 1MB, 2)
            $prebuiltCompressedMb = [math]::Round($prebuiltEntry.CompressedLength / 1MB, 2)
            Write-Host "  assets/retrograde-prebuilt.db presente: $prebuiltMb MB ($prebuiltCompressedMb MB comprimido)"
        } else {
            Write-Warning "  assets/retrograde-prebuilt.db AUSENTE no APK!"
            Write-Warning "  isso indica problema no wire-up de generatePrebuiltDb -> mergeAssets em lemuroid-app/build.gradle.kts"
        }
    } finally {
        $apkAsZip.Dispose()
    }
}

# ── Copia para dist/ ────────────────────────────────────────────────────────
$desiredApkName = "retro-game-system.apk"
$distApkPath = Join-Path $distDir $desiredApkName
Copy-Item -Path $apkFile.FullName -Destination $distApkPath -Force

Write-Host ""
Write-Host "APK final: $distApkPath"

# ── Instalacao opcional via ADB ─────────────────────────────────────────────
if ($Install) {
    $adbCandidates = @(
        "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe",
        "$env:USERPROFILE\AppData\Local\Android\Sdk\platform-tools\adb.exe",
        "C:\Android\Sdk\platform-tools\adb.exe"
    )
    $adb = $adbCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $adb) {
        Write-Warning "ADB nao encontrado. Pulando instalacao."
    } else {
        Write-Host ""
        Write-Host "Instalando via ADB..."
        # Reinstall preservando dados (sem uninstall). Use -r para sobrescrever.
        & $adb install -r $distApkPath
        if ($LASTEXITCODE -ne 0) {
            Write-Warning "Falha ao instalar. Talvez precise: adb uninstall com.swordfish.lemuroid (ou .debug)"
        }
    }
}
