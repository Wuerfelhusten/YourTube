# Evaluates a JS file in YourTube's WebView (debug build on emulator-5554) and prints the result.
# Usage: .\tools\debug\tt-eval.ps1 path\to\snippet.js [-Serial emulator-5554]
param(
    [Parameter(Mandatory = $true)][string]$File,
    [string]$Serial = "emulator-5554",
    [int]$WaitSeconds = 3,
    [switch]$Player
)
$adb = Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
$js = (Get-Content $File -Raw) -replace "`r", ""
# Push the script to the device and pass it via a shell variable to avoid quoting issues.
$tmp = New-TemporaryFile
[IO.File]::WriteAllText($tmp, $js)
& $adb -s $Serial push $tmp /data/local/tmp/tt_eval.js | Out-Null
# PowerShell 5.1 mangles nested quotes for native args, so run the am command from a device-side script.
$extra = if ($Player) { "tt_eval_player" } else { "tt_eval" }
[IO.File]::WriteAllText($tmp, ('am start -n com.moddingforge.yourtube.debug/com.moddingforge.yourtube.ui.MainActivity --es EXTRA "$(cat /data/local/tmp/tt_eval.js)"' -replace "EXTRA", $extra) + "`n")
& $adb -s $Serial push $tmp /data/local/tmp/tt_eval.sh | Out-Null
Remove-Item $tmp
& $adb -s $Serial logcat -c
& $adb -s $Serial shell sh /data/local/tmp/tt_eval.sh | Out-Null
Start-Sleep $WaitSeconds
$parts = & $adb -s $Serial logcat -d -s TT.Eval:I | Select-String "\[(\d+)\] (.*)$" | ForEach-Object { $_.Matches[0].Groups[2].Value }
$raw = $parts -join ""
# evaluateJavascript returns a JSON-encoded value; decode string results.
try { $val = $raw | ConvertFrom-Json; if ($val -is [string]) { $val } else { $raw } } catch { $raw }
