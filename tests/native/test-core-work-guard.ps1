<#
.SYNOPSIS
Runs the JVM lifecycle regression tests against CoreWorkGuard inside the final AAR.
.DESCRIPTION
Uses only the local JDK and Gradle dependency cache; no Gradle build, downloads,
Android device, ROMs, or external LibretroDroid checkout are needed.
GradleCache points to the caches directory under GRADLE_USER_HOME.
Requires cached Kotlin 1.9.24 compiler dependencies and JUnit 4.13.2.
.EXAMPLE
./tests/native/test-core-work-guard.ps1
.EXAMPLE
./tests/native/test-core-work-guard.ps1 -Aar ./libs/libretrodroid-patched.aar -JavaHome D:/jdk-17 -GradleCache D:/.gradle/caches
#>
param(
    [string]$Aar = "$PSScriptRoot/../../libs/libretrodroid-patched.aar",
    [string]$JavaHome = '',
    [string]$GradleCache = ''
)

$ErrorActionPreference = 'Stop'
$repoRoot = [IO.Path]::GetFullPath("$PSScriptRoot/../..")
. (Join-Path $repoRoot 'build-env.ps1')
Initialize-BuildEnv
if ([string]::IsNullOrWhiteSpace($JavaHome)) {
    $JavaHome = Resolve-JavaHome -RepoRoot $repoRoot
}
if ([string]::IsNullOrWhiteSpace($GradleCache)) {
    $gradleUserHome = $env:GRADLE_USER_HOME
    if ([string]::IsNullOrWhiteSpace($gradleUserHome)) {
        $gradleUserHome = Join-Path ([Environment]::GetFolderPath('UserProfile')) '.gradle'
    }
    $GradleCache = Join-Path $gradleUserHome 'caches'
}

function Resolve-ExistingPath([string]$Path, [string]$Description, [string]$PathType) {
    if (-not (Test-Path -LiteralPath $Path -PathType $PathType)) {
        throw "$Description not found: $Path"
    }
    return (Resolve-Path -LiteralPath $Path).ProviderPath
}

$aarPath = Resolve-ExistingPath $Aar 'AAR' 'Leaf'
$java = Resolve-ExistingPath (Join-Path $JavaHome 'bin/java.exe') 'JDK java.exe; configure -JavaHome' 'Leaf'
$javap = Resolve-ExistingPath (Join-Path $JavaHome 'bin/javap.exe') 'JDK javap.exe; configure -JavaHome' 'Leaf'
$moduleCache = Resolve-ExistingPath (Join-Path $GradleCache 'modules-2/files-2.1') 'Gradle dependency cache; configure -GradleCache' 'Container'
$testSource = Resolve-ExistingPath "$PSScriptRoot/CoreWorkGuardTest.kt" 'JUnit test source' 'Leaf'

function Find-CachedJar([string]$Group, [string]$Artifact, [string]$Version) {
    $artifactPath = Join-Path $moduleCache "$Group/$Artifact/$Version"
    $jar = $null
    if (Test-Path -LiteralPath $artifactPath -PathType Container) {
        $jar = Get-ChildItem -LiteralPath $artifactPath -Filter "$Artifact-$Version.jar" -File -Recurse |
            Sort-Object FullName | Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $jar) {
        throw "Missing cached dependency ${Group}:${Artifact}:${Version} under $moduleCache. Configure -GradleCache with a populated cache; this runner does not download dependencies."
    }
    return $jar
}

$compilerJar = Find-CachedJar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable' '1.9.24'
$stdlibJar = Find-CachedJar 'org.jetbrains.kotlin' 'kotlin-stdlib' '1.9.24'
$scriptJar = Find-CachedJar 'org.jetbrains.kotlin' 'kotlin-script-runtime' '1.9.24'
$reflectJar = Find-CachedJar 'org.jetbrains.kotlin' 'kotlin-reflect' '1.6.10'
$troveJar = Find-CachedJar 'org.jetbrains.intellij.deps' 'trove4j' '1.0.20200330'
$annotationsJar = Find-CachedJar 'org.jetbrains' 'annotations' '13.0'
$junitJar = Find-CachedJar 'junit' 'junit' '4.13.2'
$hamcrestJar = Find-CachedJar 'org.hamcrest' 'hamcrest-core' '1.3'

$testOut = Join-Path $repoRoot 'tmp/core-work-guard-test'
New-Item -ItemType Directory -Force -Path $testOut | Out-Null
$classesJar = Join-Path $testOut 'classes.jar'
$testsJar = Join-Path $testOut 'tests.jar'
if ($aarPath -eq $classesJar -or $aarPath -eq $testsJar) {
    throw "The input AAR must be outside the runner's output files: $testOut"
}

Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($aarPath)
try {
    $entry = $archive.GetEntry('classes.jar')
    if (-not $entry) { throw "AAR lacks classes.jar: $aarPath" }
    [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $classesJar, $true)
} finally { $archive.Dispose() }

$classes = [IO.Compression.ZipFile]::OpenRead($classesJar)
try {
    if (-not $classes.GetEntry('com/swordfish/libretrodroid/CoreWorkGuard.class')) {
        throw "AAR lacks CoreWorkGuard.class; the lifecycle fix is not packaged: $aarPath"
    }
} finally { $classes.Dispose() }

$aarHash = (Get-FileHash -LiteralPath $aarPath -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "AAR: $aarPath"
Write-Host "SHA-256: $aarHash"
Write-Host "JDK: $java"
Write-Host "Artifacts: $testOut"
"$aarHash  $aarPath" | Set-Content -LiteralPath "$testOut/aar-sha256.txt" -Encoding UTF8

$compilerClasspath = @($compilerJar, $stdlibJar, $scriptJar, $reflectJar, $troveJar, $annotationsJar) -join ';'
$testClasspath = @($classesJar, $stdlibJar, $junitJar, $hamcrestJar) -join ';'
& $java -cp $compilerClasspath org.jetbrains.kotlin.cli.jvm.K2JVMCompiler `
    -no-stdlib -no-reflect -jvm-target 1.8 "-Xfriend-paths=$classesJar" `
    -classpath $testClasspath -d $testsJar $testSource
if ($LASTEXITCODE -ne 0) { throw "Kotlin test compilation failed (exit $LASTEXITCODE)" }

& $java -cp "$testsJar;$testClasspath" org.junit.runner.JUnitCore `
    com.swordfish.libretrodroid.CoreWorkGuardTest |
    Tee-Object -FilePath "$testOut/junit-result.txt"
if ($LASTEXITCODE -ne 0) { throw "CoreWorkGuard regression tests failed (exit $LASTEXITCODE)" }

# Keep the frame bytecode for manual review; the regression tests assert behavior.
& $javap -classpath $classesJar -p -c 'com.swordfish.libretrodroid.GLRetroView$Renderer$onDrawFrame$1' |
    Out-File -Encoding UTF8 "$testOut/draw-frame.javap.txt"
if ($LASTEXITCODE -ne 0) { throw "Renderer bytecode inspection failed (exit $LASTEXITCODE)" }
Write-Host 'CoreWorkGuard regression tests passed against the packaged AAR.'
