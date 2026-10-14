# rundev.ps1 -- run the rsf CLI from build/dev-classes (sandbox dev loop).
# ASCII-only on purpose: Windows PowerShell 5.1 reads .ps1 with the ANSI codepage.
# Usage: .\tools\rundev.ps1 run lang\main.rsf
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rest
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$outDir = Join-Path $root 'build\dev-classes'
$cache = 'D:\.gradle\caches\modules-2\files-2.1'

function Get-CachedJar {
    param([string]$Group, [string]$Pattern)
    $hit = Get-ChildItem (Join-Path $cache $Group) -Recurse -Filter $Pattern -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '(sources|javadoc)' } |
        Sort-Object FullName -Descending | Select-Object -First 1
    if (-not $hit) { throw "jar not found in cache: $Group / $Pattern" }
    return $hit.FullName
}

$stdlib = Get-CachedJar 'org.jetbrains.kotlin' 'kotlin-stdlib-2.3.10.jar'
$asm = Get-CachedJar 'org.ow2.asm' 'asm-9.9.1.jar'
$asmUtil = Get-CachedJar 'org.ow2.asm' 'asm-util-9.9.1.jar'

$cp = @($outDir, $stdlib, $asm, $asmUtil) -join ';'
& java -Dfile.encoding=UTF-8 -cp $cp org.example.MainKt @Rest
exit $LASTEXITCODE
