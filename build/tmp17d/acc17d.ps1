# 17d 独立验收台：rsf 版字节码后端 vs Kotlin 版 rsf build
# 用法：pwsh -File build\tmp17d\acc17d.ps1 -Bout build\tmp17d\bcheck
# 依赖：javap（JDK）、build\libs\rainStarfox-0.9.0.jar（含 ASM）
param(
  [string]$Bout = "build\tmp17d\bcheck",
  [string]$Tmp  = "build\tmp17d\acc",
  [string]$Jar  = "build\libs\rainStarfox-0.9.0.jar"
)
$ErrorActionPreference = "Continue"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$root = "D:\java\rainStarfox"
Set-Location $root
New-Item -ItemType Directory -Force -Path $Tmp | Out-Null

function Enc([string]$p) {
  if (Test-Path $p) { [System.IO.File]::ReadAllText((Resolve-Path $p), [System.Text.Encoding]::GetEncoding(936)) } else { "" }
}

# javap 结构化文本：去掉行号表（阶段 17d 的已知差异）与常量池下标/注释（两端池内容不同）
function NormJavap([string]$dir, [string]$base) {
  $raw = & javap -c -p -cp $dir $base 2>&1 | Out-String
  $out = New-Object System.Collections.Generic.List[string]
  foreach ($line in ($raw -split "`r?`n")) {
    if ($line -match '^\s*(LineNumberTable:|LocalVariableTable:|LocalVariableTypeTable:|line \d+:)') { continue }
    if ($line -match '^\s*\d+\s+\d+\s+\d+\s+\S') { continue }   # LVC 行：Start Length Slot Name
    if ($line -match '^Compiled from ') { continue }
    $t = $line -replace '//.*$', ''
    $t = $t -replace '#\d+', '#'
    $t = $t -replace '\s+', ' '
    $out.Add($t.TrimEnd())
  }
  ($out -join "`n")
}

function ClassSet([string]$dir) {
  Get-ChildItem $dir -Filter *.class -ErrorAction SilentlyContinue | Sort-Object Name | ForEach-Object { $_.Name }
}

$samples = @("main","class","generics","errors","stdlib","arrays","files","enums")
$rows = @()

