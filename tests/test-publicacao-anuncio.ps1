<#
.SYNOPSIS
Harness offline da publicacao: roda o build-and-upload.ps1 REAL contra um bucket R2 falso (pasta local)
e confere que o anuncio (version-harness.json) sempre bate com o APK para o qual aponta.

.DESCRIPTION
Demanda: 2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio. Invariante conferida em todo cenario,
depois de sucesso OU de falha no meio da publicacao:

  para cada ABI, o objeto para o qual o version-harness.json do bucket aponta (apkUrls[abi], e o par
  legado apkUrl) tem exatamente o apkSizes[abi]/apkSha256[abi] anunciados (legado: size/sha256).

Isolamento (nada toca o R2/Cloudflare de producao):
  - cada cenario monta uma raiz nova em $env:TEMP com copias do build-and-upload.ps1, build-env.ps1,
    lemuroid-app\build.gradle.kts (versionCode/versionName reescritos para os do APK), dist\*.apk e um
    build.properties com R2 falso (endpoint 127.0.0.1:9, chave "fake", bucket "fakebucket"), sem
    CF_API_TOKEN/CF_ZONE_ID (a purga so avisa) e sem HF_TOKEN;
  - o script roda num powershell filho com -SkipBuild -SkipHF, APP_UPDATE_CHANNEL=harness e o PATH
    comecando por <raiz>\bin, onde esta o rclone falso (tests\fake-rclone.ps1 copiado como rclone.ps1);
  - o "cache de borda" e um python -m http.server em 127.0.0.1, servindo o proprio bucket (cache
    coerente) ou uma copia dele tirada antes do script (cache servindo bytes antigos);
  - o harness falha o cenario se o log do rclone falso estiver vazio ou se o script nao tiver
    resolvido o rclone para o falso.

Cenarios:
  cache-velho         servidor serve a copia velha do bucket (o incidente de 2026-11-20 15:59 UTC);
                      esperado: script sai != 0 e a invariante vale.
  falha-upload-armv7  o rclone falso falha na chamada que contem "armv7"; esperado: script != 0 e invariante.
  sucesso             sem falha; esperado: exit 0, invariante, o json anuncia o versionCode dos APKs e o link
                      fixo retro-game-system-<abi>.apk (+ .sha256) tem os bytes novos.

Saida: uma linha PASS/FAIL por cenario com o motivo; exit 0 so se todos passarem.

.PARAMETER ApkArm64
APK arm64-v8a a publicar (padrao: o APK debug da lane).

.PARAMETER ApkArmv7
APK armeabi-v7a a publicar (padrao: o APK debug da lane).

.PARAMETER Cenario
Roda so este cenario.

.PARAMETER ManterRaiz
Nao apaga a raiz temporaria de cada cenario (para inspecao).

.EXAMPLE
./tests/test-publicacao-anuncio.ps1
.EXAMPLE
./tests/test-publicacao-anuncio.ps1 -Cenario cache-velho -ManterRaiz
#>
[CmdletBinding()]
param(
    [string]$ApkArm64 = '',
    [string]$ApkArmv7 = '',
    [string]$Cenario = '',
    [switch]$ManterRaiz
)

$ErrorActionPreference = 'Stop'
# $PSScriptRoot fica vazio nos defaults do param() no Windows PowerShell 5.1: os padroes sao resolvidos aqui.
$repoRoot = [IO.Path]::GetFullPath("$PSScriptRoot/..")
$debugApkDir = Join-Path $repoRoot 'lemuroid-app\build\outputs\apk\freeBundle\debug'
if (-not $ApkArm64) { $ApkArm64 = Join-Path $debugApkDir 'lemuroid-app-free-bundle-arm64-v8a-debug.apk' }
if (-not $ApkArmv7) { $ApkArmv7 = Join-Path $debugApkDir 'lemuroid-app-free-bundle-armeabi-v7a-debug.apk' }
$fakeRcloneSrc = Join-Path $PSScriptRoot 'fake-rclone.ps1'
$R2Folder = 'RetroGameSystem'
$Channel = 'harness'
$JsonName = "version-$Channel.json"
$AbiMap = [ordered]@{ 'arm64-v8a' = 'arm64'; 'armeabi-v7a' = 'armv7' }
$TimeoutScriptSec = 900

