# Helpers de ambiente compartilhados pelos scripts de build.
# Dot-source: . (Join-Path $PSScriptRoot "build-env.ps1")

function Initialize-BuildEnv {
    <#
    .SYNOPSIS
    Recupera JAVA_HOME / GRADLE_USER_HOME / ANDROID_HOME do escopo persistente.

    .DESCRIPTION
    Variaveis gravadas no escopo User (setx, painel de variaveis de ambiente) so entram
    em processos criados DEPOIS da gravacao. Um terminal que ja estava aberto na hora
    fica sem elas para sempre — e foi exatamente assim que o build quebrou com
    "JAVA_HOME is not set" numa maquina onde JAVA_HOME estava configurado.

    Falhar por isso e caro aqui: o gradlew.bat morre antes de ler qualquer
    gradle.properties (por isso org.gradle.java.home sozinho nao resolve), e sem
    GRADLE_USER_HOME o gradle.properties do projeto manda o build para E:/.gradle e
    E:/gradle_tmp — caminhos da maquina de build original que nao existem em outras.

    Só preenche o que estiver vazio no processo, entao e no-op quando o terminal ja
    herdou as variaveis (inclusive na maquina original, que nao define nenhuma delas
    no escopo User e depende do gradle.properties versionado).
    #>
    foreach ($name in @('JAVA_HOME', 'GRADLE_USER_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT')) {
        if (-not [string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable($name, 'Process'))) {
            continue
        }
        foreach ($scope in @('User', 'Machine')) {
            $value = [Environment]::GetEnvironmentVariable($name, $scope)
            if (-not [string]::IsNullOrWhiteSpace($value)) {
                Set-Item -Path "Env:$name" -Value $value
                Write-Host "  $name recuperado do escopo ${scope}: $value" -ForegroundColor DarkGray
                break
            }
        }
    }

    Update-PathFromPersistentScopes
}

function Update-PathFromPersistentScopes {
    <#
    .SYNOPSIS
    Acrescenta ao PATH da sessao as entradas persistentes (Machine/User) que faltam.

    .DESCRIPTION
    Mesma armadilha do JAVA_HOME, uma camada acima: um `pip install --user` acrescenta
    %APPDATA%\Python\PythonXY\Scripts ao PATH do usuario, mas terminais ja abertos nunca
    veem esse diretorio — e o `hf` do passo de upload simplesmente "nao existe", depois de
    o build ter levado minutos.

    Acrescenta, nunca reescreve: o que a sessao ja tem fica na frente e na mesma ordem,
    para nao desativar um venv ativo (que prepende o proprio Scripts) nem reordenar
    ferramentas que o usuario escolheu priorizar.
    #>
    $current = ($env:Path -split ';') | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    $persistent = @()
    foreach ($scope in @('Machine', 'User')) {
        $value = [Environment]::GetEnvironmentVariable('Path', $scope)
        if (-not [string]::IsNullOrWhiteSpace($value)) {
            $persistent += ($value -split ';') | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
        }
    }

    # Comparacao normalizada (case-insensitive, sem '\' final) para nao duplicar entradas.
    $seen = [System.Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
    foreach ($entry in $current) { [void]$seen.Add($entry.TrimEnd('\')) }

    $added = @()
    foreach ($entry in $persistent) {
        if ($seen.Add($entry.TrimEnd('\'))) { $added += $entry }
    }

    if ($added.Count -gt 0) {
        $env:Path = (@($current) + $added) -join ';'
        Write-Host "  PATH: +$($added.Count) entrada(s) do escopo persistente" -ForegroundColor DarkGray
        foreach ($entry in $added) { Write-Host "    $entry" -ForegroundColor DarkGray }
    }
}

function Find-HfCli {
    <#
    .SYNOPSIS
    Caminho do CLI `hf` (huggingface_hub), ou erro dizendo como instalar.

    .DESCRIPTION
    Espelha o Find-Rclone do build-and-upload.ps1: procura no PATH e, se nao achar, nos
    diretorios Scripts dos installs `pip --user`, que e onde o `hf` costuma cair sem
    entrar no PATH de terminais ja abertos.
    #>
    foreach ($name in @('hf', 'huggingface-cli')) {
        $cmd = Get-Command $name -ErrorAction SilentlyContinue
        if ($cmd) { return $cmd.Source }
    }

    $roots = @("$env:APPDATA\Python", "$env:LOCALAPPDATA\Programs\Python", "$env:LOCALAPPDATA\Python") |
        Where-Object { Test-Path $_ }
    foreach ($root in $roots) {
        $candidate = Get-ChildItem -Path $root -Include 'hf.exe', 'huggingface-cli.exe' -Recurse -ErrorAction SilentlyContinue |
            Sort-Object FullName | Select-Object -Last 1 -ExpandProperty FullName
        if ($candidate) { return $candidate }
    }

    throw "hf CLI nao encontrado. Instale com: pip install -U huggingface_hub (e reabra o terminal)."
}

function Resolve-JavaHome {
    <#
    .SYNOPSIS
    Devolve um JAVA_HOME utilizavel, ou lanca erro explicando o que configurar.

    .DESCRIPTION
    gradlew.bat precisa de JAVA_HOME (ou java no PATH) para subir a JVM *antes* de ler
    gradle.properties, entao org.gradle.java.home sozinho nunca basta. Mesma cascata
    usada por build_apk.ps1 / build_and_install_connected.ps1.
    #>
    param([string]$RepoRoot = $PSScriptRoot)

    # 1) Ja presente no ambiente (inclusive o que Initialize-BuildEnv acabou de recuperar).
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME) -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }
    # 2) org.gradle.java.home — primeiro o gradle.properties do usuario (tem precedencia
    #    sobre o do projeto), depois o do projeto.
    $candidateProps = @()
    if (-not [string]::IsNullOrWhiteSpace($env:GRADLE_USER_HOME)) {
        $candidateProps += (Join-Path $env:GRADLE_USER_HOME "gradle.properties")
    }
    $candidateProps += (Join-Path $HOME ".gradle\gradle.properties")
    $candidateProps += (Join-Path $RepoRoot "gradle.properties")
    foreach ($props in $candidateProps) {
        if (-not (Test-Path $props)) { continue }
        foreach ($line in Get-Content $props) {
            if ($line -match "^\s*org\.gradle\.java\.home\s*=\s*(.+)$") {
                # Java .properties escapa '\' e ':'; desfaz os casos comuns.
                $jh = $matches[1].Trim() -replace '\\\\', '\' -replace '\\:', ':'
                if (Test-Path (Join-Path $jh "bin\java.exe")) { return $jh }
            }
        }
    }
    # 3) JBR que vem com o Android Studio.
    foreach ($c in @("C:\Android\Android Studio\jbr", "C:\Program Files\Android\Android Studio\jbr")) {
        if (Test-Path (Join-Path $c "bin\java.exe")) { return $c }
    }
    # 4) java.exe ja no PATH.
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCmd) { return Split-Path -Parent (Split-Path -Parent $javaCmd.Source) }

    throw "Java nao encontrado. Defina JAVA_HOME (JDK 17) ou org.gradle.java.home em gradle.properties."
}

function Set-ResolvedJavaHome {
    param([string]$RepoRoot = $PSScriptRoot)

    $resolved = Resolve-JavaHome -RepoRoot $RepoRoot
    if ($env:JAVA_HOME -ne $resolved) {
        Write-Host "  JAVA_HOME: $resolved" -ForegroundColor DarkGray
        $env:JAVA_HOME = $resolved
    }
    return $resolved
}
