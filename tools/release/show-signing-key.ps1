# Shows the release signing data with labels, for storing it in a password manager.
# The keystore itself (base64) is copied to the clipboard. Run from anywhere:
#
#   powershell -ExecutionPolicy Bypass -File tools\release\show-signing-key.ps1
#
# -Masked hides the values (for checking that the script works).
param([switch]$Masked)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$propsFile = Join-Path $root "keystore.properties"
if (-not (Test-Path $propsFile)) { throw "keystore.properties not found in $root" }

$p = @{}
foreach ($line in Get-Content $propsFile) {
    if ($line -match '^\s*([^#=][^=]*)=(.*)$') { $p[$Matches[1].Trim()] = $Matches[2].Trim() }
}
$keystore = Join-Path $root $p["storeFile"]
if (-not (Test-Path $keystore)) { throw "Keystore not found: $keystore" }
$b64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore))

function Show([string]$Label, [string]$Value, [switch]$Secret) {
    if ($Masked -and $Secret) { $Value = "*" * [Math]::Min($Value.Length, 8) + " (" + $Value.Length + " chars)" }
    Write-Host ("{0,-20}: " -f $Label) -NoNewline -ForegroundColor Gray
    Write-Host $Value
}

Write-Host ""
Write-Host "=== YourTube release key (for Bitwarden) ===" -ForegroundColor Cyan
Show "Keystore file" $p["storeFile"]
Show "Keystore password" $p["storePassword"] -Secret
Show "Key alias" $p["keyAlias"] -Secret
Show "Key password" $p["keyPassword"] -Secret
Show "Keystore (base64)" ("" + $b64.Length + " chars - copied to the clipboard")
Show "Certificate SHA-256" "704cd31011d26d6c6a27d0fcbccfa48db5df8b0cf9face69a686669173deea34"
Write-Host ""
if (-not $Masked) {
    Set-Clipboard -Value $b64
    Write-Host "Afterwards: close this window and clear the clipboard (Set-Clipboard -Value ' ')." -ForegroundColor Yellow
}
