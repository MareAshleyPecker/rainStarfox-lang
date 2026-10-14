function Cmp($p,$n){
  Remove-Item -Recurse -Force "build\tmp17d\r_$n" -ErrorAction SilentlyContinue
  Remove-Item -Recurse -Force "build\tmp17d\k_$n" -ErrorAction SilentlyContinue
  & java -jar build\libs\rainStarfox-0.9.0.jar build $p -o "build\tmp17d\k_$n" 2>&1 | Out-Null
  $pr = Start-Process -FilePath java -ArgumentList @("-Drsf.backend.file=$p","-Drsf.backend.out=build/tmp17d/r_$n","-cp","build/tmp17d/bout;build/libs/rainStarfox-0.9.0.jar","BackendCli") -NoNewWindow -Wait -PassThru -RedirectStandardError "build\tmp17d\err_$n.txt" -RedirectStandardOutput "build\tmp17d\gen_$n.txt"
  if ($pr.ExitCode -ne 0) {
    "[" + $n + "] rsf 后端失败: " + [System.IO.File]::ReadAllText("D:\java\rainStarfox\build\tmp17d\err_$n.txt", [System.Text.Encoding]::GetEncoding(936))
    return
  }
  $a = Start-Process -FilePath java -ArgumentList @("-cp","build/tmp17d/k_$n",$n) -NoNewWindow -Wait -PassThru -RedirectStandardOutput "build\tmp17d\k_$n.txt" -RedirectStandardError "build\tmp17d\k_$n.err"
  $b = Start-Process -FilePath java -ArgumentList @("-cp","build/tmp17d/r_$n",$n) -NoNewWindow -Wait -PassThru -RedirectStandardOutput "build\tmp17d\r_$n.txt" -RedirectStandardError "build\tmp17d\r_$n.err"
  $kt = [System.IO.File]::ReadAllText("D:\java\rainStarfox\build\tmp17d\k_$n.txt", [System.Text.Encoding]::GetEncoding(936))
  $rt = [System.IO.File]::ReadAllText("D:\java\rainStarfox\build\tmp17d\r_$n.txt", [System.Text.Encoding]::GetEncoding(936))
  $ke = [System.IO.File]::ReadAllText("D:\java\rainStarfox\build\tmp17d\k_$n.err", [System.Text.Encoding]::GetEncoding(936))
  $re = [System.IO.File]::ReadAllText("D:\java\rainStarfox\build\tmp17d\r_$n.err", [System.Text.Encoding]::GetEncoding(936))
  if ($kt -eq $rt -and $ke -eq $re) { "[$n] OK (exit k=$($a.ExitCode) r=$($b.ExitCode))" }
  else {
    "[$n] DIFF (exit k=$($a.ExitCode) r=$($b.ExitCode))"
    "k-stdout:"; $kt
    "r-stdout:"; $rt
    "k-stderr:"; $ke
    "r-stderr:"; $re
  }
}
Cmp "rsfSrc/lang/enums.rsf" "enums"
Cmp "rsfSrc/lang/files.rsf" "files"
Cmp "rsfSrc/lang/generics.rsf" "generics"
Cmp "rsfSrc/lang/arrays.rsf" "arrays"
Cmp "rsfSrc/lang/main.rsf" "main"
Cmp "rsfSrc/lang/stdlib.rsf" "stdlib"
Cmp "rsfSrc/lang/errors.rsf" "errors"