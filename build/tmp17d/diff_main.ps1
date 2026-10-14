$pairs = @{}
foreach ($d in @('k_main','r_main')) { $p = "build\tmp17d\cmp2\$d"; $pairs[$d] = $p }
function Norm($p) {
  $o = javap -c -p $p 2>$null
  $out = New-Object System.Collections.ArrayList
  $cur = ""
  foreach ($l in $o) {
    if ($l -match '^\s{2}[^\s].*\(.*\).*;\s*$' -or $l -match '^\s{2}[^\s].*;$') { $cur = $l.Trim(); [void]$out.Add("### " + ($cur -replace '#\d+','#')) }
    elseif ($l -match '^\s+\d+:') { [void]$out.Add(($l -replace '^\s+\d+:\s*','' -replace '#\d+','#' -replace '\s+',' ')) }
  }
  return $out
}
$k = Norm 'build\tmp17d\cmp2\k_main\main.class'
$r = Norm 'build\tmp17d\cmp2\r_main\main.class'
Write-Host "K=$($k.Count) R=$($r.Count)"
$n=[Math]::Max($k.Count,$r.Count); $d=0
for($i=0;$i -lt $n;$i++){ if($k[$i] -ne $r[$i]){ $d++; if($d -le 30){ Write-Host "i$i K: $($k[$i])"; Write-Host "i$i R: $($r[$i])" } } }
Write-Host "diff=$d"