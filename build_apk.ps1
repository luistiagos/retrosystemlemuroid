param(
    [string]$Task = ":lemuroid-app:assembleFreeBundleRelease",
    [switch]$Debug,                # alias rápido para assembleFreeBundleDebug
    [switch]$SkipPrebuiltCheck,    # pula validação dos pré-requisitos do prebuilt DB
    [switch]$Install,              # instala via ADB no fim
    [string]$CatalogChannel = "default",
    [string]$CatalogManifest = "",
    [string]$AppUpdateChannel = "",
    [string]$AppUpdateEndpoint = "",
    [string]$CatalogApplicationIdSuffix = ""
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
# gradlew.bat usa o CWD como raiz do build — fixa no repo para o script
# funcionar quando invocado de qualquer diretorio.
Set-Location -Path $repoRoot
$gradleWrapper = Join-Path $repoRoot "gradlew.bat"
$apkOutputDir = Join-Path $repoRoot "lemuroid-app\build\outputs\apk"
$distDir = Join-Path $repoRoot "dist"

function Resolve-JavaHome {
    # gradlew.bat needs JAVA_HOME (or java in PATH) to bootstrap *before* it reads
    # gradle.properties, so org.gradle.java.home alone is not enough. Resolve it here.
    # 1) Already set in environment
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME) -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }
    # 2) org.gradle.java.home in gradle.properties
    $gradlePropsPath = Join-Path $repoRoot "gradle.properties"
    if (Test-Path $gradlePropsPath) {
        foreach ($line in Get-Content $gradlePropsPath) {
            if ($line -match "^\s*org\.gradle\.java\.home\s*=\s*(.+)$") {
                # Java .properties escapes backslashes and ':'; unescape the common cases.
                $jh = $matches[1].Trim() -replace '\\\\', '\' -replace '\\:', ':'
                if (Test-Path (Join-Path $jh "bin\java.exe")) { return $jh }
            }
        }
    }
    # 3) Android Studio bundled JBR (common locations)
    foreach ($c in @("C:\Android\Android Studio\jbr", "C:\Program Files\Android\Android Studio\jbr")) {
        if (Test-Path (Join-Path $c "bin\java.exe")) { return $c }
    }
    # 4) java.exe already in PATH
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCmd) { return Split-Path -Parent (Split-Path -Parent $javaCmd.Source) }

    throw "Java nao encontrado. Defina JAVA_HOME ou configure org.gradle.java.home em gradle.properties."
}

# Caminhos consumidos pela Gradle task `generatePrebuiltDb` (buildSrc/PrebuiltDbGenerator.kt).
# Se algum faltar, o build falha tarde dentro do Gradle com erro pouco amigável — validamos cedo.
$catalogManifest = if ([string]::IsNullOrWhiteSpace($CatalogManifest)) {
    Join-Path $repoRoot "lemuroid-app\src\main\assets\catalog_manifest.txt"
} elseif ([System.IO.Path]::IsPathRooted($CatalogManifest)) {
    $CatalogManifest
} else {
    Join-Path $repoRoot $CatalogManifest
}
$roomSchemaJson  = Join-Path $repoRoot "retrograde-app-shared\schemas\com.swordfish.lemuroid.lib.library.db.RetrogradeDatabase\24.json"

if ([string]::IsNullOrWhiteSpace($AppUpdateChannel)) {
    $AppUpdateChannel = $CatalogChannel
}

if ($Debug) {
    $Task = ":lemuroid-app:assembleFreeBundleDebug"
}

if (-not (Test-Path $gradleWrapper)) {
    throw "Gradle wrapper nao encontrado em $gradleWrapper"
}

New-Item -ItemType Directory -Path $distDir -Force | Out-Null

# Temp do Gradle no E:: o C: vive ~100% cheio e o Gradle escreve temp grande
# (AAPT2/resource link/packaging) no java.io.tmpdir, que gradle.properties aponta
# para E:/gradle_tmp. Garante que o diretorio exista (senao o Gradle falha cedo).
New-Item -ItemType Directory -Path "E:\gradle_tmp" -Force | Out-Null

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
        Write-Warning "schemas/24.json ausente em $roomSchemaJson"
        Write-Warning "  isso normalmente significa que o kapt ainda nao gerou. Rode um build basico primeiro:"
        Write-Warning "    .\gradlew.bat :retrograde-app-shared:kaptDebugKotlin"
        throw "Pre-requisito faltando: schemas/24.json"
    }
    $schemaIdentityHash = (Select-String -Path $roomSchemaJson -Pattern '"identityHash":\s*"([a-f0-9]+)"' |
        Select-Object -First 1).Matches.Groups[1].Value
    Write-Host "  schemas/24.json identityHash: $schemaIdentityHash"
}

Write-Host ""
# Ensure JAVA_HOME is set so gradlew.bat can bootstrap before reading gradle.properties.
$resolvedJavaHome = Resolve-JavaHome
if ($env:JAVA_HOME -ne $resolvedJavaHome) {
    Write-Host "Definindo JAVA_HOME: $resolvedJavaHome"
    $env:JAVA_HOME = $resolvedJavaHome
}

Write-Host "Executando $Task..."
$gradleArgs = @(
    $Task,
    "-PcatalogChannel=$CatalogChannel",
    "-PappUpdateChannel=$AppUpdateChannel",
    "-PcatalogManifest=$catalogManifest"
)
if (-not [string]::IsNullOrWhiteSpace($AppUpdateEndpoint)) {
    $gradleArgs += "-PappUpdateEndpoint=$AppUpdateEndpoint"
}
if (-not [string]::IsNullOrWhiteSpace($CatalogApplicationIdSuffix)) {
    $gradleArgs += "-PcatalogApplicationIdSuffix=$CatalogApplicationIdSuffix"
}

