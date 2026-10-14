param([string]$Sample)
function Norm($p) {
  $o = javap -c -p $p 2>$null
  $out = New-Object System.Collections.ArrayList
  foreach ($l in $o) {
    if ($l -match '^\s{2}[^\s].*;$') { [void]$out.Add("### " + ($l.Trim() -replace '#\d+','#')) }
    elseif ($l -match '^\s+\d+:') { [void]$out.Add(($l -replace '^\s+\d+:\s*','' -replace '#\d+','#' -replace '\s+',' ')) }
  }
  return $out
}
$kd = "build\tmp17d\cmp2\k_$Sample"; $rd = "build\tmp17d\cmp2\r_$Sample"
$files = Get-ChildItem $kd -Filter *.class | ForEach-Object { $_.Name } | Sort-Object
$files += (Get-ChildItem $rd -Filter *.class | ForEach-Object { $_.Name } | Sort-Object) | Sort-Object -Unique
foreach ($f in ($files | Sort-Object -Unique)) {
  if (-not (Test-Path "$kd\$f")) { Write-Host "$f : MISSING in K"; continue }
  if (-not (Test-Path "$rd\$f")) { Write-Host "$f : MISSING in R"; continue }
  $k = Norm "$kd\$f"; $r = Norm "$rd\$f"
  $n=[Math]::Max($k.Count,$r.Count); $d=0
  for($i=0;$i -lt $n;$i++){ if($k[$i] -ne $r[$i]){ $d++ } }
  Write-Host "$f : K=$($k.Count) R=$($r.Count) diff=$d"
}