foreach ($n in $samples) {
  $src = "rsfSrc/lang/$n.rsf"
  $kd = "$Tmp\k_$n"; $rd = "$Tmp\r_$n"
  Remove-Item -Recurse -Force $kd,$rd -ErrorAction SilentlyContinue

  # ① Kotlin 版后端
  & java -jar $Jar build $src -o $kd 2>&1 | Out-Null
  $kOk = ($LASTEXITCODE -eq 0)

  # ② rsf 版后端（独立 JVM，BackendCli 是 rsf 写的）
  $pr = Start-Process -FilePath java `
        -ArgumentList @("-Drsf.backend.file=$src", "-Drsf.backend.out=$rd", "-cp", "$Bout;$Jar", "BackendCli") `
        -NoNewWindow -Wait -PassThru `
        -RedirectStandardOutput "$Tmp\gen_$n.out" -RedirectStandardError "$Tmp\gen_$n.err"
  $rOk = ($pr.ExitCode -eq 0)

  if (-not $kOk) { $rows += [pscustomobject]@{ 样例=$n; Kotlin="FAIL"; rsf="-"; 结构="-"; 运行="-"; 备注="Kotlin 版都编不过" }; continue }
  if (-not $rOk) {
    $msg = ((Enc "$Tmp\gen_$n.err") + (Enc "$Tmp\gen_$n.out")) -split "`r?`n" |
           Where-Object { $_ -match 'rsf|error|错误|不支持' } | Select-Object -First 1
    $rows += [pscustomobject]@{ 样例=$n; Kotlin="OK"; rsf="FAIL"; 结构="-"; 运行="-"; 备注=$msg }
    continue
  }

  # ③ 类集合 + 字节级（字节级在行号对齐前预期不同，这里只报数，不当判据）
  $kf = ClassSet $kd; $rf = ClassSet $rd
  $missing = @($kf | Where-Object { $rf -notcontains $_ })
  $extra   = @($rf | Where-Object { $kf -notcontains $_ })
  $bytes = 0; $byteDiff = @()
  foreach ($f in $kf) {
    if ($rf -notcontains $f) { continue }
    $a = [System.IO.File]::ReadAllBytes("$kd\$f"); $b = [System.IO.File]::ReadAllBytes("$rd\$f")
    $eq = ($a.Length -eq $b.Length)
    if ($eq) { for ($i = 0; $i -lt $a.Length; $i++) { if ($a[$i] -ne $b[$i]) { $eq = $false; break } } }
    if ($eq) { $bytes++ } else { $byteDiff += "$f($($a.Length)vs$($b.Length))" }
  }
  $classTxt = if ($missing.Count -eq 0 -and $extra.Count -eq 0) { "$($kf.Count)个" } else { "缺[$(($missing -join ','))] 多[$(($extra -join ','))]" }

  # ④ 结构化反汇编对比（行号已剔除 ⇒ 这是阶段 17d 的实质判据）
  $struct = "一致"
  if ($missing.Count -gt 0 -or $extra.Count -gt 0) { $struct = "类不全，跳过" }
  else {
    $diffs = @()
    foreach ($f in $kf) {
      $base = [System.IO.Path]::GetFileNameWithoutExtension($f)
      $kt = NormJavap $kd $base
      $rt = NormJavap $rd $base
      if ($kt -ne $rt) {
        $kl = $kt -split "`n"; $rl = $rt -split "`n"
        $first = 0
        while ($first -lt $kl.Count -and $first -lt $rl.Count -and $kl[$first] -eq $rl[$first]) { $first++ }
        $diffs += "$base@第$($first+1)行`n      K: $($kl[$first])`n      r: $($rl[$first])"
      }
    }
    if ($diffs.Count -gt 0) { $struct = "$($diffs.Count)个类不同：`n    " + ($diffs -join "`n    ") }
  }

  # ⑤ 运行期
  $ka = Start-Process -FilePath java -ArgumentList @("-cp",$kd,$n) -NoNewWindow -Wait -PassThru -RedirectStandardOutput "$Tmp\k_$n.run" -RedirectStandardError "$Tmp\k_$n.runerr"
  $ra = Start-Process -FilePath java -ArgumentList @("-cp",$rd,$n) -NoNewWindow -Wait -PassThru -RedirectStandardOutput "$Tmp\r_$n.run" -RedirectStandardError "$Tmp\r_$n.runerr"
  $kt2 = Enc "$Tmp\k_$n.run"; $rt2 = Enc "$Tmp\r_$n.run"
  $ke2 = Enc "$Tmp\k_$n.runerr"; $re2 = Enc "$Tmp\r_$n.runerr"
  $run = if ($kt2 -eq $rt2 -and $ke2 -eq $re2 -and $ka.ExitCode -eq $ra.ExitCode) { "一致（$($kt2.Length)字符，退出码 $($ka.ExitCode)）" }
         else { "DIFF：k=$($kt2.Length)ch/exit$($ka.ExitCode) r=$($rt2.Length)ch/exit$($ra.ExitCode) err k=$($ke2.Length) r=$($re2.Length)" }

  $note = if ($byteDiff.Count -eq 0) { "字节级也全同" } else { "字节级同$bytes/差$($byteDiff.Count)" }
  $rows += [pscustomobject]@{ 样例=$n; Kotlin="OK"; rsf="OK"; 结构=$struct; 运行=$run; 备注="$note | 类:$classTxt" }
}

Write-Output "==== 17d 验收（rsf 后端类目录：$Bout） ===="
$rows | Format-List | Out-String -Width 400
$ok = @($rows | Where-Object { $_.结构 -eq "一致" -and $_.运行 -like "一致*" }).Count
Write-Output "结构+运行 双过：$ok / $($samples.Count)"