# Cada cenario: Servir = pasta que o servidor HTTP serve ('bucket' ou 'cdn-velho'); FalharEm = valor de
# FAKE_RCLONE_FALHAR_EM ($null = sem falha); Saida = 'falha' (exit != 0) ou 'sucesso' (exit 0);
# ArgsExtras = parametros a mais para o build-and-upload.ps1; ConfereLinksFixos = o link fixo e o .sha256
# tem de ter os bytes novos (so faz sentido quando a publicacao termina).
$Cenarios = [ordered]@{
    'cache-velho' = @{
        Descricao = 'cache de borda servindo os bytes antigos (incidente 2026-11-20)'
        Servir = 'cdn-velho'; FalharEm = $null; Saida = 'falha'; ArgsExtras = @(); ConfereLinksFixos = $false
    }
    'falha-upload-armv7' = @{
        Descricao = 'rclone falha no upload do armv7, depois do arm64'
        Servir = 'bucket'; FalharEm = 'armv7'; Saida = 'falha'; ArgsExtras = @(); ConfereLinksFixos = $false
    }
    'sucesso' = @{
        Descricao = 'publicacao completa, cache coerente'
        Servir = 'bucket'; FalharEm = $null; Saida = 'sucesso'; ArgsExtras = @(); ConfereLinksFixos = $true
    }
}

if ($Cenario -and -not $Cenarios.Contains($Cenario)) {
    throw "Cenario desconhecido: '$Cenario'. Validos: $($Cenarios.Keys -join ', ')"
}

# --- Pre-requisitos ---
. (Join-Path $repoRoot 'build-env.ps1')
Initialize-BuildEnv | Out-Null

$Apks = [ordered]@{}
foreach ($par in @(@('arm64-v8a', $ApkArm64), @('armeabi-v7a', $ApkArmv7))) {
    if (-not (Test-Path -LiteralPath $par[1] -PathType Leaf)) {
        throw "APK $($par[0]) nao encontrado: $($par[1]) (rode o build da lane ou passe -ApkArm64/-ApkArmv7)"
    }
    $Apks[$par[0]] = (Resolve-Path -LiteralPath $par[1]).ProviderPath
}

