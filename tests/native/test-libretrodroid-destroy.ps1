param(
    [string]$SourceRoot = "$PSScriptRoot/../../../LibretroDroid-patched",
    [string]$Aar = "$PSScriptRoot/../../libs/libretrodroid-patched.aar",
    [string]$Sdk = 'D:/DevCaches/Android/Sdk',
    [string]$NdkVersion = '29.0.14206865',
    [string]$Serial = ''
)
$ErrorActionPreference = 'Stop'
$testOut = [IO.Path]::GetFullPath("$PSScriptRoot/../../tmp/libretrodroid-destroy-test")
New-Item -ItemType Directory -Force $testOut | Out-Null
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead([IO.Path]::GetFullPath($Aar))
try {
    $entry = $archive.GetEntry('jni/arm64-v8a/liblibretrodroid.so')
    if (-not $entry) { throw 'AAR lacks arm64-v8a library' }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, "$testOut/liblibretrodroid.so", $true)
} finally { $archive.Dispose() }
$compiler = "$Sdk/ndk/$NdkVersion/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe"
$cpp = [IO.Path]::GetFullPath("$SourceRoot/libretrodroid/src/main/cpp")
$test = "$PSScriptRoot/libretrodroid_destroy_test.cpp"
$flags = @('--target=aarch64-linux-android21', '-std=c++17', '-UNDEBUG')
& $compiler @flags -DTEST_CORE -static-libstdc++ -shared -fPIC "-I$cpp/libretro/libretro-common/include" $test -o "$testOut/destroy_test_core.so"
if ($LASTEXITCODE -ne 0) { throw 'Test core compilation failed' }
& $compiler @flags -static-libstdc++ "-I$cpp" "-I$cpp/oboe/include" "-I$cpp/libretro/libretro-common/include" $test "-L$testOut" -llibretrodroid -ldl -llog -o "$testOut/destroy_test"
if ($LASTEXITCODE -ne 0) { throw 'Test executable compilation failed' }
$adb = "$Sdk/platform-tools/adb.exe"
$deviceArgs = @()
if ($Serial) { $deviceArgs = @('-s', $Serial) }
$remote = '/data/local/tmp/libretrodroid-destroy-test'
& $adb @deviceArgs shell mkdir -p $remote
if ($LASTEXITCODE -ne 0) { throw 'Cannot prepare device test directory' }
foreach ($file in @('liblibretrodroid.so', 'destroy_test_core.so', 'destroy_test')) {
    & $adb @deviceArgs push "$testOut/$file" "$remote/$file"
    if ($LASTEXITCODE -ne 0) { throw "Cannot push $file" }
}
& $adb @deviceArgs shell chmod 755 "$remote/destroy_test"
if ($LASTEXITCODE -ne 0) { throw 'Cannot mark test executable' }
& $adb @deviceArgs shell "LD_LIBRARY_PATH=$remote $remote/destroy_test $remote/destroy_test_core.so"
if ($LASTEXITCODE -ne 0) { throw "Native regression test failed (exit $LASTEXITCODE)" }
