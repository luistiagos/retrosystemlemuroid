<#
.SYNOPSIS
rclone falso do harness de publicacao (tests/test-publicacao-anuncio.ps1). Nunca fala com a rede.

.DESCRIPTION
O harness copia este arquivo para <raiz>\bin\rclone.ps1 e poe <raiz>\bin no inicio do PATH do
processo filho que roda o build-and-upload.ps1. O Find-Rclone do script usa Get-Command rclone,
que acha este .ps1 antes do rclone.exe real; o Invoke-R2Rclone o chama com "& $exe <args>", entao
ele roda DENTRO do processo do script e devolve o resultado por $LASTEXITCODE (exit N).

Emula, sobre uma pasta local, so o que o script usa:
  copy     <arquivo local> <pasta remota>   grava com o nome do arquivo
  copyto   <origem> <destino>               origem local ou remota (copia dentro do bucket)
  lsjson   <pasta remota>                   JSON com Path, Name, Size, IsDir, ModTime (nao recursivo)
  deletefile <arquivo remoto>
Remoto = ":s3:<bucket>/<caminho>" -> <FAKE_RCLONE_BUCKET_DIR>\<caminho>. Flags "--..." sao ignoradas.

Ambiente (o harness define):
  FAKE_RCLONE_BUCKET_DIR  pasta que faz o papel do bucket (obrigatoria)
  FAKE_RCLONE_LOG         arquivo onde cada chamada e registrada (obrigatoria)
  FAKE_RCLONE_BUCKET      nome do bucket aceito (padrao: fakebucket); outro nome = erro
  FAKE_RCLONE_FALHAR_EM   se algum argumento contiver este texto, sai com 1 SEM escrever
                          (simula rede/credencial caindo naquela chamada)

Sem param() de proposito: os "--flags" do script chegam em $args como texto.
Demanda: 2026-11-20-publicacao-sobrescreve-apk-antes-do-anuncio.
#>

$fakeBucketDir = $env:FAKE_RCLONE_BUCKET_DIR
$fakeLog = $env:FAKE_RCLONE_LOG
$fakeBucketName = if ($env:FAKE_RCLONE_BUCKET) { $env:FAKE_RCLONE_BUCKET } else { 'fakebucket' }
$fakeArgs = @($args | ForEach-Object { [string]$_ })

function Write-FakeRcloneLog([string]$Resultado) {
    if ($fakeLog) {
        $linha = "{0}`t{1}`t{2}" -f (Get-Date -Format 'HH:mm:ss.fff'), $Resultado, ($fakeArgs -join ' | ')
        Add-Content -LiteralPath $fakeLog -Value $linha -Encoding UTF8
    }
}

function Exit-FakeRclone([int]$Codigo, [string]$Resultado, [string]$Mensagem) {
    Write-FakeRcloneLog $Resultado
    if ($Mensagem) { [Console]::Error.WriteLine("fake-rclone: $Mensagem") }
    exit $Codigo
}

if (-not $fakeBucketDir -or -not $fakeLog) {
    [Console]::Error.WriteLine('fake-rclone: FAKE_RCLONE_BUCKET_DIR e FAKE_RCLONE_LOG sao obrigatorias')
    exit 2
}

# Remoto -> caminho local; $null se nao for remoto. Bucket diferente do esperado = erro (nao e o falso).
function ConvertTo-FakeLocalPath([string]$Caminho) {
    if ($Caminho -notmatch '^:s3:([^/]+)(/(.*))?$') { return $null }
    if ($matches[1] -ne $fakeBucketName) {
        Exit-FakeRclone 2 'ERRO' "bucket '$($matches[1])' nao e o bucket falso '$fakeBucketName'"
    }
    $resto = [string]$matches[3]
    $local = $fakeBucketDir
    foreach ($parte in ($resto -split '/' | Where-Object { $_ -ne '' })) {
        $local = Join-Path $local $parte
    }
    return $local
}

