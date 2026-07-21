param(
    [string]$Task = ":lemuroid-app:assembleFreeBundleDebug",
    [string]$Serial = "",
    [string]$PackageName = "app.retrogamesystem.debug",
    [switch]$LaunchAfterInstall
)

$ErrorActionPreference = "Stop"

$repoRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradleWrapper = Join-Path $repoRoot "gradlew.bat"
$apkOutputDir = Join-Path $repoRoot "lemuroid-app\build\outputs\apk"

function Get-LocalProperty {
    param(
        [string]$FilePath,
        [string]$Key
    )

    if (-not (Test-Path $FilePath)) {
        return ""
    }

    $escapedKey = [regex]::Escape($Key)
    foreach ($line in Get-Content -Path $FilePath) {
        if ($line -match "^\s*$escapedKey\s*=\s*(.*)$") {
            return $matches[1].Trim()
        }
    }

    return ""
}

function Unescape-LocalPropertiesValue {
    param([string]$Value)

    $sb = New-Object System.Text.StringBuilder
    for ($i = 0; $i -lt $Value.Length; $i++) {
        $c = $Value[$i]
        if ($c -eq '\' -and $i + 1 -lt $Value.Length) {
            $next = $Value[$i + 1]
            if ($next -eq '\' -or $next -eq ':' -or $next -eq '=' -or $next -eq ' ') {
                [void]$sb.Append($next)
                $i++
                continue
            }
        }
        [void]$sb.Append($c)
    }

    return $sb.ToString()
}

function Resolve-JavaHome {
    # 1) Already set in environment
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME) -and (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
        return $env:JAVA_HOME
    }

    # 2) org.gradle.java.home in gradle.properties
    $gradlePropsPath = Join-Path $repoRoot "gradle.properties"
    $javaHomeRaw = Get-LocalProperty -FilePath $gradlePropsPath -Key "org.gradle.java.home"
    if (-not [string]::IsNullOrWhiteSpace($javaHomeRaw)) {
        $javaHome = Unescape-LocalPropertiesValue -Value $javaHomeRaw
        if (Test-Path (Join-Path $javaHome "bin\java.exe")) {
            return $javaHome
        }
    }

    # 3) Android Studio bundled JBR (common locations)
    $candidates = @(
        "C:\Android\Android Studio\jbr",
        "C:\Program Files\Android\Android Studio\jbr"
    )
    foreach ($c in $candidates) {
        if (Test-Path (Join-Path $c "bin\java.exe")) {
            return $c
        }
    }

    # 4) java.exe in PATH
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCmd) {
        return Split-Path -Parent (Split-Path -Parent $javaCmd.Source)
    }

    throw "Java nao encontrado. Defina JAVA_HOME ou configure org.gradle.java.home em gradle.properties."
}

