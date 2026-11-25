param(
    [Parameter(Mandatory=$true)][string]$Path,
    [Parameter(Mandatory=$true)][int]$LineNo
)
$gbk = [System.Text.Encoding]::GetEncoding(936)
$lenient = New-Object System.Text.UTF8Encoding($false, $false)
$bytes = [System.IO.File]::ReadAllBytes($Path)
$text = [System.Text.Encoding]::UTF8.GetString($bytes)
if ($text.Length -gt 0 -and $text[0] -eq [char]0xFEFF) { $text = $text.Substring(1) }
$lines = $text -split "`r`n|`n"
$line = $lines[$LineNo - 1]
"LINE $LineNo (mojibake):"
$line
$b = $gbk.GetBytes($line)
"CP936 bytes: " + (($b | ForEach-Object { $_.ToString('X2') }) -join ' ')
"recovered: " + $lenient.GetString($b)
"codepoints: " + (($line.ToCharArray() | ForEach-Object { ('U+{0:X4}' -f [int]$_) }) -join ' ')
