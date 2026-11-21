# Trava de versao da publicacao. Dot-source no build-and-upload.ps1, como o build-env.ps1:
#     . (Join-Path $PROJECT_ROOT "publish-guard.ps1")
#
# Por que existe (demanda 2026-11-20-publicacao-manual-sem-commit-versao-duplicada): o
# build-and-upload.ps1 sobe o versionCode no build.gradle.kts e publica, mas nao commita o bump.
# Em 19/11 e 20/11/2026 duas arvores partiram do mesmo 267 e cada uma publicou a SUA 268 (bytes
# diferentes). O app compara so o versionCode: quem instalou a primeira 268 nunca seria avisado
# da segunda. A trava le o version.json que esta no ar ANTES do bump e recusa gerar um numero que
# nao passe dele.
#
# Arquivo so em ASCII de proposito: o Windows PowerShell 5.1 le .ps1 sem BOM como ANSI.

function ConvertFrom-AnnouncementJson {
    <#
    .SYNOPSIS
    Converte o texto do version.json no objeto da trava; lanca se o JSON nao serve.

    .DESCRIPTION
    Devolve [PSCustomObject] com versionCode ([int] > 0), versionName e sha256 (o do arm64, campo
    legado que o anuncio sempre traz). JSON invalido ou sem versionCode inteiro lanca: um anuncio
    que nao da para ler nao prova que o numero esta livre.
    #>
    param(
        [AllowEmptyString()][string]$Content,
        [string]$Url = ''
    )
    $text = if ($null -eq $Content) { '' } else { $Content.TrimStart([char]0xFEFF).Trim() }
    if (-not $text) {
        throw "Anuncio da versao vazio em ${Url}: nao da para saber qual versao esta no ar."
    }
    try {
        $json = $text | ConvertFrom-Json
    } catch {
        throw "Anuncio da versao em $Url nao e JSON valido ($($_.Exception.Message)); nao da para saber qual versao esta no ar."
    }
    $code = $null
    if ($json -and ($json.PSObject.Properties.Name -contains 'versionCode')) { $code = $json.versionCode }
    $isInteger = ($code -is [int]) -or ($code -is [long])
    if (-not $isInteger -or $code -le 0 -or $code -gt [int]::MaxValue) {
        throw "Anuncio da versao em $Url sem versionCode inteiro (veio: '$code'); nao da para saber qual versao esta no ar."
    }
    return [PSCustomObject]@{
        versionCode = [int]$code
        versionName = [string]$json.versionName
        sha256      = [string]$json.sha256
    }
}

