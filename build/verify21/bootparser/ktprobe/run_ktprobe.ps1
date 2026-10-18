# 阶段 20 自举同步第 1 刀 · 旁证：同一份探针源，rsf 版 dump 与 Kotlin 版 dump 逐字节比。
# 只读探针源、只写 build/verify21/bootparser/dumps/**。用法：pwsh -File run_ktprobe.ps1
$ErrorActionPreference = 'Stop'
Set-Location 'D:\java\rainStarfox'
$root = 'build\verify21\bootparser'
$jar = 'build\libs\rainStarfox-0.9.0.jar'
$tcls = 'build\classes\kotlin\test'
$cpMain = "$root\out"
$cpTest = "$root\ktprobe\classes;$jar;$tcls"

New-Item -ItemType Directory -Force -Path "$root\ktprobe\classes" | Out-Null
New-Item -ItemType Directory -Force -Path "$root\dumps" | Out-Null

& javac -encoding UTF-8 -cp "$jar;$tcls" -d "$root\ktprobe\classes" "$root\ktprobe\KtDump.java"
if ($LASTEXITCODE -ne 0) { throw 'javac KtDump.java failed' }
Write-Output 'javac KtDump.java -> OK'

$rows = @()
foreach ($f in (Get-ChildItem "$root\probes\*.rsf" | Sort-Object Name)) {
    $abs = $f.FullName
    $base = $f.BaseName
    $rsfFile = "$root\dumps\$base.rsf.txt"
    $ktFile = "$root\dumps\$base.kt.txt"
    # 让 java 把 stdout 直接落盘（绕开 PowerShell 的编码转码，保证比的是字节）
    & cmd /c "java -Dstdout.encoding=UTF-8 -Drsf.ast.file=$abs -cp $cpMain ParserCli > $rsfFile 2>&1"
    $rsfExit = $LASTEXITCODE
    & cmd /c "java -Dstdout.encoding=UTF-8 -cp $cpTest KtDump $abs > $ktFile 2>&1"
    $ktExit = $LASTEXITCODE
    $rsfTxt = (Get-Content -Raw -Encoding utf8 $rsfFile) -replace "`r`n", "`n"
    $ktTxt = (Get-Content -Raw -Encoding utf8 $ktFile) -replace "`r`n", "`n"
    $rows += [pscustomobject]@{
        probe    = $base
        rsfExit  = $rsfExit
        ktExit   = $ktExit
        rsfFailed = $rsfTxt.StartsWith('<解析失败>')
        ktRejected = ($ktTxt -match 'Exception|Error')
        byteSame = ($rsfTxt -ceq $ktTxt)
        rsfBytes = (Get-Item $rsfFile).Length
        ktBytes  = (Get-Item $ktFile).Length
    }
}
$rows | Format-Table -AutoSize | Out-String -Width 200 | Write-Output

$legal = $rows | Where-Object { $_.probe -eq 'legal_interface' }
Write-Output ("合法探针逐字节一致 = " + $legal.byteSame)
$bad = $rows | Where-Object { $_.probe -ne 'legal_interface' }
Write-Output ("非法探针数 = " + $bad.Count + "；rsf 全部判失败 = " + (($bad | Where-Object { -not $_.rsfFailed }).Count -eq 0) + "；Kotlin 全部拒收 = " + (($bad | Where-Object { -not $_.ktRejected }).Count -eq 0))
