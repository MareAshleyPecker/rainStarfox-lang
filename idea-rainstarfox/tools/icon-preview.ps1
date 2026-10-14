param(
    [string]$Html = "preview/icon-16.html",
    [int]$W = 16,
    [int]$H = 16,
    [int]$Cells = 16
)
# Rasterize an SVG icon with headless Edge, then print it as ASCII art by colour.
# Why: icons here are hand-written SVG with no visual check available in this
# environment. Keep this file ASCII-only -- Windows PowerShell 5.1 reads .ps1
# using the system ANSI codepage, so non-ASCII characters break parsing.
$edge = @(
    "C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe",
    "C:\Program Files\Microsoft\Edge\Application\msedge.exe"
) | Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $edge) { Write-Output "Edge not found, skipping icon check"; exit 0 }

$root = "D:\java\rainStarfox\idea-rainstarfox"
$png  = Join-Path $root "build\shot.png"
if (Test-Path $png) { Remove-Item $png }
$url  = "file:///" + ($root -replace '\\', '/') + "/tools/" + $Html
& $edge --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 --window-size=$W,$H --screenshot="$png" $url 2>$null | Out-Null

Add-Type -AssemblyName System.Drawing
$bmp = [System.Drawing.Bitmap]::FromFile($png)
Write-Output ("bitmap: " + $bmp.Width + "x" + $bmp.Height)
for ($y = 0; $y -lt $Cells; $y++) {
    $row = ""
    for ($x = 0; $x -lt $Cells; $x++) {
        $px = $bmp.GetPixel($x, $y)
        $r = [int]$px.R
        $g = [int]$px.G
        $b = [int]$px.B
        if ($b -gt 110 -and $b -gt ($r + 25)) { $row += "B" }
        elseif ($r -gt 180 -and $g -gt 130 -and $b -lt 140) { $row += "Y" }
        elseif ($r -lt 120 -and $g -lt 120 -and $b -lt 120) { $row += "#" }
        elseif ($r -gt 230 -and $g -gt 230 -and $b -gt 230) { $row += "." }
        else { $row += "+" }
    }
    Write-Output $row
}
$bmp.Dispose()