function Get-AnnouncedVersion {
    <#
    .SYNOPSIS
    Le o version.json publico do canal. $null SO quando o servidor responde 404 (canal sem anuncio).

    .DESCRIPTION
    GET com cache-busting (?t=<ticks>) e Cache-Control: no-cache: o cache de borda segura o
    anuncio velho, e uma trava que le a versao anterior deixaria passar exatamente o numero que
    ja esta no ar. Qualquer falha que nao seja 404 (sem rede, DNS, 5xx, 403, JSON sem
    versionCode inteiro) LANCA: seguir as cegas reabre o bug, e sem rede a publicacao morreria
    adiante de qualquer jeito.

    O status vem do objeto de resposta, nunca do texto da mensagem (que muda com o idioma do
    Windows): no 5.1 e System.Net.WebException com .Response.StatusCode; no 7 e
    HttpResponseException com a mesma propriedade.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Url,
        [int]$TimeoutSec = 30
    )
    [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
    $sep = if ($Url.Contains('?')) { '&' } else { '?' }
    $fetchUrl = "$Url${sep}t=$([DateTime]::UtcNow.Ticks)"

    $savedProgress = $ProgressPreference
    $ProgressPreference = 'SilentlyContinue'
    try {
        $resp = Invoke-WebRequest -Uri $fetchUrl -UseBasicParsing -TimeoutSec $TimeoutSec `
            -Headers @{ 'Cache-Control' = 'no-cache'; 'Pragma' = 'no-cache' }
    } catch {
        $status = $null
        $response = $_.Exception.Response
        if ($null -ne $response) {
            try { $status = [int]$response.StatusCode } catch { $status = $null }
        }
        if ($status -eq 404) { return $null }
        $detail = if ($null -ne $status) { "HTTP $status" } else { $_.Exception.Message }
        throw "Nao foi possivel ler o anuncio da versao em $Url ($detail). A trava de versao precisa dele para nao repetir um versionCode ja publicado; confira a conexao e repita."
    } finally {
        $ProgressPreference = $savedProgress
    }

    $content = $resp.Content
    if ($content -is [byte[]]) { $content = [System.Text.Encoding]::UTF8.GetString($content) }
    return ConvertFrom-AnnouncementJson -Content $content -Url $Url
}

function Test-PublishVersionGate {
    <#
    .SYNOPSIS
    Funcao pura: $null = pode seguir; texto = motivo para abortar.

    .DESCRIPTION
    | Modo      | Anuncio | Regra                                                              |
    |-----------|---------|--------------------------------------------------------------------|
    | qualquer  | $null   | segue (canal sem anuncio; o chamador avisa)                        |
    | Bump      | existe  | TargetCode (ja com o +1) <= anunciado -> aborta                    |
    | NoBump    | existe  | TargetCode <= anunciado -> aborta (mesmo numero, bytes novos)      |
    | SkipBuild | existe  | < anunciado -> aborta; == anunciado so segue se DistSha == sha256  |
    |           |         | anunciado (republicacao do mesmo binario), senao aborta            |
    #>
    param(
        [Parameter(Mandatory = $true)][int]$TargetCode,
        [Parameter(Mandatory = $true)][ValidateSet('Bump', 'NoBump', 'SkipBuild')][string]$Mode,
        [AllowNull()]$Announced = $null,
        [AllowNull()][AllowEmptyString()][string]$DistSha = $null
    )
    if ($null -eq $Announced) { return $null }
    $live = [int]$Announced.versionCode

    switch ($Mode) {
        'Bump' {
            if ($TargetCode -le $live) {
                return "o bump geraria o versionCode $TargetCode, que nao passa do $live que ja esta no ar."
            }
        }
        'NoBump' {
            if ($TargetCode -le $live) {
                return "-NoBump recompilaria o versionCode $TargetCode, que nao passa do $live que ja esta no ar (mesmo numero com bytes novos e o que o app nunca oferece)."
            }
        }
        'SkipBuild' {
            if ($TargetCode -lt $live) {
                return "-SkipBuild publicaria o versionCode $TargetCode, abaixo do $live que ja esta no ar."
            }
            if ($TargetCode -eq $live) {
                $liveSha = [string]$Announced.sha256
                $sameBinary = $DistSha -and $liveSha -and ($DistSha.Trim().ToLowerInvariant() -eq $liveSha.Trim().ToLowerInvariant())
                if (-not $sameBinary) {
                    $shown = if ($DistSha) { $DistSha } else { '(sem dist\retro-game-system-arm64.apk)' }
                    return "-SkipBuild republicaria o versionCode $TargetCode, que ja esta no ar com OUTRO binario (anunciado sha256 $liveSha; dist\ arm64 $shown)."
                }
            }
        }
    }
    return $null
}

function Get-PublishCheckoutState {
    <#
    .SYNOPSIS
    Commit de partida e se o build.gradle.kts tem mudanca nao commitada, para a mensagem da trava.

    .DESCRIPTION
    Devolve 'desconhecido' quando nao ha git, quando RepoRoot nao e a raiz de um repositorio
    (copia de rascunho do script) ou quando o git falha. Nunca lanca: a mensagem de aborto nao
    pode virar outro erro.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$RepoRoot,
        [string]$RelativeFile = 'lemuroid-app/build.gradle.kts'
    )
    $state = [PSCustomObject]@{ Commit = 'desconhecido'; FileDirty = 'desconhecido' }
    if (-not (Get-Command git -ErrorAction SilentlyContinue)) { return $state }

    # No 5.1, stderr de executavel nativo com EAP=Stop vira excecao; aqui erro de git e so "desconhecido".
    $savedEap = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $top = & git -C $RepoRoot rev-parse --show-toplevel 2>$null
        if ($LASTEXITCODE -ne 0 -or -not $top) { return $state }
        $topFull = [IO.Path]::GetFullPath(([string]$top).Trim()).TrimEnd('\', '/')
        $rootFull = [IO.Path]::GetFullPath($RepoRoot).TrimEnd('\', '/')
        if ($topFull -ne $rootFull) { return $state }   # -ne em string ja ignora caixa

        $head = & git -C $RepoRoot rev-parse --short HEAD 2>$null
        if ($LASTEXITCODE -eq 0 -and $head) { $state.Commit = ([string]$head).Trim() }

        $dirty = & git -C $RepoRoot status --porcelain -- $RelativeFile 2>$null
        if ($LASTEXITCODE -eq 0) { $state.FileDirty = if ($dirty) { 'sim' } else { 'nao' } }
    } catch {
        # fica 'desconhecido'
    } finally {
        $ErrorActionPreference = $savedEap
    }
    return $state
}

function Format-PublishGateMessage {
    <#
    .SYNOPSIS
    Texto do aborto: o que esta no ar, o que seria gerado, de onde o script partiu e o que fazer.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$Reason,
        [Parameter(Mandatory = $true)]$Announced,
        [Parameter(Mandatory = $true)][string]$AnnouncementUrl,
        [Parameter(Mandatory = $true)][int]$TargetCode,
        [string]$TargetName = '',
        [Parameter(Mandatory = $true)][ValidateSet('Bump', 'NoBump', 'SkipBuild')][string]$Mode,
        [string]$Commit = 'desconhecido',
        [string]$FileDirty = 'desconhecido'
    )
    $liveCode = [int]$Announced.versionCode
    $liveName = [string]$Announced.versionName
    $todo = switch ($Mode) {
        'Bump' {
            "O bump da versao no ar nao esta neste checkout: traga o commit 'chore(versao): publicado/$liveName' (git pull),`n" +
            "ou edite o versionCode de lemuroid-app/build.gradle.kts para $liveCode ou mais (o bump soma 1)`n" +
            "e passe -VersionName com um nome acima de $liveName."
        }
        'NoBump' {
            "Rode sem -NoBump depois de trazer o commit 'chore(versao): publicado/$liveName' (git pull),`n" +
            "ou edite lemuroid-app/build.gradle.kts para um versionCode acima de $liveCode e um versionName acima de $liveName."
        }
        'SkipBuild' {
            "dist\ nao tem o binario que esta no ar com esse numero. Rode sem -SkipBuild (com o bump) para`n" +
            "publicar uma versao acima de $liveCode; se o .kts estiver para tras, traga antes o commit 'chore(versao): publicado/$liveName'."
        }
    }
    $target = if ($TargetName) { "versionCode $TargetCode ($TargetName)" } else { "versionCode $TargetCode" }
    return @"
