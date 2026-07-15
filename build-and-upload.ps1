<#
.SYNOPSIS
    Build release (celular + smart TV) + upload para HuggingFace (versionado) e R2 (distribuicao).

.DESCRIPTION
    Passo a passo completo:

    1. BUILD
       - Executa .\release.ps1 (assembleFreeBundleRelease com splits por ABI)
       - Gera dist\retro-game-system-arm64.apk (celular) e dist\retro-game-system-armv7.apk (smart TV)

    2. UPLOAD PARA HUGGINGFACE (historico versionado)
       - Repo: luisluis123/versions (dataset)
       - Pasta: RetroGameSystem/
       - Arquivos: retro-game-system-<versionName>-arm64.apk / -armv7.apk
       - Token: lido do build.properties (HF_TOKEN) ou variavel de ambiente HF_TOKEN

    3. UPLOAD PARA R2 (distribuicao - sempre sobrescreve, sem numero de versao)
       - Mesmo bucket "versions" usado pelo GODSend, pasta propria RetroGameSystem/
       - Envia retro-game-system-arm64.apk / -armv7.apk via rclone
       - Credenciais: build.properties/r2-config.json locais, com fallback para
         E:\projects\GODSend\r2-config.json (mesma conta R2)

.PARAMETER SkipBuild
    Pula o build (usa APKs existentes em dist\).

.PARAMETER SkipHF
    Pula upload para HuggingFace.

.PARAMETER SkipR2
    Pula upload para R2.

.EXAMPLE
    .\build-and-upload.ps1
    Executa tudo: build + HF + R2.

.EXAMPLE
    .\build-and-upload.ps1 -SkipBuild
    Usa APKs existentes em dist\ e faz upload para ambos.

.EXAMPLE
    .\build-and-upload.ps1 -SkipR2
    Build + upload apenas para HuggingFace.

.NOTES
    Pre-requisitos:
    - Mesmos do release.ps1 (gradlew, release.jks) quando nao usar -SkipBuild
    - rclone (winget install Rclone.Rclone) - necessario so para R2
    - hf CLI / huggingface_hub (pip install huggingface_hub) - necessario so para HF
    - Arquivo build.properties na raiz (veja build.properties.example) com HF_TOKEN
      e, opcionalmente, R2_*. Sem R2_* local, cai no r2-config.json do GODSend.
#>

[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$SkipHF,
    [switch]$SkipR2
)

$ErrorActionPreference = "Stop"

# ─── CONFIG ──────────────────────────────────────────
$PROJECT_ROOT = Split-Path -Parent $MyInvocation.MyCommand.Path
$DIST_DIR = Join-Path $PROJECT_ROOT "dist"
$ENV_FILE = Join-Path $PROJECT_ROOT "build.properties"
$GRADLE_FILE = Join-Path $PROJECT_ROOT "lemuroid-app\build.gradle.kts"

# Load build.properties
if (Test-Path -LiteralPath $ENV_FILE) {
    Get-Content -LiteralPath $ENV_FILE -Encoding UTF8 | ForEach-Object {
        if ($_ -match '^\s*([^#=]+)=(.*)\s*$') {
            $k = $matches[1].Trim()
            $v = $matches[2].Trim().Trim('"', "'")
            Set-Variable -Name $k -Value $v -Scope Script
        }
    }
}

# Versao: lida do build.gradle.kts, fonte de verdade do versionamento do app.
$gradleContent = Get-Content -LiteralPath $GRADLE_FILE -Raw
if ($gradleContent -notmatch 'versionCode\s*=\s*(\d+)') {
    throw "versionCode nao encontrado em $GRADLE_FILE"
}
$VERSION_CODE = $matches[1]
if ($gradleContent -notmatch 'versionName\s*=\s*"([^"]+)"') {
    throw "versionName nao encontrado em $GRADLE_FILE"
}
$VERSION_NAME = $matches[1]