function Resolve-AdbPath {
    # 1) PATH
    $adbInPath = Get-Command adb -ErrorAction SilentlyContinue
    if ($adbInPath) {
        return $adbInPath.Source
    }

    # 2) ANDROID_SDK_ROOT / ANDROID_HOME
    $sdkFromEnv = if (-not [string]::IsNullOrWhiteSpace($env:ANDROID_SDK_ROOT)) {
        $env:ANDROID_SDK_ROOT
    } elseif (-not [string]::IsNullOrWhiteSpace($env:ANDROID_HOME)) {
        $env:ANDROID_HOME
    } else {
        ""
    }

    if (-not [string]::IsNullOrWhiteSpace($sdkFromEnv)) {
        $candidate = Join-Path $sdkFromEnv "platform-tools\adb.exe"
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    # 3) local.properties (sdk.dir)
    $localPropsPath = Join-Path $repoRoot "local.properties"
    $sdkDirRaw = Get-LocalProperty -FilePath $localPropsPath -Key "sdk.dir"
    if (-not [string]::IsNullOrWhiteSpace($sdkDirRaw)) {
        $sdkDir = Unescape-LocalPropertiesValue -Value $sdkDirRaw
        $candidate = Join-Path $sdkDir "platform-tools\adb.exe"
        if (Test-Path $candidate) {
            return $candidate
        }
    }

    throw "ADB nao encontrado no PATH, variaveis ANDROID_SDK_ROOT/ANDROID_HOME, nem em local.properties (sdk.dir)."
}

function Get-ConnectedDevices {
    param(
        [string]$adbExe,
        [string[]]$adbArgs
    )

    $raw = & $adbExe @adbArgs devices
    if ($LASTEXITCODE -ne 0) {
        throw "Falha ao executar 'adb devices'."
    }

    return @(
        $raw |
            Select-Object -Skip 1 |
            Where-Object { $_ -match "^\s*\S+\s+device(\s|$)" } |
            ForEach-Object {
                if ($_ -match "^\s*(\S+)\s+device(\s|$)") {
                    $matches[1]
                }
            }
    )
}

if (-not (Test-Path $gradleWrapper)) {
    throw "Gradle wrapper nao encontrado em $gradleWrapper"
}

$adbExe = Resolve-AdbPath

Write-Host "Iniciando ADB server..."
& $adbExe start-server | Out-Null
if ($LASTEXITCODE -ne 0) {
    throw "Nao foi possivel iniciar o ADB server."
}

$devices = @(Get-ConnectedDevices -adbExe $adbExe)
if ($devices.Count -eq 0) {
    throw "Nenhum dispositivo conectado em estado 'device'. Verifique cabo USB e depuracao USB."
}

if ([string]::IsNullOrWhiteSpace($Serial) -and $devices.Count -gt 1) {
    throw "Mais de um dispositivo conectado. Use -Serial para escolher um: $($devices -join ', ')"
}

if (-not [string]::IsNullOrWhiteSpace($Serial) -and -not ($devices -contains $Serial)) {
    throw "Serial '$Serial' nao encontrado entre dispositivos conectados: $($devices -join ', ')"
}

$selectedSerial = if ([string]::IsNullOrWhiteSpace($Serial)) { $devices[0] } else { $Serial }
$adbArgs = @("-s", $selectedSerial)

Write-Host "Dispositivo selecionado: $selectedSerial"

# Ensure JAVA_HOME is set so gradlew.bat can bootstrap before reading gradle.properties
$resolvedJavaHome = Resolve-JavaHome
if ($env:JAVA_HOME -ne $resolvedJavaHome) {
    Write-Host "Definindo JAVA_HOME: $resolvedJavaHome"
    $env:JAVA_HOME = $resolvedJavaHome
}

Write-Host "Executando build: $Task"
& $gradleWrapper $Task
if ($LASTEXITCODE -ne 0) {
    throw "Build falhou com codigo $LASTEXITCODE"
}

# Get device's supported ABIs to pick the right split APK
$deviceAbisRaw = & $adbExe @adbArgs shell getprop ro.product.cpu.abilist
$deviceAbis = if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($deviceAbisRaw)) {
    @($deviceAbisRaw.Trim().Split(',')) | ForEach-Object { $_.Trim() }
} else {
    @()
}

Write-Host "Device supported ABIs: $($deviceAbis -join ', ')"

$apkFile = $null
foreach ($abi in $deviceAbis) {
    $matchedApk = Get-ChildItem -Path $apkOutputDir -Recurse -Filter "*$abi*.apk" |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
    if ($matchedApk) {
        $apkFile = $matchedApk
        break
    }
}

if (-not $apkFile) {
    Write-Host "No APK matching device ABI found. Falling back to most recently modified APK."
    $apkFile = Get-ChildItem -Path $apkOutputDir -Recurse -Filter "*.apk" |
        Sort-Object LastWriteTimeUtc -Descending |
        Select-Object -First 1
}

if (-not $apkFile) {
    throw "Nenhum APK foi encontrado em $apkOutputDir"
}

Write-Host "Instalando APK: $($apkFile.FullName)"
& $adbExe @adbArgs install -r -d -t "$($apkFile.FullName)"
if ($LASTEXITCODE -ne 0) {
    throw "Falha ao instalar APK no dispositivo $selectedSerial"
}

Write-Host "Instalacao concluida com sucesso no dispositivo $selectedSerial"

if ($LaunchAfterInstall) {
    Write-Host "Abrindo app: $PackageName"
    & $adbExe @adbArgs shell monkey -p $PackageName -c android.intent.category.LAUNCHER 1 | Out-Null
    if ($LASTEXITCODE -ne 0) {
        throw "APK instalado, mas falhou ao abrir app com pacote '$PackageName'."
    }
    Write-Host "App aberto com sucesso."
}
