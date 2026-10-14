param(
    [Parameter(Mandatory=$true)][string]$Mojibake,
    [Parameter(Mandatory=$true)][string]$ClassFile,
    [Parameter(Mandatory=$true)][string]$Out,
    [Parameter(Mandatory=$true)][string]$SpotsOut,
    [string]$CorpusRoot = 'D:\java\rainStarfox'
)
# Recovery of a CP936/UTF-8 double-encoding accident (see tools/recover-encoding.ps1).
#
# Phase 1: reverse the mis-decode; pick the last byte of each damaged character with the
#          project's own Chinese prose as a language model.
# Phase 2: exactly the characters that sit inside string literals — those are still present
#          in the .class file that the pristine source compiled to, so look them up there.
# Phase 3: for anything still unknown, look the character up in the prose corpus again with
#          a wider window.
$gbk = [System.Text.Encoding]::GetEncoding(936)
$lenient = New-Object System.Text.UTF8Encoding($false, $false)
$utf8 = New-Object System.Text.UTF8Encoding($false)

# ---- phase 2 dictionary: every Chinese run that the pristine build baked into the class file
$clsText = $lenient.GetString([System.IO.File]::ReadAllBytes($ClassFile))
$fragRx = [regex]'[\u4e00-\u9fff\u3000-\u303f\uff01-\uff5e\u2018-\u201d\u2014\u2026\u2190-\u2193]{2,}'
$fragBuilder = New-Object System.Text.StringBuilder
foreach ($m in $fragRx.Matches($clsText)) { [void]$fragBuilder.Append($m.Value).Append("`n") }
$fragments = $fragBuilder.ToString()

# ---- phase 1/3 corpus
$corpus = New-Object System.Text.StringBuilder
$files = Get-ChildItem -Path $CorpusRoot -Recurse -File -Include *.kt,*.rsf,*.md -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch '\\build\\|\\.gradle\\|\\.zig-cache\\|\\out\\|\\tools\\' }
foreach ($f in $files) {
    try { [void]$corpus.Append([System.IO.File]::ReadAllText($f.FullName, [System.Text.Encoding]::UTF8)).Append("`n") } catch { }
}
$corpusText = $corpus.ToString()

$rawText = $lenient.GetString([System.IO.File]::ReadAllBytes($Mojibake))
if ($rawText.Length -gt 0 -and $rawText[0] -eq [char]0xFEFF) { $rawText = $rawText.Substring(1) }
$lines = $rawText -split "`r`n|`n"

function Find-Spots([byte[]]$b) {
    $spots = New-Object System.Collections.ArrayList
    $i = 0
    while ($i -lt $b.Length) {
        $c = $b[$i]
        if ($c -lt 0x80) { $i++; continue }
        $need = 0
        if ($c -ge 0xC2 -and $c -le 0xDF) { $need = 1 }
        elseif ($c -ge 0xE0 -and $c -le 0xEF) { $need = 2 }
        elseif ($c -ge 0xF0 -and $c -le 0xF4) { $need = 3 }
        else { $i++; continue }
        $survived = 0
        for ($k = 1; $k -le $need; $k++) {
            if ($i + $k -lt $b.Length -and $b[$i + $k] -ge 0x80 -and $b[$i + $k] -le 0xBF) { $survived++ } else { break }
        }
        if ($survived -eq $need) {
            if ($i + $need -lt $b.Length) { $i += ($need + 1); continue }
            break
        }
        [void]$spots.Add(@{ Start = $i; PrefixBytes = @($b[$i..($i + $survived)]); LostAt = $i + $survived + 1 })
        $i = $i + $survived + 2
    }
    return $spots
}

function Lookup-Char([string]$left1, [string]$left2, [string]$right1, [string]$right2, [byte[]]$prefix, [string]$dictionary) {
    $patterns = New-Object System.Collections.ArrayList
    if ($left2.Length -ge 1 -and $right1.Length -ge 1) { [void]$patterns.Add(@([regex]::Escape($left2) + '(.)' + [regex]::Escape($right1), 'middle')) }
    if ($left2.Length -ge 1) { [void]$patterns.Add(@([regex]::Escape($left2) + '(.)', 'after')) }
    if ($right1.Length -ge 1) { [void]$patterns.Add(@('(.)' + [regex]::Escape($right1), 'before')) }
    foreach ($p in $patterns) {
        $hits = @{}
        foreach ($m in [regex]::Matches($dictionary, $p[0])) {
            $cand = $m.Groups[1].Value
            $cb = $utf8.GetBytes($cand)
            if ($cb.Length -ne ($prefix.Length + 1)) { continue }
            $ok = $true
            for ($k = 0; $k -lt $prefix.Length; $k++) { if ($cb[$k] -ne $prefix[$k]) { $ok = $false; break } }
            if (-not $ok) { continue }
            if (-not $hits.ContainsKey($cand)) { $hits[$cand] = 0 }
            $hits[$cand] = $hits[$cand] + 1
        }
        if ($hits.Count -gt 0) {
            $best = $null; $bestN = 0
            foreach ($k in $hits.Keys) { if ($hits[$k] -gt $bestN) { $bestN = $hits[$k]; $best = $k } }
            return $best
        }
    }
    return $null
}