# HuggingFace
$HF_REPO = if ($env:HF_REPO) { $env:HF_REPO } elseif ($Script:HF_REPO) { $Script:HF_REPO } else { "luisluis123/versions" }
$HF_REPO_TYPE = "dataset"
$HF_FOLDER = "RetroGameSystem"
$HF_TOKEN = if ($env:HF_TOKEN) { $env:HF_TOKEN } elseif ($Script:HF_TOKEN) { $Script:HF_TOKEN } else { "" }

# R2 (mesmo bucket "versions" do GODSend, pasta propria)
$R2_FOLDER = "RetroGameSystem"
$LOCAL_R2_CONFIG = Join-Path $PROJECT_ROOT "r2-config.json"
$GODSEND_R2_CONFIG = "E:\projects\GODSend\r2-config.json"

# ABIs distribuidas (celular = arm64-v8a, smart TV / TV box antiga = armeabi-v7a)
$AbiMap = [ordered]@{ "arm64-v8a" = "arm64"; "armeabi-v7a" = "armv7" }
$DeviceLabel = [ordered]@{ "arm64-v8a" = "Celular / tablet"; "armeabi-v7a" = "Smart TV / TV box antiga" }

# ─── HELPERS ─────────────────────────────────────────
function Print-Step {
    param([string]$Message, [string]$Color = "Cyan")
    Write-Host ""
    Write-Host "========================================" -ForegroundColor $Color
    Write-Host "  $Message" -ForegroundColor $Color
    Write-Host "========================================" -ForegroundColor $Color
}

function Find-Rclone {
    $cmd = Get-Command rclone -ErrorAction SilentlyContinue
    if ($cmd) { return $cmd.Source }

    $candidate = Get-ChildItem -Path "$env:LOCALAPPDATA\Microsoft\WinGet\Packages" -Filter "rclone.exe" -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1 -ExpandProperty FullName
    if ($candidate) { return $candidate }

    throw "rclone.exe not found. Install with: winget install Rclone.Rclone (then restart shell)."
}

# ─── STEP 1: BUILD ──────────────────────────────────
if (-not $SkipBuild) {
    Print-Step "PASSO 1/3: Build release v$VERSION_NAME (celular + smart TV)"
    & (Join-Path $PROJECT_ROOT "release.ps1")
    if ($LASTEXITCODE -ne 0 -and $null -ne $LASTEXITCODE) {
        throw "release.ps1 falhou com exit code $LASTEXITCODE"
    }
} else {
    Print-Step "PASSO 1/3: Build (SKIPPED - usando APKs existentes em dist\)"
}

$DistApks = @{}
foreach ($abi in $AbiMap.Keys) {
    $path = Join-Path $DIST_DIR "retro-game-system-$($AbiMap[$abi]).apk"
    if (-not (Test-Path -LiteralPath $path)) {
        throw "APK nao encontrado: $path`nExecute sem -SkipBuild, ou rode .\release.ps1 primeiro."
    }
    $DistApks[$abi] = $path
}

