<#
.SYNOPSIS
Checks FBNeo save/load across fresh Android processes; optionally reproduces the old-state SIGSEGV.
.DESCRIPTION
Requires locally supplied ROMs/BIOS and the bundled current core. Does not download games,
change app data, or replace packaged cores. JVM/app tests verify the frontend rejection gate.
.EXAMPLE
./tests/native/test-fbneo-state-compatibility.ps1 -RomDirectory ./tmp/test_roms -Serial RX8R90G1D6E
.EXAMPLE
./tests/native/test-fbneo-state-compatibility.ps1 -RomDirectory ./tmp/test_roms -OldCore ./tmp/fbneo-recurrence/fbneo-r28c.so -ReproduceLegacyCrash -Games rbffspec
#>
param(
    [Parameter(Mandatory = $true)][string]$RomDirectory,
    [string]$Serial = '',
    [string[]]$Games = @('rbffspec', 'kof97', 'msh'),
    [string]$OldCore = '',
    [switch]$ReproduceLegacyCrash,
    [int]$Frames = 3600,
    [string]$Sdk = 'D:/DevCaches/Android/Sdk',
    [string]$NdkVersion = '29.0.14206865',
    [string]$SourceRoot = "$PSScriptRoot/../../../LibretroDroid-patched"
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath("$PSScriptRoot/../..")
$testOut = Join-Path $repoRoot 'tmp/fbneo-state-test'
New-Item -ItemType Directory -Force $testOut | Out-Null
$adb = "$Sdk/platform-tools/adb.exe"
$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }
$remote = '/data/local/tmp/fbneo-recurrence'
$compiler = "$Sdk/ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
$headers = [IO.Path]::GetFullPath("$SourceRoot/libretrodroid/src/main/cpp/libretro/libretro-common/include")
$current = Join-Path $repoRoot 'lemuroid-cores/bundled-cores/src/main/jniLibs/arm64-v8a/libfbneo_libretro_android.so'
if ($Frames -le 0) { throw 'Frames must be positive' }
foreach ($game in $Games) {
    if ($game -notmatch '^[a-zA-Z0-9_]+$') { throw "Invalid ROM basename: $game" }
    if (-not (Test-Path -LiteralPath "$RomDirectory/$game.zip")) { throw "Missing ROM: $game.zip" }
}
if ($ReproduceLegacyCrash -and -not (Test-Path -LiteralPath $OldCore -PathType Leaf)) {
    throw 'Supply -OldCore with the historical FBNeo binary to reproduce the crash'
}
function Invoke-Adb {
    $ErrorActionPreference = 'Continue' # adb progress uses stderr even on success (PowerShell 5.1).
    & $adb @deviceArgs @args
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $args" }
}
& $compiler --target=aarch64-linux-android21 -std=gnu11 "-I$headers" "$PSScriptRoot/fbneo_state_compatibility_test.c" -ldl -o "$testOut/state_harness"
if ($LASTEXITCODE -ne 0) { throw 'Test harness compilation failed' }
Invoke-Adb shell mkdir -p $remote
Invoke-Adb push "$testOut/state_harness" "$remote/state_harness"
Invoke-Adb push $current "$remote/current.so"
Invoke-Adb shell chmod 755 "$remote/state_harness"
if (Test-Path -LiteralPath "$RomDirectory/neogeo.zip") {
    Invoke-Adb push "$RomDirectory/neogeo.zip" "$remote/neogeo.zip"
}
if ($ReproduceLegacyCrash) { Invoke-Adb push $OldCore "$remote/old.so" }
foreach ($game in $Games) {
    Invoke-Adb push "$RomDirectory/$game.zip" "$remote/$game.zip"
    foreach ($mode in @('save', 'load')) {
        & $adb @deviceArgs shell "FBNEO_TEST_FRAMES=$Frames $remote/state_harness $remote/current.so $remote/$game.zip $mode $remote/$game-current.state" |
            Tee-Object -FilePath "$testOut/$game-current-$mode.log"
        if ($LASTEXITCODE -ne 0) { throw "Current-core $mode failed for $game" }
    }
    if ($ReproduceLegacyCrash) {
        Invoke-Adb shell "$remote/state_harness $remote/old.so $remote/$game.zip save $remote/$game-old.state"
        $ErrorActionPreference = 'Continue'
        & $adb @deviceArgs shell "$remote/state_harness $remote/current.so $remote/$game.zip load $remote/$game-old.state" |
            Tee-Object -FilePath "$testOut/$game-legacy-load.log"
        $legacyExit = $LASTEXITCODE
        $ErrorActionPreference = 'Stop'
        & $adb @deviceArgs logcat -b crash -d -t 120 | Out-File -Encoding utf8 "$testOut/$game-legacy-crash.txt"
        if ($legacyExit -eq 0) { throw "No legacy-state crash reproduced for $game; inspect the log" }
        Write-Host "Legacy state failed in isolated process (exit $legacyExit); inspect tombstone before attributing cause."
    }
}
Write-Host "FBNeo current-core save/load checks passed. Logs: $testOut"