Write-Host "  catalogChannel: $CatalogChannel"
Write-Host "  appUpdateChannel: $AppUpdateChannel"
Write-Host "  catalogManifest: $catalogManifest"
if (-not [string]::IsNullOrWhiteSpace($AppUpdateEndpoint)) {
    Write-Host "  appUpdateEndpoint: $AppUpdateEndpoint"
}
if (-not [string]::IsNullOrWhiteSpace($CatalogApplicationIdSuffix)) {
    Write-Host "  applicationId suffix: $CatalogApplicationIdSuffix"
}
& $gradleWrapper @gradleArgs

if ($LASTEXITCODE -ne 0) {
    throw "Build falhou com codigo $LASTEXITCODE"
}

# ── Localiza os APKs gerados (splits por ABI) ───────────────────────────────
# Busca no diretorio da variante buildada (nunca "o APK mais recente" recursivo:
# foi assim que uma build debug ja acabou distribuida em dist\).
$isRelease = $Task -match '(?i)release'
$variantDir = Join-Path $apkOutputDir $(if ($isRelease) { "freeBundle\release" } else { "freeBundle\debug" })
$apkFiles = @(Get-ChildItem -Path $variantDir -Filter "*.apk" -ErrorAction SilentlyContinue)

if ($apkFiles.Count -eq 0) {
    throw "Nenhum APK foi encontrado em $variantDir"
}

Write-Host ""
foreach ($apk in $apkFiles) {
    $apkSizeMb = [math]::Round($apk.Length / 1MB, 2)
    Write-Host "APK gerado: $($apk.FullName) ($apkSizeMb MB)"
}

# ── Confirma que o prebuilt DB foi empacotado dentro de cada APK ────────────
# Isso protege contra dependsOn mal configurada na task generatePrebuiltDb —
# se o asset nao estiver no APK, a "tela preparando ambiente" volta no primeiro boot.
if (-not $SkipPrebuiltCheck) {
    # PowerShell 5.1 nao carrega System.IO.Compression.FileSystem por padrao.
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    foreach ($apk in $apkFiles) {
        $apkAsZip = [System.IO.Compression.ZipFile]::OpenRead($apk.FullName)
        try {
            $prebuiltEntry = $apkAsZip.Entries | Where-Object { $_.FullName -eq "assets/retrograde-prebuilt.db" } | Select-Object -First 1
            if ($prebuiltEntry) {
                $prebuiltMb = [math]::Round($prebuiltEntry.Length / 1MB, 2)
                $prebuiltCompressedMb = [math]::Round($prebuiltEntry.CompressedLength / 1MB, 2)
                Write-Host "  $($apk.Name): assets/retrograde-prebuilt.db presente: $prebuiltMb MB ($prebuiltCompressedMb MB comprimido)"
            } else {
                Write-Warning "  $($apk.Name): assets/retrograde-prebuilt.db AUSENTE no APK!"
                Write-Warning "  isso indica problema no wire-up de generatePrebuiltDb -> mergeAssets em lemuroid-app/build.gradle.kts"
            }
        } finally {
            $apkAsZip.Dispose()
        }
    }
}

# Mapa ABI -> sufixo amigavel dos artefatos em dist\.
$abiMap = [ordered]@{ "arm64-v8a" = "arm64"; "armeabi-v7a" = "armv7" }

# ── Copia para dist/ — SOMENTE builds release ───────────────────────────────
# dist\ e o diretorio de distribuicao; build debug nunca pode sobrescrever os
# artefatos (ja aconteceu: dist\retro-game-system.apk ficou com a build debug).
if (-not $isRelease) {
    Write-Warning "Build DEBUG: artefatos NAO copiados para dist\ (dist\ e reservado a builds release)."
} else {
    $safeChannelName = $CatalogChannel -replace '[^A-Za-z0-9_-]', '_'
    Write-Host ""
    foreach ($abi in $abiMap.Keys) {
        $apk = $apkFiles | Where-Object { $_.Name -match [regex]::Escape($abi) } | Select-Object -First 1
        if (-not $apk) {
            Write-Warning "Split $abi nao encontrado em $variantDir"
            continue
        }
        $desiredApkName = if ($safeChannelName -eq "default") {
            "retro-game-system-$($abiMap[$abi]).apk"
        } else {
            "retro-game-system-$safeChannelName-$($abiMap[$abi]).apk"
        }
        $distApkPath = Join-Path $distDir $desiredApkName
        Copy-Item -Path $apk.FullName -Destination $distApkPath -Force
        Write-Host "APK final: $distApkPath"
    }
}

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
        # Com splits por ABI, escolhe o APK compativel com o device conectado.
        # Instala direto do diretorio da variante (funciona para debug tambem).
        $deviceAbi = (& $adb shell getprop ro.product.cpu.abi).Trim()
        Write-Host ""
        Write-Host "ABI do device: $deviceAbi"
        $targetAbi = if ($abiMap.Contains($deviceAbi)) { $deviceAbi } else { "armeabi-v7a" }
        $apkToInstall = $apkFiles | Where-Object { $_.Name -match [regex]::Escape($targetAbi) } | Select-Object -First 1
        if (-not $apkToInstall) {
            Write-Warning "Nenhum APK do split $targetAbi encontrado. Pulando instalacao."
        } else {
            Write-Host "Instalando via ADB: $($apkToInstall.Name)..."
            # Reinstall preservando dados (sem uninstall). Use -r para sobrescrever.
            & $adb install -r $apkToInstall.FullName
            if ($LASTEXITCODE -ne 0) {
                Write-Warning "Falha ao instalar. Talvez seja necessario remover uma instalacao anterior antes de tentar novamente."
            }
        }
    }
}
