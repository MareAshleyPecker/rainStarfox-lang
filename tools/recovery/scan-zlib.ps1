param(
    [Parameter(Mandatory=$true)][string]$Path,
    [Parameter(Mandatory=$true)][string]$Marker
)
# Scan a binary blob for zlib streams and report any that inflate to text containing Marker.
$bytes = [System.IO.File]::ReadAllBytes($Path)
$hits = 0
$i = 0
$found = New-Object System.Collections.ArrayList
while ($i -lt $bytes.Length - 1) {
    $b0 = $bytes[$i]
    $b1 = $bytes[$i + 1]
    $isZlib = ($b0 -eq 0x78) -and (($b1 -eq 0x9C) -or ($b1 -eq 0x01) -or ($b1 -eq 0xDA) -or ($b1 -eq 0x5E))
    if ($isZlib) {
        try {
            # raw deflate data starts after the 2-byte zlib header
            $ms = New-Object System.IO.MemoryStream($bytes, $i + 2, $bytes.Length - $i - 2)
            $ds = New-Object System.IO.Compression.DeflateStream($ms, [System.IO.Compression.CompressionMode]::Decompress)
            $out = New-Object System.IO.MemoryStream
            $ds.CopyTo($out)
            $ds.Dispose()
            $len = $out.Length
            if ($len -gt 100) {
                $text = [System.Text.Encoding]::UTF8.GetString($out.ToArray())
                if ($text.Contains($Marker)) {
                    $hits++
                    [void]$found.Add("offset=$i inflatedBytes=$len")
                }
            }
            $out.Dispose()
        } catch {
        }
    }
    $i++
}
"zlib hits containing marker: $hits"
$found | Select-Object -First 20