function Copy-FakeFile([string]$Origem, [string]$Destino) {
    if (-not (Test-Path -LiteralPath $Origem -PathType Leaf)) {
        Exit-FakeRclone 1 'ERRO' "origem nao existe: $Origem"
    }
    $pai = Split-Path -Parent $Destino
    if (-not (Test-Path -LiteralPath $pai)) { New-Item -ItemType Directory -Force -Path $pai | Out-Null }
    # Escreve ao lado e troca: o objeto aparece inteiro ou nao aparece, como no S3.
    $tmp = "$Destino.fake-rclone-tmp"
    Copy-Item -LiteralPath $Origem -Destination $tmp -Force
    Move-Item -LiteralPath $tmp -Destination $Destino -Force
}

$falharEm = $env:FAKE_RCLONE_FALHAR_EM
if ($falharEm) {
    foreach ($a in $fakeArgs) {
        if ($a.Contains($falharEm)) {
            Exit-FakeRclone 1 'FALHA-INJETADA' "falha injetada (FAKE_RCLONE_FALHAR_EM=$falharEm)"
        }
    }
}

$posicionais = @($fakeArgs | Where-Object { -not $_.StartsWith('--') })
if ($posicionais.Count -eq 0) { Exit-FakeRclone 2 'ERRO' 'sem subcomando' }
$sub = $posicionais[0]
$resto = @($posicionais | Select-Object -Skip 1)

switch ($sub) {
    'copy' {
        if ($resto.Count -ne 2) { Exit-FakeRclone 2 'ERRO' 'copy espera <origem> <pasta destino>' }
        $pasta = ConvertTo-FakeLocalPath $resto[1]
        if (-not $pasta) { Exit-FakeRclone 2 'ERRO' "copy: destino nao e remoto: $($resto[1])" }
        Copy-FakeFile $resto[0] (Join-Path $pasta (Split-Path -Leaf $resto[0]))
    }
    'copyto' {
        if ($resto.Count -ne 2) { Exit-FakeRclone 2 'ERRO' 'copyto espera <origem> <destino>' }
        $origem = ConvertTo-FakeLocalPath $resto[0]
        if (-not $origem) { $origem = $resto[0] }
        $destino = ConvertTo-FakeLocalPath $resto[1]
        if (-not $destino) { Exit-FakeRclone 2 'ERRO' "copyto: destino nao e remoto: $($resto[1])" }
        Copy-FakeFile $origem $destino
    }
    'lsjson' {
        if ($resto.Count -ne 1) { Exit-FakeRclone 2 'ERRO' 'lsjson espera <pasta>' }
        $pasta = ConvertTo-FakeLocalPath $resto[0]
        if (-not $pasta) { Exit-FakeRclone 2 'ERRO' "lsjson: nao e remoto: $($resto[0])" }
        $itens = @()
        # Prefixo inexistente no S3 lista vazio, nao da erro.
        if (Test-Path -LiteralPath $pasta -PathType Container) {
            foreach ($i in (Get-ChildItem -LiteralPath $pasta | Where-Object { $_.Name -notlike '*.fake-rclone-tmp' })) {
                $itens += [ordered]@{
                    Path    = $i.Name
                    Name    = $i.Name
                    Size    = if ($i.PSIsContainer) { -1 } else { [long]$i.Length }
                    ModTime = $i.LastWriteTimeUtc.ToString('o')
                    IsDir   = [bool]$i.PSIsContainer
                }
            }
        }
        Write-Output (ConvertTo-Json -InputObject @($itens) -Depth 3 -Compress)
    }
    'deletefile' {
        if ($resto.Count -ne 1) { Exit-FakeRclone 2 'ERRO' 'deletefile espera <arquivo>' }
        $alvo = ConvertTo-FakeLocalPath $resto[0]
        if (-not $alvo) { Exit-FakeRclone 2 'ERRO' "deletefile: nao e remoto: $($resto[0])" }
        if (-not (Test-Path -LiteralPath $alvo -PathType Leaf)) { Exit-FakeRclone 1 'ERRO' "nao existe: $($resto[0])" }
        Remove-Item -LiteralPath $alvo -Force
    }
    default {
        Exit-FakeRclone 2 'ERRO' "subcomando nao emulado: $sub"
    }
}

Exit-FakeRclone 0 'OK' $null
