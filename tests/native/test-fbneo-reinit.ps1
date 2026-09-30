<#
.SYNOPSIS
Reproduces the FBNeo double free when one process runs retro_init after retro_deinit.
.DESCRIPTION
Runs fbneo_reinit_test.c twice against the bundled FBNeo core: one session per process
(control, must pass) and two sessions in the same process (must fail). No ROM is needed.

Scudo (Android 11+) aborts on the double free by itself. jemalloc (Android 5-10) may or may not
crash, so on those images pass -MallocDebug: it needs `adb root` and turns on malloc_debug for
this program only, which logs "+++ ALLOCATION ... (free)" on the second free.

The app-side guard is GameProcessSession: one core session per :game process.
.EXAMPLE
./tests/native/test-fbneo-reinit.ps1 -Serial RX8R90G1D6E
.EXAMPLE
./tests/native/test-fbneo-reinit.ps1 -Serial emulator-5554 -Abi x86_64 -MallocDebug
#>
param(
    [string]$Serial = '',
    [ValidateSet('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')][string]$Abi = 'arm64-v8a',
    [switch]$MallocDebug,
    [string]$Sdk = 'D:/DevCaches/Android/Sdk',
    [string]$NdkVersion = '29.0.14206865'
)
$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath("$PSScriptRoot/../..")
$testOut = Join-Path $repoRoot 'tmp/fbneo-reinit-test'
New-Item -ItemType Directory -Force $testOut | Out-Null
$adb = "$Sdk/platform-tools/adb.exe"
$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }
$remote = '/data/local/tmp/fbneo-reinit'
$program = 'fbneo_reinit_test'
$targets = @{
    'arm64-v8a'   = 'aarch64-linux-android21'
    'armeabi-v7a' = 'armv7a-linux-androideabi21'
    'x86'         = 'i686-linux-android21'
    'x86_64'      = 'x86_64-linux-android21'
}
$compiler = "$Sdk/ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe"
$core = Join-Path $repoRoot "lemuroid-cores/bundled-cores/src/main/jniLibs/$Abi/libfbneo_libretro_android.so"
if (-not (Test-Path -LiteralPath $core -PathType Leaf)) { throw "Missing core: $core" }

function Invoke-Adb {
    $ErrorActionPreference = 'Continue' # adb progress uses stderr even on success (PowerShell 5.1).
    & $adb @deviceArgs @args
    if ($LASTEXITCODE -ne 0) { throw "adb failed: $args" }
}

# Returns the exit code and whether malloc_debug flagged a bad free during the run.
function Invoke-Sessions([int]$sessions) {
    Invoke-Adb logcat -c
    $ErrorActionPreference = 'Continue'
    & $adb @deviceArgs shell "cd $remote && ./$program ./core.so $sessions 2>&1; echo EXIT=`$?" |
        Tee-Object -FilePath "$testOut/sessions-$sessions.log"
    $ErrorActionPreference = 'Stop'
    $log = Get-Content "$testOut/sessions-$sessions.log" -Raw
    $exit = if ($log -match 'EXIT=(\d+)') { [int]$Matches[1] } else { -1 }
    & $adb @deviceArgs logcat -d | Out-File -Encoding utf8 "$testOut/sessions-$sessions-logcat.txt"
    $flagged = Select-String -Path "$testOut/sessions-$sessions-logcat.txt" -Pattern '\+\+\+ ALLOCATION|Scudo ERROR|double free|passed to free' -Quiet
    return @{ Exit = $exit; Flagged = [bool]$flagged }
}

& $compiler "--target=$($targets[$Abi])" -std=gnu11 "$PSScriptRoot/fbneo_reinit_test.c" -ldl -o "$testOut/$program"
if ($LASTEXITCODE -ne 0) { throw 'Test harness compilation failed' }
Invoke-Adb shell mkdir -p $remote
Invoke-Adb push "$testOut/$program" "$remote/$program"
Invoke-Adb push $core "$remote/core.so"
Invoke-Adb shell chmod 755 "$remote/$program"

if ($MallocDebug) {
    Invoke-Adb root
    Invoke-Adb wait-for-device
    Invoke-Adb shell setprop libc.debug.malloc.program $program
    Invoke-Adb shell setprop libc.debug.malloc.options "'guard free_track'"
}
try {
    $single = Invoke-Sessions 1
    $double = Invoke-Sessions 2
} finally {
    if ($MallocDebug) {
        Invoke-Adb shell setprop libc.debug.malloc.options "''"
        Invoke-Adb shell setprop libc.debug.malloc.program "''"
    }
}

Write-Host "1 session : exit=$($single.Exit) flagged=$($single.Flagged)"
Write-Host "2 sessions: exit=$($double.Exit) flagged=$($double.Flagged)"
if ($single.Exit -ne 0 -or $single.Flagged) { throw 'Control run (one session per process) failed; inspect the logs' }
if ($double.Exit -eq 0 -and -not $double.Flagged) {
    throw 'Second retro_init did not fail. On jemalloc images, run again with -MallocDebug.'
}
Write-Host "Reproduced: FBNeo cannot run a second session in the same process. Logs: $testOut"
