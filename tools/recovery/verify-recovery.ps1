param(
    [Parameter(Mandatory=$true)][string]$ClassFile,
    [Parameter(Mandatory=$true)][string]$Source
)
# Validate a recovered .kt against the string constants that the pristine build baked into
# the .class file: every Chinese run found in the class file must appear verbatim in the source.
$lenient = New-Object System.Text.UTF8Encoding($false, $false)
$bytes = [System.IO.File]::ReadAllBytes($ClassFile)
$text = $lenient.GetString($bytes)
$src = [System.IO.File]::ReadAllText($Source, [System.Text.Encoding]::UTF8)

$rx = [regex]'[\u4e00-\u9fff\u3000-\u303f\uff01-\uff5e\u2018-\u201d\u2014\u2026]{3,}'
$frags = @{}
foreach ($m in $rx.Matches($text)) { $frags[$m.Value] = $true }
$missing = New-Object System.Collections.ArrayList
foreach ($k in $frags.Keys) {
    if (-not $src.Contains($k)) { [void]$missing.Add($k) }
}
"class fragments: $($frags.Count)"
"missing in source: $($missing.Count)"
$missing | Sort-Object | ForEach-Object { "  MISSING: $_" }