# ─── STEP 2: HUGGINGFACE UPLOAD (versionado) ───────
if (-not $SkipHF) {
    Print-Step "PASSO 2/3: Upload para HuggingFace (versionado)"

    if (-not $HF_TOKEN) {
        throw "HF_TOKEN nao definido. Crie build.properties na raiz (veja build.properties.example) ou defina a variavel de ambiente HF_TOKEN."
    }

    Write-Host "Repositorio: $HF_REPO ($HF_REPO_TYPE)" -ForegroundColor Yellow
    Write-Host "Pasta remota: $HF_FOLDER/" -ForegroundColor Yellow
    Write-Host ""

    $env:PYTHONIOENCODING = "utf-8"
    $hfUrls = @{}

    foreach ($abi in $AbiMap.Keys) {
        $suffix = $AbiMap[$abi]
        $versionedName = "retro-game-system-$VERSION_NAME-$suffix.apk"
        $hfRemotePath = "$HF_FOLDER/$versionedName"

        Write-Host "Enviando $($DeviceLabel[$abi]): $versionedName..." -ForegroundColor Yellow

        $savedEAP = $ErrorActionPreference
        $ErrorActionPreference = "Continue"
        try {
            hf upload $HF_REPO "$($DistApks[$abi])" $hfRemotePath `
                --repo-type $HF_REPO_TYPE --token $HF_TOKEN --commit-message "v$VERSION_NAME ($suffix)" 2>&1
        } finally {
            $ErrorActionPreference = $savedEAP
        }

        if ($LASTEXITCODE -ne 0) {
            throw "Upload para HuggingFace falhou ($abi) com exit code $LASTEXITCODE"
        }

        $hfUrls[$abi] = "https://huggingface.co/datasets/$HF_REPO/blob/main/$hfRemotePath"
        Write-Host "  OK: $($hfUrls[$abi])" -ForegroundColor Green
    }
} else {
    Print-Step "PASSO 2/3: Upload para HuggingFace (SKIPPED)"
}

# ─── STEP 3: R2 UPLOAD (distribuicao, sem versao) ──
if (-not $SkipR2) {
    Print-Step "PASSO 3/3: Upload para R2 (distribuicao)"

    if ($Script:R2_ACCESS_KEY_ID -and $Script:R2_SECRET_ACCESS_KEY -and $Script:R2_ENDPOINT -and $Script:R2_BUCKET) {
        $cfg = [PSCustomObject]@{
            accessKeyId     = $Script:R2_ACCESS_KEY_ID
            secretAccessKey = $Script:R2_SECRET_ACCESS_KEY
            endpoint        = $Script:R2_ENDPOINT
            bucket          = $Script:R2_BUCKET
            publicBaseUrl   = if ($Script:R2_PUBLIC_URL) { $Script:R2_PUBLIC_URL } else { "" }
        }
    } elseif (Test-Path -LiteralPath $LOCAL_R2_CONFIG) {
        $cfg = Get-Content -LiteralPath $LOCAL_R2_CONFIG -Raw -Encoding UTF8 | ConvertFrom-Json
    } elseif (Test-Path -LiteralPath $GODSEND_R2_CONFIG) {
        Write-Host "Sem R2_* local: usando credenciais do GODSend (mesmo bucket 'versions')." -ForegroundColor Yellow
        Write-Host "  $GODSEND_R2_CONFIG" -ForegroundColor Yellow
        $cfg = Get-Content -LiteralPath $GODSEND_R2_CONFIG -Raw -Encoding UTF8 | ConvertFrom-Json
    } else {
        throw "Credenciais R2 nao encontradas. Defina R2_* no build.properties, crie r2-config.json (veja r2-config.example.json), ou garanta que $GODSEND_R2_CONFIG existe."
    }

    foreach ($field in @('accessKeyId', 'secretAccessKey', 'endpoint', 'bucket')) {
        if (-not $cfg.$field) {
            throw "Config R2 faltando campo obrigatorio: $field"
        }
    }

    $rclone = Find-Rclone
    $dest = ":s3:$($cfg.bucket)/$R2_FOLDER"
    $s3Flags = @(
        "--s3-provider=Cloudflare",
        "--s3-access-key-id=$($cfg.accessKeyId)",
        "--s3-secret-access-key=$($cfg.secretAccessKey)",
        "--s3-endpoint=$($cfg.endpoint)",
        "--s3-no-check-bucket"
    )

    foreach ($abi in $AbiMap.Keys) {
        $localFile = $DistApks[$abi]
        Write-Host "Enviando $(Split-Path $localFile -Leaf) -> bucket '$($cfg.bucket)/$R2_FOLDER'..." -ForegroundColor Yellow
        & $rclone copy $localFile $dest @s3Flags --progress
        if ($LASTEXITCODE -ne 0) {
            throw "rclone copy falhou ($abi) com exit code $LASTEXITCODE"
        }
    }

    # Verification
    Write-Host ""
    Write-Host "Verificando transferencia..." -ForegroundColor Yellow

    $remoteEntries = & $rclone lsjson $dest @s3Flags | ConvertFrom-Json
    $remoteByName = @{}
    foreach ($e in $remoteEntries) {
        if (-not $e.IsDir) { $remoteByName[$e.Name] = $e.Size }
    }

    foreach ($abi in $AbiMap.Keys) {
        $fileName = "retro-game-system-$($AbiMap[$abi]).apk"
        $localSize = (Get-Item -LiteralPath $DistApks[$abi]).Length
        if (-not $remoteByName.ContainsKey($fileName)) {
            throw "VERIFICACAO FALHOU: $fileName nao encontrado no remoto."
        }
        $remoteSize = $remoteByName[$fileName]
        if ($remoteSize -ne $localSize) {
            throw "VERIFICACAO FALHOU: tamanho diferente para $fileName (local: $localSize bytes, remoto: $remoteSize bytes)"
        }
        Write-Host "  OK: $fileName ($localSize bytes)" -ForegroundColor Green
    }
    Write-Host "Verificacao passou." -ForegroundColor Green

    Write-Host ""
    Write-Host "Upload para R2 concluido!" -ForegroundColor Green
} else {
    Print-Step "PASSO 3/3: Upload para R2 (SKIPPED)"
}

# ─── SUMMARY ────────────────────────────────────────
Print-Step "RESUMO" "Green"

Write-Host "Versao: $VERSION_NAME (code $VERSION_CODE)" -ForegroundColor Green
Write-Host ""

if (-not $SkipHF) {
    Write-Host "HuggingFace (versionado):" -ForegroundColor Cyan
    foreach ($abi in $AbiMap.Keys) {
        Write-Host "  $($DeviceLabel[$abi]): $($hfUrls[$abi])" -ForegroundColor Cyan
    }
}

$r2PublicUrls = @{}
if (-not $SkipR2) {
    Write-Host ""
    Write-Host "R2 (distribuicao):" -ForegroundColor Cyan
    if ($cfg.publicBaseUrl) {
        $base = $cfg.publicBaseUrl.TrimEnd('/')
        foreach ($abi in $AbiMap.Keys) {
            $r2PublicUrls[$abi] = "$base/$R2_FOLDER/retro-game-system-$($AbiMap[$abi]).apk"
            Write-Host "  $($DeviceLabel[$abi]): $($r2PublicUrls[$abi])" -ForegroundColor Cyan
        }
    } else {
        Write-Host "  (defina publicBaseUrl / R2_PUBLIC_URL para ver as URLs)" -ForegroundColor Yellow
    }
}

# ─── JSON app_version ───────────────────────────────
# Pronto para colar na rota /app_version do pythonanywhere. Usa as URLs
# publicas do R2 (nomes estaveis, sem versao no path). "apkUrl" legado
# aponta pro arm64 - maioria dos devices em campo.
Write-Host ""
Write-Host "JSON para /app_version:" -ForegroundColor Cyan
if ($r2PublicUrls.ContainsKey("arm64-v8a") -and $r2PublicUrls.ContainsKey("armeabi-v7a")) {
    $appVersionJson = [ordered]@{
        versionCode = [int]$VERSION_CODE
        versionName = $VERSION_NAME
        channel     = "default"
        apkUrl      = $r2PublicUrls["arm64-v8a"]
        apkUrls     = [ordered]@{
            "arm64-v8a"    = $r2PublicUrls["arm64-v8a"]
            "armeabi-v7a"  = $r2PublicUrls["armeabi-v7a"]
        }
    } | ConvertTo-Json -Depth 5
    Write-Host $appVersionJson -ForegroundColor White
} else {
    Write-Host "  (defina publicBaseUrl / R2_PUBLIC_URL para gerar o JSON)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "Todos os passos concluidos com sucesso!" -ForegroundColor Green
