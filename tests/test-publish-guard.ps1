<#
.SYNOPSIS
Testa a trava de versao da publicacao (publish-guard.ps1).
.DESCRIPTION
Demanda 2026-11-20-publicacao-manual-sem-commit-versao-duplicada. Roda em Windows PowerShell 5.1
(o do dono) e no pwsh. Sai com codigo diferente de 0 na primeira falha.

Offline: tabela de decisao de Test-PublishVersionGate com anuncios injetados (limites == e +1 nos
tres modos), parse do version.json, mensagem de aborto, estado do checkout fora do git e a ordem
trava -> WriteAllText dentro do build-and-upload.ps1.
Online (pulado com -Offline): Get-AnnouncedVersion contra o anuncio real, um canal inexistente
(404 -> $null) e um host que nao resolve (lanca).
.EXAMPLE
powershell -NoProfile -File tests/test-publish-guard.ps1
.EXAMPLE
powershell -NoProfile -File tests/test-publish-guard.ps1 -Offline
#>
param(
    [switch]$Offline
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath("$PSScriptRoot/..")
. (Join-Path $repoRoot 'publish-guard.ps1')

$script:passed = 0

function Fail([string]$Name, [string]$Detail) {
    Write-Host "FALHOU: $Name" -ForegroundColor Red
    Write-Host "  $Detail" -ForegroundColor Red
    exit 1
}

function Pass([string]$Name) {
    $script:passed++
    Write-Host "ok   $Name" -ForegroundColor Green
}

# Gate devolve $null (segue) ou texto (aborta).
function Assert-Gate([string]$Name, [bool]$ExpectAbort, [int]$TargetCode, [string]$Mode, $Announced, [string]$DistSha = $null) {
    $reason = Test-PublishVersionGate -TargetCode $TargetCode -Mode $Mode -Announced $Announced -DistSha $DistSha
    if ($ExpectAbort) {
        if ($null -eq $reason -or -not ($reason -is [string]) -or $reason.Length -eq 0) {
            Fail $Name "esperado abortar, a trava deixou seguir"
        }
    } elseif ($null -ne $reason) {
        Fail $Name "esperado seguir, a trava abortou: $reason"
    }
    Pass $Name
}

function Assert-Throws([string]$Name, [scriptblock]$Block, [string]$MessageLike = '*') {
    $threw = $false
    $msg = ''
    try { & $Block | Out-Null } catch { $threw = $true; $msg = $_.Exception.Message }
    if (-not $threw) { Fail $Name "esperado lancar, nao lancou" }
    if ($msg -notlike $MessageLike) { Fail $Name "mensagem '$msg' nao casa '$MessageLike'" }
    Pass $Name
}

function Assert-True([string]$Name, [bool]$Condition, [string]$Detail = '') {
    if (-not $Condition) { Fail $Name $Detail }
    Pass $Name
}

$sha = 'aa24ca6d0000000000000000000000000000000000000000000000000000beef'
$live = [PSCustomObject]@{ versionCode = 269; versionName = '1.17.38'; sha256 = $sha }
$liveSemSha = [PSCustomObject]@{ versionCode = 269; versionName = '1.17.38'; sha256 = '' }

Write-Host '--- Test-PublishVersionGate: canal sem anuncio ($null) ---'
foreach ($mode in 'Bump', 'NoBump', 'SkipBuild') {
    Assert-Gate "$mode sem anuncio segue" $false 1 $mode $null
}

Write-Host '--- Test-PublishVersionGate: Bump (TargetCode ja com o +1) ---'
Assert-Gate 'Bump 268 com 269 no ar aborta (a 268 duplicada)' $true 268 'Bump' $live
Assert-Gate 'Bump 269 com 269 no ar aborta (limite ==)'        $true 269 'Bump' $live
Assert-Gate 'Bump 270 com 269 no ar segue (limite +1)'         $false 270 'Bump' $live
Assert-Gate 'Bump 1 com 269 no ar aborta'                      $true 1 'Bump' $live

Write-Host '--- Test-PublishVersionGate: NoBump ---'
Assert-Gate 'NoBump 268 com 269 no ar aborta'          $true 268 'NoBump' $live
Assert-Gate 'NoBump 269 com 269 no ar aborta (limite ==: mesmo numero, bytes novos)' $true 269 'NoBump' $live
Assert-Gate 'NoBump 269 aborta mesmo com sha igual'    $true 269 'NoBump' $live $sha
Assert-Gate 'NoBump 270 com 269 no ar segue (limite +1)' $false 270 'NoBump' $live

Write-Host '--- Test-PublishVersionGate: SkipBuild ---'
Assert-Gate 'SkipBuild 268 com 269 no ar aborta'                          $true 268 'SkipBuild' $live $sha
Assert-Gate 'SkipBuild 269 com o mesmo binario segue (republicacao)'      $false 269 'SkipBuild' $live $sha
Assert-Gate 'SkipBuild 269 com o mesmo binario em maiusculas segue'       $false 269 'SkipBuild' $live $sha.ToUpperInvariant()
Assert-Gate 'SkipBuild 269 com outro binario aborta'                      $true 269 'SkipBuild' $live ('0' * 64)
Assert-Gate 'SkipBuild 269 sem APK em dist\ aborta'                       $true 269 'SkipBuild' $live $null
Assert-Gate 'SkipBuild 269 com anuncio sem sha256 aborta'                 $true 269 'SkipBuild' $liveSemSha $sha
Assert-Gate 'SkipBuild 270 com 269 no ar segue (limite +1)'               $false 270 'SkipBuild' $live $null

Write-Host '--- ConvertFrom-AnnouncementJson ---'
$parsed = ConvertFrom-AnnouncementJson -Content "{`"versionCode`": 269, `"versionName`": `"1.17.38`", `"sha256`": `"$sha`", `"size`": 1}" -Url 'u'
Assert-True 'JSON valido vira objeto com versionCode [int]' (($parsed.versionCode -is [int]) -and $parsed.versionCode -eq 269 -and $parsed.versionName -eq '1.17.38' -and $parsed.sha256 -eq $sha) "veio: $($parsed | ConvertTo-Json -Compress)"
$bom = [string][char]0xFEFF
$parsedBom = ConvertFrom-AnnouncementJson -Content "$bom{`"versionCode`": 7}" -Url 'u'
Assert-True 'JSON com BOM e aceito' ($parsedBom.versionCode -eq 7)
Assert-Throws 'JSON sem versionCode lanca'           { ConvertFrom-AnnouncementJson -Content '{"versionName": "1.0"}' -Url 'u' } '*versionCode inteiro*'
Assert-Throws 'versionCode string lanca'             { ConvertFrom-AnnouncementJson -Content '{"versionCode": "269"}' -Url 'u' } '*versionCode inteiro*'
Assert-Throws 'versionCode decimal lanca'            { ConvertFrom-AnnouncementJson -Content '{"versionCode": 269.5}' -Url 'u' } '*versionCode inteiro*'
Assert-Throws 'versionCode zero lanca'               { ConvertFrom-AnnouncementJson -Content '{"versionCode": 0}' -Url 'u' } '*versionCode inteiro*'
Assert-Throws 'JSON invalido lanca'                  { ConvertFrom-AnnouncementJson -Content '<html>erro</html>' -Url 'u' } '*JSON valido*'
Assert-Throws 'conteudo vazio lanca'                 { ConvertFrom-AnnouncementJson -Content '' -Url 'u' } '*vazio*'

Write-Host '--- Get-PublishCheckoutState / Assert-PublishVersionGate ---'
$tmp = Join-Path ([IO.Path]::GetTempPath()) "publish-guard-test-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $tmp | Out-Null
try {
    $state = Get-PublishCheckoutState -RepoRoot $tmp
    Assert-True 'fora do git: commit desconhecido' ($state.Commit -eq 'desconhecido') "veio: $($state.Commit)"

    $url = 'https://versions.example/RetroGameSystem/version.json'
    $abortMsg = $null
    try {
        Assert-PublishVersionGate -TargetCode 269 -TargetName '1.17.38' -Mode Bump -Announced $live -AnnouncementUrl $url -RepoRoot $tmp
    } catch { $abortMsg = $_.Exception.Message }
    Assert-True 'Assert lanca no caso da 268 duplicada' ($null -ne $abortMsg)
    foreach ($needle in @(('versionCode 269 (1.17.38) em ' + $url),'versionCode 269 (1.17.38)', 'commit de partida:      desconhecido', 'publicado/1.17.38', 'Nada foi escrito')) {
        Assert-True "mensagem cita '$needle'" ($abortMsg.Contains($needle)) "mensagem:`n$abortMsg"
    }

    $noThrow = $true
    try {
        Assert-PublishVersionGate -TargetCode 270 -TargetName '1.17.39' -Mode Bump -Announced $live -AnnouncementUrl $url -RepoRoot $tmp
        Assert-PublishVersionGate -TargetCode 1 -Mode NoBump -Announced $null -AnnouncementUrl $url -RepoRoot $tmp
    } catch { $noThrow = $false; $err = $_.Exception.Message }
    Assert-True 'Assert nao lanca quando a trava deixa seguir' $noThrow "lancou: $err"
} finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