# Mesma busca do build-and-upload.ps1 (gate do versionCode): o harness precisa do code para o gradle e a semente.
$aapt = Get-ChildItem -Path (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools'), `
                            "$env:ANDROID_HOME\build-tools", "$env:ANDROID_SDK_ROOT\build-tools" `
                     -Filter 'aapt2.exe' -Recurse -ErrorAction SilentlyContinue |
        Sort-Object FullName | Select-Object -Last 1 -ExpandProperty FullName
if (-not $aapt) { throw 'aapt2.exe nao encontrado (ANDROID_HOME/ANDROID_SDK_ROOT)' }

$ApkInfo = @{}
foreach ($abi in $Apks.Keys) {
    $badging = & $aapt dump badging $Apks[$abi] 2>$null
    $line = $badging | Select-String -Pattern "^package: name='([^']+)' versionCode='(\d+)' versionName='([^']*)'"
    if (-not $line) { throw "Nao consegui ler o versionCode de $($Apks[$abi])" }
    $ApkInfo[$abi] = @{
        Code   = [int]$line.Matches[0].Groups[2].Value
        Name   = $line.Matches[0].Groups[3].Value
        Sha256 = (Get-FileHash -LiteralPath $Apks[$abi] -Algorithm SHA256).Hash.ToLower()
        Size   = (Get-Item -LiteralPath $Apks[$abi]).Length
    }
}
if ($ApkInfo['arm64-v8a'].Code -ne $ApkInfo['armeabi-v7a'].Code) {
    throw "Os dois APKs tem versionCode diferente ($($ApkInfo['arm64-v8a'].Code) x $($ApkInfo['armeabi-v7a'].Code))"
}
$ApkCode = $ApkInfo['arm64-v8a'].Code
$ApkName = $ApkInfo['arm64-v8a'].Name

$python = Get-Command python -CommandType Application -ErrorAction SilentlyContinue |
    Where-Object { $_.Source -notlike '*\WindowsApps\*' } | Select-Object -First 1 -ExpandProperty Source
if (-not $python) { throw 'python nao encontrado no PATH (o servidor HTTP local usa python -m http.server)' }

# --- Helpers ---
function Get-Sha256([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLower() }

function Get-FreePort {
    $l = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $l.Start()
    try { return $l.LocalEndpoint.Port } finally { $l.Stop() }
}

function Write-Utf8NoBom([string]$Path, [string]$Text) {
    [System.IO.File]::WriteAllText($Path, $Text, (New-Object System.Text.UTF8Encoding($false)))
}

function New-RandomFile([string]$Path, [int]$Size) {
    $bytes = New-Object byte[] $Size
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
    [System.IO.File]::WriteAllBytes($Path, $bytes)
}

# Monta a raiz temporaria do cenario e devolve um objeto com os caminhos e a URL publica.
function New-HarnessRoot([string]$Nome, [string]$FalharEm) {
    # O nome do cenario NAO entra no caminho: FAKE_RCLONE_FALHAR_EM casa com qualquer argumento, e
    # "falha-upload-armv7" no caminho da raiz faria a falha cair ja no upload do arm64.
    $raiz = Join-Path $env:TEMP ("lemuroid-harness-publicacao-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
    if ($FalharEm -and $raiz.Contains($FalharEm)) { throw "a raiz $raiz contem o texto da falha injetada '$FalharEm'" }
    $bucketFolder = Join-Path $raiz "bucket\$R2Folder"
    foreach ($d in @($raiz, (Join-Path $raiz 'lemuroid-app'), (Join-Path $raiz 'dist'), $bucketFolder,
                     (Join-Path $raiz 'bin'), (Join-Path $raiz 'tmp'))) {
        New-Item -ItemType Directory -Force -Path $d | Out-Null
    }
    $port = Get-FreePort
    $publicBase = "http://127.0.0.1:$port"

    Copy-Item -LiteralPath (Join-Path $repoRoot 'build-and-upload.ps1') -Destination $raiz
    Copy-Item -LiteralPath (Join-Path $repoRoot 'build-env.ps1') -Destination $raiz
    Copy-Item -LiteralPath $fakeRcloneSrc -Destination (Join-Path $raiz 'bin\rclone.ps1')

    # O script confere o versionCode do build.gradle.kts contra o do APK (aapt2): reescreve para o do APK.
    # -SkipBuild pula o bump, entao este arquivo nao muda durante o cenario.
    $gradle = [System.IO.File]::ReadAllText((Join-Path $repoRoot 'lemuroid-app\build.gradle.kts'))
    $gradle = [regex]::Replace($gradle, '(?m)^(\s*versionCode\s*=\s*)\d+', "`${1}$ApkCode", 1)
    $gradle = [regex]::Replace($gradle, '(?m)^(\s*versionName\s*=\s*)"[^"]*"', "`${1}`"$ApkName`"", 1)
    Write-Utf8NoBom (Join-Path $raiz 'lemuroid-app\build.gradle.kts') $gradle

    foreach ($abi in $AbiMap.Keys) {
        Copy-Item -LiteralPath $Apks[$abi] -Destination (Join-Path $raiz "dist\retro-game-system-$($AbiMap[$abi]).apk")
    }

    # R2 falso. Sem CF_API_TOKEN/CF_ZONE_ID (Purge-EdgeCache so avisa) e sem HF_TOKEN.
    Write-Utf8NoBom (Join-Path $raiz 'build.properties') (@(
        'R2_ACCESS_KEY_ID=fake'
        'R2_SECRET_ACCESS_KEY=fake'
        'R2_ENDPOINT=http://127.0.0.1:9'
        'R2_BUCKET=fakebucket'
        "R2_PUBLIC_URL=$publicBase"
    ) -join "`r`n")

    # Semente = producao como esta hoje: versao anterior (code - 1) publicada nos caminhos fixos,
    # com bytes distintos dos APKs novos, sidecars e o anuncio no formato que o script gera.
    $prevCode = $ApkCode - 1
    $seed = @{}
    $tamanho = 4096
    foreach ($abi in $AbiMap.Keys) {
        $nome = "retro-game-system-$($AbiMap[$abi]).apk"
        $arq = Join-Path $bucketFolder $nome
        New-RandomFile $arq $tamanho
        $tamanho += 1024
        $sha = Get-Sha256 $arq
        Write-Utf8NoBom "$arq.sha256" "$sha  $nome`r`n"
        $seed[$abi] = @{ Url = "$publicBase/$R2Folder/$($nome)?v=$prevCode"; Sha256 = $sha; Size = (Get-Item -LiteralPath $arq).Length }
    }
    $seedJson = [ordered]@{
        versionCode = $prevCode
        versionName = 'semente'
        channel     = $Channel
        apkUrl      = $seed['arm64-v8a'].Url
        sha256      = $seed['arm64-v8a'].Sha256
        size        = [long]$seed['arm64-v8a'].Size
        apkUrls     = [ordered]@{ 'arm64-v8a' = $seed['arm64-v8a'].Url; 'armeabi-v7a' = $seed['armeabi-v7a'].Url }
        apkSha256   = [ordered]@{ 'arm64-v8a' = $seed['arm64-v8a'].Sha256; 'armeabi-v7a' = $seed['armeabi-v7a'].Sha256 }
        apkSizes    = [ordered]@{ 'arm64-v8a' = [long]$seed['arm64-v8a'].Size; 'armeabi-v7a' = [long]$seed['armeabi-v7a'].Size }
    } | ConvertTo-Json -Depth 5
    Write-Utf8NoBom (Join-Path $bucketFolder $JsonName) $seedJson

    # "Cache de borda" velho: copia do bucket ANTES de o script rodar.
    Copy-Item -LiteralPath (Join-Path $raiz 'bucket') -Destination (Join-Path $raiz 'cdn-velho') -Recurse

    return [PSCustomObject]@{
        Raiz = $raiz; Port = $port; PublicBase = $publicBase
        Bucket = Join-Path $raiz 'bucket'; BucketFolder = $bucketFolder
        RcloneLog = Join-Path $raiz 'rclone.log'; FakeRclone = Join-Path $raiz 'bin\rclone.ps1'
        Seed = $seed
    }
}

function Start-HttpServer([string]$Dir, [int]$Port, [string]$Raiz) {
    $p = Start-Process -FilePath $python -PassThru -NoNewWindow `
        -ArgumentList @('-I', '-m', 'http.server', "$Port", '--bind', '127.0.0.1', '--directory', "`"$Dir`"") `
        -RedirectStandardOutput (Join-Path $Raiz 'http.out.log') -RedirectStandardError (Join-Path $Raiz 'http.err.log')
    $limite = (Get-Date).AddSeconds(15)
    while ((Get-Date) -lt $limite) {
        if ($p.HasExited) { throw "servidor HTTP saiu com codigo $($p.ExitCode) (ver $Raiz\http.err.log)" }
        $c = New-Object System.Net.Sockets.TcpClient
        try { $c.Connect('127.0.0.1', $Port); return $p } catch { Start-Sleep -Milliseconds 200 } finally { $c.Close() }
    }
    Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
    throw "servidor HTTP nao abriu a porta $Port em 15 s"
}

function Stop-HttpServer($Proc) {
    if ($Proc -and -not $Proc.HasExited) {
        Stop-Process -Id $Proc.Id -Force -ErrorAction SilentlyContinue
        [void]$Proc.WaitForExit(10000)
    }
}

# Roda o build-and-upload.ps1 da raiz num powershell filho. Devolve o exit code.
function Invoke-PublishScript($H, $Cfg) {
    $envVars = [ordered]@{
        Path                   = "$($H.Raiz)\bin;$env:Path"
        APP_UPDATE_CHANNEL     = $Channel
        FAKE_RCLONE_BUCKET_DIR = $H.Bucket
        FAKE_RCLONE_BUCKET     = 'fakebucket'
        FAKE_RCLONE_LOG        = $H.RcloneLog
        FAKE_RCLONE_FALHAR_EM  = $Cfg.FalharEm
        TEMP                   = Join-Path $H.Raiz 'tmp'
        TMP                    = Join-Path $H.Raiz 'tmp'
        HF_TOKEN               = $null
    }
    $salvas = @{}
    foreach ($k in $envVars.Keys) { $salvas[$k] = [Environment]::GetEnvironmentVariable($k, 'Process') }
    try {
        foreach ($k in $envVars.Keys) { [Environment]::SetEnvironmentVariable($k, $envVars[$k], 'Process') }
        $argList = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', "`"$($H.Raiz)\build-and-upload.ps1`"",
                     '-SkipBuild', '-SkipHF') + @($Cfg.ArgsExtras)
        $p = Start-Process -FilePath 'powershell.exe' -ArgumentList $argList -PassThru -NoNewWindow `
            -WorkingDirectory $H.Raiz `
            -RedirectStandardOutput (Join-Path $H.Raiz 'script.out.log') `
            -RedirectStandardError (Join-Path $H.Raiz 'script.err.log')
        $null = $p.Handle   # sem isto o ExitCode volta vazio no Windows PowerShell 5.1
    } finally {
        foreach ($k in $salvas.Keys) { [Environment]::SetEnvironmentVariable($k, $salvas[$k], 'Process') }
    }
    if (-not $p.WaitForExit($TimeoutScriptSec * 1000)) {
        Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
        throw "build-and-upload.ps1 nao terminou em $TimeoutScriptSec s"
    }
    $p.WaitForExit()
    return $p.ExitCode
}

# A invariante. Devolve a lista de divergencias (vazia = vale).
function Test-AnuncioCoerente($H) {
    $erros = @()
    $jsonPath = Join-Path $H.BucketFolder $JsonName
    if (-not (Test-Path -LiteralPath $jsonPath)) { return @("$JsonName ausente no bucket") }
    $json = Get-Content -LiteralPath $jsonPath -Raw | ConvertFrom-Json
    $alvos = @()
    foreach ($abi in $AbiMap.Keys) {
        $alvos += @{ Rotulo = "apkUrls[$abi]"; Url = $json.apkUrls.$abi; Sha = $json.apkSha256.$abi; Size = $json.apkSizes.$abi }
    }
    $alvos += @{ Rotulo = 'apkUrl (legado)'; Url = $json.apkUrl; Sha = $json.sha256; Size = $json.size }
    foreach ($a in $alvos) {
        if (-not $a.Url -or -not $a.Sha -or $null -eq $a.Size) {
            $erros += "$($a.Rotulo): anuncio incompleto (url='$($a.Url)' sha='$($a.Sha)' size='$($a.Size)')"
            continue
        }
        $semQuery = ([string]$a.Url).Split('?')[0]
        $prefixo = "$($H.PublicBase)/"
        if (-not $semQuery.StartsWith($prefixo)) {
            $erros += "$($a.Rotulo): URL fora do bucket: $($a.Url)"
            continue
        }
        $local = $H.Bucket
        foreach ($parte in ($semQuery.Substring($prefixo.Length) -split '/' | Where-Object { $_ -ne '' })) {
            $local = Join-Path $local ([Uri]::UnescapeDataString($parte))
        }
        $anunciado = "$(([string]$a.Sha).ToLower()) ($($a.Size) B)"
        if (-not (Test-Path -LiteralPath $local -PathType Leaf)) {
            $erros += "$($a.Rotulo) -> $($semQuery.Substring($prefixo.Length)): anunciado $anunciado | encontrado: AUSENTE"
            continue
        }
        $sha = Get-Sha256 $local
        $size = (Get-Item -LiteralPath $local).Length
        if ($sha -ne ([string]$a.Sha).ToLower() -or [long]$size -ne [long]$a.Size) {
            $erros += "$($a.Rotulo) -> $($semQuery.Substring($prefixo.Length)): anunciado $anunciado | encontrado $sha ($size B)"
        }
    }
    return $erros
}

# Link fixo (o que o suporte manda) e sidecar com os bytes novos.
function Test-LinksFixos($H) {
    $erros = @()
    foreach ($abi in $AbiMap.Keys) {
        $nome = "retro-game-system-$($AbiMap[$abi]).apk"
        $arq = Join-Path $H.BucketFolder $nome
        if (-not (Test-Path -LiteralPath $arq)) { $erros += "$($nome): ausente"; continue }
        $sha = Get-Sha256 $arq
        if ($sha -ne $ApkInfo[$abi].Sha256) { $erros += "$($nome): $sha, esperado $($ApkInfo[$abi].Sha256) (APK novo)" }
        $side = "$arq.sha256"
        if (-not (Test-Path -LiteralPath $side)) { $erros += "$nome.sha256: ausente"; continue }
        $sideSha = ((Get-Content -LiteralPath $side -Raw).Trim() -split '\s+')[0].ToLower()
        if ($sideSha -ne $ApkInfo[$abi].Sha256) { $erros += "$nome.sha256: $sideSha, esperado $($ApkInfo[$abi].Sha256)" }
    }
    return $erros
}

function Get-UltimasLinhas([string]$Path, [int]$N) {
    if (-not (Test-Path -LiteralPath $Path)) { return @() }
    return @(Get-Content -LiteralPath $Path | Where-Object { $_.Trim() -ne '' } | Select-Object -Last $N)
}

function Invoke-Cenario([string]$Nome, $Cfg) {
    $H = $null
    $srv = $null
    $motivos = @()
    $exit = $null
    try {
        $H = New-HarnessRoot $Nome $Cfg.FalharEm
        Write-Host "[$Nome] raiz: $($H.Raiz)" -ForegroundColor DarkGray
        $servir = if ($Cfg.Servir -eq 'cdn-velho') { Join-Path $H.Raiz 'cdn-velho' } else { $H.Bucket }
        $srv = Start-HttpServer $servir $H.Port $H.Raiz
        $exit = Invoke-PublishScript $H $Cfg
    } catch {
        $motivos += "erro do harness: $($_.Exception.Message)"
    } finally {
        Stop-HttpServer $srv
    }

    if ($H -and $null -ne $exit) {
        # Quem rodou foi o falso?
        $log = Get-UltimasLinhas $H.RcloneLog 1000
        if ($log.Count -eq 0) { $motivos += 'o rclone falso nao foi chamado (log vazio)' }
        if ($Cfg.FalharEm -and -not ($log | Where-Object { $_ -match "`tFALHA-INJETADA`t" })) {
            $motivos += "a falha injetada ('$($Cfg.FalharEm)') nao disparou: o cenario nao testou o que diz"
        }
        $out = if (Test-Path -LiteralPath (Join-Path $H.Raiz 'script.out.log')) { Get-Content -LiteralPath (Join-Path $H.Raiz 'script.out.log') -Raw } else { '' }
        if (-not $out.Contains("rclone : $($H.FakeRclone)")) {
            $motivos += "o script nao resolveu o rclone para o falso ($($H.FakeRclone))"
        }

        if ($Cfg.Saida -eq 'sucesso' -and $exit -ne 0) { $motivos += "script saiu $exit, esperado 0" }
        if ($Cfg.Saida -eq 'falha' -and $exit -eq 0) { $motivos += 'script saiu 0, esperado != 0' }

        $div = @(Test-AnuncioCoerente $H)
        if ($div.Count -gt 0) {
            $motivos += 'invariante QUEBRADA: o anuncio nao bate com o objeto para o qual aponta'
            $motivos += @($div | ForEach-Object { "  $_" })
        }

        if ($Cfg.Saida -eq 'sucesso') {
            $json = Get-Content -LiteralPath (Join-Path $H.BucketFolder $JsonName) -Raw | ConvertFrom-Json
            if ([int]$json.versionCode -ne $ApkCode) { $motivos += "o anuncio diz versionCode $($json.versionCode), esperado $ApkCode" }
        }
        if ($Cfg.ConfereLinksFixos) {
            $fix = @(Test-LinksFixos $H)
            if ($fix.Count -gt 0) {
                $motivos += 'link fixo sem os bytes novos:'
                $motivos += @($fix | ForEach-Object { "  $_" })
            }
        }
    }

    $resultado = [PSCustomObject]@{ Nome = $Nome; Passou = ($motivos.Count -eq 0); Exit = $exit; Motivos = $motivos }
    $exitTxt = if ($null -eq $exit) { 'n/a' } else { $exit }
    if ($resultado.Passou) {
        Write-Host "PASS $Nome ($($Cfg.Descricao)): script exit $exitTxt, anuncio coerente" -ForegroundColor Green
    } else {
        Write-Host "FAIL $Nome ($($Cfg.Descricao)): script exit $exitTxt" -ForegroundColor Red
        foreach ($m in $motivos) { Write-Host "  $m" -ForegroundColor Red }
        if ($H) {
            $err = Get-UltimasLinhas (Join-Path $H.Raiz 'script.err.log') 6
            if ($err.Count -gt 0) {
                Write-Host '  ultimas linhas de erro do script:' -ForegroundColor DarkGray
                foreach ($l in $err) { Write-Host "    $l" -ForegroundColor DarkGray }
            }
        }
    }

    if ($H) {
        if ($ManterRaiz) {
            Write-Host "  raiz mantida: $($H.Raiz)" -ForegroundColor DarkGray
        } else {
            Remove-Item -LiteralPath $H.Raiz -Recurse -Force -ErrorAction SilentlyContinue
            if (Test-Path -LiteralPath $H.Raiz) { Write-Host "  AVISO: nao consegui apagar $($H.Raiz)" -ForegroundColor Yellow }
        }
    }
    return $resultado
}

# --- Execucao ---
Write-Host "APKs: versionCode $ApkCode ($ApkName)"
foreach ($abi in $Apks.Keys) { Write-Host "  $abi : $($ApkInfo[$abi].Sha256) ($($ApkInfo[$abi].Size) B)  $($Apks[$abi])" }
Write-Host ''

$nomes = if ($Cenario) { @($Cenario) } else { @($Cenarios.Keys) }
$resultados = @()
foreach ($n in $nomes) { $resultados += Invoke-Cenario $n $Cenarios[$n] }

$falhas = @($resultados | Where-Object { -not $_.Passou })
Write-Host ''
Write-Host "$($resultados.Count - $falhas.Count)/$($resultados.Count) cenario(s) passaram."
if ($falhas.Count -gt 0) { exit 1 }
exit 0
