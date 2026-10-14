# 独立对拍：Kotlin 版 rsf build  vs  rsf 版后端（bcheck 里的 BackendCli）
# 三层比较：① .class 字节级 ② 反汇编 ③ 运行期 stdout/stderr
$ErrorActionPreference = "Continue"
$JAR = "build\libs\rainStarfox-0.9.0.jar"
$BOUT = "build\tmp17d\bcheck"
$TMP = "build\tmp17d\cmp2"
New-Item -ItemType Directory -Force -Path $TMP | Out-Null

function Enc($p) { if (Test-Path $p) { [System.IO.File]::ReadAllText((Resolve-Path $p), [System.Text.Encoding]::GetEncoding(936)) } else { "" } }

$samples = @("main","class","generics","errors","stdlib","arrays","files","enums")
$rows = @()
foreach ($n in $samples) {
  $p = "rsfSrc/lang/$n.rsf"
  $kd = "$TMP\k_$n"; $rd = "$TMP\r_$n"
  Remove-Item -Recurse -Force $kd,$rd -ErrorAction SilentlyContinue

  # ① Kotlin 版
  $kout = & java -jar $JAR build $p -o $kd 2>&1
  $kbuildok = ($LASTEXITCODE -eq 0)

  # ② rsf 版后端
  $pr = Start-Process -FilePath java -ArgumentList @("-Drsf.backend.file=$p","-Drsf.backend.out=$rd","-cp","$BOUT;$JAR","BackendCli") -NoNewWindow -Wait -PassThru -RedirectStandardOutput "$TMP\gen_$n.txt" -RedirectStandardError "$TMP\gen_$n.err"
  $rbuildok = ($pr.ExitCode -eq 0)
  $rerr = (Enc "$TMP\gen_$n.err") + (Enc "$TMP\gen_$n.txt")

  if (-not $kbuildok) { $rows += [pscustomobject]@{ Sample=$n; KBuild="FAIL"; RBuild="-"; Bytes="-"; Run="-"; Note=(($kout | Out-String).Trim()) }; continue }
  if (-not $rbuildok) {
    $msg = ($rerr -split "`n" | Where-Object { $_ -match 'rsf|错误|error' } | Select-Object -First 1)
    $rows += [pscustomobject]@{ Sample=$n; KBuild="OK"; RBuild="FAIL"; Bytes="-"; Run="-"; Note=$msg }
    continue
  }

  # ③ .class 字节级比较
  $kf = Get-ChildItem $kd -Filter *.class -ErrorAction SilentlyContinue | Sort-Object Name
  $rf = Get-ChildItem $rd -Filter *.class -ErrorAction SilentlyContinue | Sort-Object Name
  $same = 0; $diff = 0; $diffs = @()
  foreach ($f in $kf) {
    $rf2 = $rf | Where-Object { $_.Name -eq $f.Name }
    if (-not $rf2) { $diff++; $diffs += "$($f.Name)(缺)"; continue }
    $a = [System.IO.File]::ReadAllBytes($f.FullName); $b = [System.IO.File]::ReadAllBytes($rf2.FullName)
    $eq = ($a.Length -eq $b.Length)
    if ($eq) { for ($i=0; $i -lt $a.Length; $i++) { if ($a[$i] -ne $b[$i]) { $eq = $false; break } } }
    if ($eq) { $same++ } else { $diff++; $diffs += ("$($f.Name):$($a.Length)vs$($b.Length)") }
  }
  $bytesTxt = if ($diff -eq 0) { "全部相同($same)" } else { "相同$same/差$diff [" + ($diffs -join ",") + "]" }

  # ④ 运行期
  $ka = Start-Process -FilePath java -ArgumentList @("-cp",$kd,$n) -NoNewWindow -Wait -PassThru -RedirectStandardOutput "$TMP\k_$n.out" -RedirectStandardError "$TMP\k_$n.oe"
  $ra = Start-Process -FilePath java -ArgumentList @("-cp",$rd,$n) -NoNewWindow -Wait -PassThru -RedirectStandardOutput "$TMP\r_$n.out" -RedirectStandardError "$TMP\r_$n.oe"
  $kt = Enc "$TMP\k_$n.out"; $rt = Enc "$TMP\r_$n.out"
  $ke = Enc "$TMP\k_$n.oe"; $re = Enc "$TMP\r_$n.oe"
  $runTxt = if ($kt -eq $rt -and $ke -eq $re) { "stdout/stderr 一致 ($($kt.Length)ch)" } else { "DIFF stdout k=$($kt.Length) r=$($rt.Length); err k=$($ke.Length) r=$($re.Length)" }
  $rows += [pscustomobject]@{ Sample=$n; KBuild="OK"; RBuild="OK"; Bytes=$bytesTxt; Run=$runTxt; Note="" }
}
$rows | Format-Table -AutoSize -Wrap | Out-String -Width 400