$stateRepo = Get-PublishCheckoutState -RepoRoot $repoRoot
if ($stateRepo.Commit -ne 'desconhecido') {
    Assert-True 'no repo: commit curto e estado do .kts' (($stateRepo.Commit -match '^[0-9a-f]{4,}$') -and ($stateRepo.FileDirty -in @('sim', 'nao'))) "veio: $($stateRepo | ConvertTo-Json -Compress)"
}

Write-Host '--- build-and-upload.ps1: a trava vem antes de qualquer escrita ---'
$scriptText = [IO.File]::ReadAllText((Join-Path $repoRoot 'build-and-upload.ps1'))
$iRead  = $scriptText.IndexOf('$announcedVersion = Get-AnnouncedVersion')
$iGate  = $scriptText.IndexOf('-Mode Bump')
$iWrite = $scriptText.IndexOf('[System.IO.File]::WriteAllText($GRADLE_FILE')
$iSkip  = $scriptText.IndexOf('-Mode $gateMode')
$iStep1 = $scriptText.IndexOf('PASSO 1/4')
Assert-True 'anuncio lido antes da trava do bump' ($iRead -ge 0 -and $iRead -lt $iGate) "read=$iRead gate=$iGate"
Assert-True 'trava do bump antes do WriteAllText' ($iGate -ge 0 -and $iWrite -gt $iGate) "gate=$iGate write=$iWrite"
Assert-True 'trava NoBump/SkipBuild antes do passo 1' ($iSkip -gt $iWrite -and $iSkip -lt $iStep1) "skip=$iSkip passo1=$iStep1"