$outLines = New-Object System.Collections.ArrayList
$spotRecords = New-Object System.Collections.ArrayList
$byClass = 0
$byCorpus = 0
$unresolved = 0
for ($n = 0; $n -lt $lines.Count; $n++) {
    $line = $lines[$n]
    if ($line -notmatch '[^\x00-\x7F]') { [void]$outLines.Add($line); continue }
    $b = $gbk.GetBytes($line)
    $spots = Find-Spots $b
    if ($spots.Count -eq 0) { [void]$outLines.Add($lenient.GetString($b)); continue }

    $decisions = New-Object System.Collections.ArrayList
    foreach ($s in $spots) {
        $pre = $s.PrefixBytes
        $leftText = $lenient.GetString($b[0..($s.Start - 1)])
        $rightStart = $s.LostAt + 1
        $rightText = if ($rightStart -lt $b.Length) { $lenient.GetString($b[$rightStart..($b.Length - 1)]) } else { '' }
        $l2 = if ($leftText.Length -ge 2) { $leftText.Substring($leftText.Length - 2) } else { $leftText }
        $r1 = if ($rightText.Length -ge 1) { $rightText.Substring(0, 1) } else { '' }

        # candidates limited by the surviving byte prefix
        $cands = New-Object System.Collections.ArrayList
        for ($last = 0x80; $last -le 0xBF; $last++) {
            $cand = $utf8.GetString(@($pre + [byte]$last))
            if ($cand.IndexOf([char]0xFFFD) -ge 0) { continue }
            [void]$cands.Add($cand)
        }

        $best = $null; $bestScore = 0; $how = ''
        # phase 2: string literals, exact
        $fromClass = Lookup-Char $l2 $l2 $r1 '' $pre $fragments
        if ($fromClass -ne $null) { $best = $fromClass; $bestScore = 999; $how = 'class'; $byClass++ }
        else {
            # phase 1/3: corpus language model over the candidate set
            $r2 = if ($rightText.Length -ge 2) { $rightText.Substring(0, 2) } else { $rightText }
            foreach ($cand in $cands) {
                $score = 0
                if ($l2.Length -eq 2 -and $r1.Length -ge 1) {
                    $score = ([regex]::Matches($corpusText, [regex]::Escape($l2) + [regex]::Escape($cand) + [regex]::Escape($r1))).Count * 10
                }
                if ($score -eq 0 -and $l2.Length -ge 1) {
                    $score = ([regex]::Matches($corpusText, [regex]::Escape($l2) + [regex]::Escape($cand))).Count
                }
                if ($score -gt $bestScore) { $bestScore = $score; $best = $cand }
            }
            if ($best -ne $null -and $bestScore -gt 0) { $how = 'corpus'; $byCorpus++ } else { $how = ''; $unresolved++ }
        }

        $id = $spotRecords.Count + 1
        $guessText = ''
        if ($best -ne $null) { $guessText = $best }
        [void]$spotRecords.Add([pscustomobject]@{
            Id = $id; Line = $n + 1
            Prefix = (($pre | ForEach-Object { $_.ToString('X2') }) -join ' ')
            Left = $l2; Right = $r1; Guess = $guessText; How = $how; Score = $bestScore
        })
        [void]$decisions.Add(@{ At = $s.LostAt; Char = $best })
    }

    $newBytes = New-Object System.Collections.ArrayList
    $cursor = 0
    foreach ($d in $decisions) {
        while ($cursor -lt $d.At) { [void]$newBytes.Add($b[$cursor]); $cursor++ }
        if ($d.Char) { foreach ($bb in $utf8.GetBytes($d.Char)) { [void]$newBytes.Add($bb) } }
        else { [void]$newBytes.Add([byte]0x3F) }
        $cursor = $d.At + 1
    }
    while ($cursor -lt $b.Length) { [void]$newBytes.Add($b[$cursor]); $cursor++ }
    [void]$outLines.Add($lenient.GetString($newBytes.ToArray()))
}
[System.IO.File]::WriteAllBytes($Out, $utf8.GetBytes((($outLines -join "`r`n") + "`r`n")))
$spotRecords | ForEach-Object { "id={0}`tline={1}`tprefix={2}`tleft=[{3}]`tright=[{4}]`tguess=[{5}]`thow={6}`tscore={7}" -f $_.Id, $_.Line, $_.Prefix, $_.Left, $_.Right, $_.Guess, $_.How, $_.Score } |
    Set-Content -Encoding UTF8 $SpotsOut
"fragments: $($fragRx.Matches($clsText).Count)  corpus files: $($files.Count)"
"spots: $($spotRecords.Count)  from class: $byClass  from corpus: $byCorpus  unresolved: $unresolved"
