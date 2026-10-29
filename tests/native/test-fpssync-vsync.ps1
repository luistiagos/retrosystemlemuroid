param(
    [string]$SourceRoot = "$PSScriptRoot/../../../LibretroDroid-patched",
    [string]$Aar = "$PSScriptRoot/../../libs/libretrodroid-patched.aar",
    [string]$Sdk = 'D:/DevCaches/Android/Sdk',
    [string]$NdkVersion = '29.0.14206865',
    [ValidateSet('arm64-v8a', 'x86_64')]
    [string]$Abi = 'arm64-v8a',
    [string]$Serial = ''
)
$ErrorActionPreference = 'Stop'
$testOut = [IO.Path]::GetFullPath("$PSScriptRoot/../../tmp/fpssync-vsync-test")
New-Item -ItemType Directory -Force $testOut | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($Aar))
try {
    $entry = $archive.GetEntry("jni/$Abi/liblibretrodroid.so")
    if (-not $entry) { throw "AAR lacks $Abi library" }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, "$testOut/liblibretrodroid.so", $true)
} finally { $archive.Dispose() }
$target = @{ 'arm64-v8a' = 'aarch64-linux-android21'; 'x86_64' = 'x86_64-linux-android21' }[$Abi]
$compiler = "$Sdk/ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe"
$cpp = [IO.Path]::GetFullPath("$SourceRoot/libretrodroid/src/main/cpp")
# Links against the FPSSync shipped in the AAR, not the source: the test proves what goes into the APK.
& $compiler "--target=$target" -std=c++17 -static-libstdc++ "-I$cpp" "$PSScriptRoot/fpssync_vsync_test.cpp" "-L$testOut" -llibretrodroid -llog -o "$testOut/fpssync_test"
if ($LASTEXITCODE -ne 0) { throw 'Test executable compilation failed' }
$adb = "$Sdk/platform-tools/adb.exe"
$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }
$remote = '/data/local/tmp/fpssync-vsync-test'
& $adb @deviceArgs shell mkdir -p $remote
if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare device test directory' }
foreach ($file in @('liblibretrodroid.so', 'fpssync_test')) {
    & $adb @deviceArgs push "$testOut/$file" "$remote/$file"
    if ($LASTEXITCODE -ne 0) { throw "Cannot push $file" }
}
& $adb @deviceArgs shell chmod 755 "$remote/fpssync_test"
if ($LASTEXITCODE -ne 0) { throw 'Cannot mark test executable' }
& $adb @deviceArgs shell "LD_LIBRARY_PATH=$remote $remote/fpssync_test"
if ($LASTEXITCODE -ne 0) { throw "FPSSync regression test failed (exit $LASTEXITCODE)" }