if ($Offline) {
    Write-Host '--- Get-AnnouncedVersion: PULADO (-Offline) ---' -ForegroundColor Yellow
} else {
    Write-Host '--- Get-AnnouncedVersion (rede) ---'
    $real = Get-AnnouncedVersion -Url 'https://versions.digitalstoregames.com/RetroGameSystem/version.json'
    Assert-True 'anuncio real tem versionCode inteiro > 0' (($null -ne $real) -and ($real.versionCode -is [int]) -and $real.versionCode -gt 0) "veio: $($real | ConvertTo-Json -Compress)"
    Write-Host "     (no ar: $($real.versionCode) / $($real.versionName))"

    $ghost = Get-AnnouncedVersion -Url "https://versions.digitalstoregames.com/RetroGameSystem/version-canalinexistente-$([guid]::NewGuid().ToString('N')).json"
    Assert-True 'canal inexistente (404) devolve $null' ($null -eq $ghost) "veio: $($ghost | ConvertTo-Json -Compress)"

    Assert-Throws 'host que nao resolve lanca' { Get-AnnouncedVersion -Url 'https://invalido.invalid/version.json' -TimeoutSec 15 } '*Nao foi possivel ler o anuncio*'
}

Write-Host ""
Write-Host "Todos os $($script:passed) casos passaram." -ForegroundColor Green
exit 0