TRAVA DE VERSAO: $Reason
  no ar:                  versionCode $liveCode ($liveName) em $AnnouncementUrl
  seria gerada/publicada: $target
  commit de partida:      $Commit
  lemuroid-app/build.gradle.kts com mudanca nao commitada: $FileDirty
Nada foi escrito: o build.gradle.kts e o dist\ estao como estavam.
$todo
"@
}

function Assert-PublishVersionGate {
    <#
    .SYNOPSIS
    Aplica a trava: lanca com a mensagem completa quando Test-PublishVersionGate manda abortar.
    #>
    param(
        [Parameter(Mandatory = $true)][int]$TargetCode,
        [string]$TargetName = '',
        [Parameter(Mandatory = $true)][ValidateSet('Bump', 'NoBump', 'SkipBuild')][string]$Mode,
        [AllowNull()]$Announced = $null,
        [AllowNull()][AllowEmptyString()][string]$DistSha = $null,
        [Parameter(Mandatory = $true)][string]$AnnouncementUrl,
        [Parameter(Mandatory = $true)][string]$RepoRoot
    )
    $reason = Test-PublishVersionGate -TargetCode $TargetCode -Mode $Mode -Announced $Announced -DistSha $DistSha
    if ($null -eq $reason) { return }
    $state = Get-PublishCheckoutState -RepoRoot $RepoRoot
    throw (Format-PublishGateMessage -Reason $reason -Announced $Announced -AnnouncementUrl $AnnouncementUrl `
            -TargetCode $TargetCode -TargetName $TargetName -Mode $Mode -Commit $state.Commit -FileDirty $state.FileDirty)
}

function Get-PublishCommitReminder {
    <#
    .SYNOPSIS
    Funcao pura: linhas do lembrete de commit/tag/push do bump publicado.

    .DESCRIPTION
    O script nao commita (a arvore do dono e compartilhada com outras sessoes); ele so diz o que
    falta. Demanda 2026-11-20-publicacao-manual-sem-commit-versao-duplicada: o bump da 268 ficou
    ~35 h sem commit e a proxima publicacao repetiu o numero.
    Devolve [PSCustomObject] @{ Pending = [bool]; Lines = [string[]] }.
    #>
    param(
        [Parameter(Mandatory = $true)][string]$VersionName,
        [AllowNull()][AllowEmptyString()][string]$Branch = '',
        [bool]$GradleDirty = $false,
        [bool]$TagExists = $false
    )
    $tag = "publicado/$VersionName"
    $lines = New-Object System.Collections.Generic.List[string]
    $pending = $GradleDirty -or (-not $TagExists)
    if (-not $pending) {
        $lines.Add("versao $VersionName ja commitada e com tag $tag.")
        return [PSCustomObject]@{ Pending = $false; Lines = [string[]]$lines.ToArray() }
    }

    $b = if ($null -eq $Branch) { '' } else { $Branch.Trim() }
    if (-not $b -or $b -eq 'HEAD') {
        $lines.Add("AVISO: o checkout nao esta num branch (detached ou sem git); troque <branch> pelo branch de publicacao.")
        $b = '<branch>'
    }
    if ($GradleDirty) {
        $lines.Add("Commite SO o build.gradle.kts (nunca git add -A: a arvore tem trabalho de outras sessoes):")
        $lines.Add("  git add lemuroid-app/build.gradle.kts")
        $lines.Add("  git commit -m `"chore(versao): publicado/$VersionName`"")
    }
    if (-not $TagExists) {
        $lines.Add("  git tag $tag")
    }
    $lines.Add("  git push origin $b $tag")
    return [PSCustomObject]@{ Pending = $true; Lines = [string[]]$lines.ToArray() }
}
