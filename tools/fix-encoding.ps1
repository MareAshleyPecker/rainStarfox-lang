param(
    [Parameter(Mandatory=$true)][string]$Path,
    [Parameter(Mandatory=$true)][string]$Out
)
# Reverse a damaged round-trip: the text was read as CP936 and re-written as UTF-8.
# Per line: encode with CP936 -> decode as UTF-8 (lenient: invalid bytes -> U+FFFD).
# Lines that end up with U+FFFD are reported so the few lost characters can be patched.
$gbk = [System.Text.Encoding]::GetEncoding(936)
$lenient = New-Object System.Text.UTF8Encoding($false, $false)
$utf8 = New-Object System.Text.UTF8Encoding($false)

$bytes = [System.IO.File]::ReadAllBytes($Path)
$text = [System.Text.Encoding]::UTF8.GetString($bytes)
if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
$lines = $text -split "`r`n|`n"

$clean = 0
$lossy = New-Object System.Collections.ArrayList
$outLines = New-Object System.Collections.ArrayList
for ($i = 0; $i -lt $lines.Count; $i++) {
    $line = $lines[$i]
    if ($line -notmatch '[^\x00-\x7F]') { [void]$outLines.Add($line); $clean++; continue }
    $b = $gbk.GetBytes($line)
    $recovered = $lenient.GetString($b)
    [void]$outLines.Add($recovered)
    if ($recovered.IndexOf([char]0xFFFD) -ge 0) {
        [void]$lossy.Add(($i + 1))
    } else {
        $clean++
    }
}
$joined = ($outLines -join "`r`n") + "`r`n"
[System.IO.File]::WriteAllBytes($Out, $utf8.GetBytes($joined))
"clean lines: $clean"
"lossy lines: $($lossy.Count)"
if ($lossy.Count -gt 0) { "lossy line numbers: " + ($lossy -join ',') }
