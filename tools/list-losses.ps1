param(
    [Parameter(Mandatory=$true)][string]$Path,
    [Parameter(Mandatory=$true)][string]$Out
)
# List every lost byte: encode each line with CP936, decode as UTF-8 while tracking
# exactly where the byte stream becomes invalid, and show a little context.
$gbk = [System.Text.Encoding]::GetEncoding(936)
$bytes = [System.IO.File]::ReadAllBytes($Path)
$text = [System.Text.Encoding]::UTF8.GetString($bytes)
if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
$lines = $text -split "`r`n|`n"

$report = New-Object System.Collections.ArrayList
for ($n = 0; $n -lt $lines.Count; $n++) {
    $line = $lines[$n]
    if ($line -notmatch '[^\x00-\x7F]') { continue }
    $b = $gbk.GetBytes($line)
    # walk the byte stream as UTF-8 and mark bad spots
    $i = 0
    $bad = New-Object System.Collections.ArrayList
    while ($i -lt $b.Length) {
        $c = $b[$i]
        if ($c -lt 0x80) { $i++; continue }
        $need = 0
        if ($c -ge 0xC2 -and $c -le 0xDF) { $need = 1 }
        elseif ($c -ge 0xE0 -and $c -le 0xEF) { $need = 2 }
        elseif ($c -ge 0xF0 -and $c -le 0xF4) { $need = 3 }
        else { [void]$bad.Add(@{ Index = $i; Kind = 'badlead'; Byte = $c }); $i++; continue }
        $okAll = $true
        for ($k = 1; $k -le $need; $k++) {
            if ($i + $k -ge $b.Length) { $okAll = $false; break }
            $cc = $b[$i + $k]
            if ($cc -lt 0x80 -or $cc -gt 0xBF) { $okAll = $false; break }
        }
        if ($okAll) { $i += ($need + 1); continue }
        # incomplete / invalid: report the offending byte (the '?' placeholder)
        [void]$bad.Add(@{ Index = $i; Kind = 'truncated'; Byte = $c })
        $i += 1
    }
    if ($bad.Count -eq 0) { continue }
    foreach ($spot in $bad) {
        $start = [Math]::Max(0, $spot.Index - 8)
        $before = $b[$start..($spot.Index - 1)]
        $after = @()
        if ($spot.Index + 1 -lt $b.Length) {
            $end = [Math]::Min($b.Length - 1, $spot.Index + 6)
            $after = $b[($spot.Index + 1)..$end]
        }
        $preHex = ($before | ForEach-Object { $_.ToString('X2') }) -join ' '
        $postHex = ($after | ForEach-Object { $_.ToString('X2') }) -join ' '
        [void]$report.Add(
            ("line={0} kind={1} byteIn={2} before=[{3}] at=[{4}] after=[{5}]" -f `
                ($n + 1), $spot.Kind, $spot.Byte.ToString('X2'), $preHex, $b[$spot.Index].ToString('X2'), $postHex)
        )
    }
}
$report | Set-Content -Encoding UTF8 $Out
"loss spots: $($report.Count)"
$report | Select-Object -First 40
