# devbuild.ps1 -- compile the project inside the sandbox.
#
# NOTE: this machine's `pwsh` is Windows PowerShell 5.1, which reads .ps1 files
# using the system ANSI codepage. Keep this file ASCII-only or it will not parse.
#
# Why not gradlew here: Gradle needs to write GRADLE_USER_HOME (D:\.gradle, which
# lives outside the workspace). This script instead pulls kotlin-compiler-embeddable
# and asm straight out of the Gradle module cache and compiles into
# build/dev-classes. Normal development should use gradlew.
param(
    [switch]$SkipCompile
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$cache = 'D:\.gradle\caches\modules-2\files-2.1'
$outDir = Join-Path $root 'build\dev-classes'

function Get-CachedJar {
    param([string]$Group, [string]$Pattern)
    $base = Join-Path $cache $Group
    if (-not (Test-Path $base)) { throw "cache dir missing: $base" }
    $hit = Get-ChildItem $base -Recurse -Filter $Pattern -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '(sources|javadoc)' } |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $hit) { throw "jar not found in cache: $Group / $Pattern" }
    return $hit.FullName
}

$compiler = Get-CachedJar 'org.jetbrains.kotlin' 'kotlin-compiler-embeddable-2.3.10.jar'
$stdlib = Get-CachedJar 'org.jetbrains.kotlin' 'kotlin-stdlib-2.3.10.jar'
$scriptRuntime = Get-CachedJar 'org.jetbrains.kotlin' 'kotlin-script-runtime-2.3.10.jar'
$reflect = Get-CachedJar 'org.jetbrains.kotlin' 'kotlin-reflect-2.*.jar'
$asm = Get-CachedJar 'org.ow2.asm' 'asm-9.9.1.jar'
$asmUtil = Get-CachedJar 'org.ow2.asm' 'asm-util-9.9.1.jar'
# K2JVMCompiler also needs coroutines + trove + jetbrains annotations on its own classpath
$coroutines = Get-CachedJar 'org.jetbrains.kotlinx' 'kotlinx-coroutines-core-jvm-1.9.0.jar'
$trove = Get-CachedJar 'org.jetbrains.intellij.deps' 'trove4j-*.jar'
$annotations = Get-CachedJar 'org.jetbrains' 'annotations-26.0.2.jar'

if (-not $SkipCompile) {
    if (Test-Path $outDir) { Remove-Item $outDir -Recurse -Force }
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null

    $sources = Get-ChildItem (Join-Path $root 'src\main\kotlin') -Recurse -Filter '*.kt' |
        Select-Object -ExpandProperty FullName
    if (-not $sources) { throw 'no .kt sources under src/main/kotlin' }

    $compilerCp = @($compiler, $stdlib, $scriptRuntime, $reflect, $coroutines, $trove, $annotations) -join ';'
    $libsCp = @($stdlib, $asm, $asmUtil) -join ';'

    # Pass sources through an argfile: avoids command-line length limits.
    $argFile = Join-Path $env:TEMP 'rsf-devbuild-args.txt'
    $compilerArgs = @(
        '-no-stdlib'
        '-nowarn'
        '-jvm-target', '21'
        '-classpath', $libsCp
        '-d', $outDir
    ) + $sources
    [System.IO.File]::WriteAllLines(
        $argFile, $compilerArgs, (New-Object System.Text.UTF8Encoding($false)))

    # Native stderr output becomes an ErrorRecord in PS 5.1, which would abort the
    # script under $ErrorActionPreference='Stop'. Relax it for this call and rely on
    # the exit code instead.
    $savedPref = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    & java -cp $compilerCp org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "@$argFile" 2>&1 |
        ForEach-Object { "$_" } | Write-Host
    $code = $LASTEXITCODE
    $ErrorActionPreference = $savedPref
    if ($code -ne 0) { throw "kotlin compile failed (exit $code)" }
    Write-Host "[devbuild] OK -> $outDir" -ForegroundColor Green
    Write-Host "[devbuild] libs: $libsCp"
}
