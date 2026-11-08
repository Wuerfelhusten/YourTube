# Stores the release signing key as GitHub repository secrets for .github/workflows/release.yml.
# Reads keystore.properties + the keystore it points to; run once from the repository root:
#
#   powershell -ExecutionPolicy Bypass -File tools\release\set-github-secrets.ps1
#
# Requires the GitHub CLI (gh), logged in with access to the repository. The values go straight
# from this machine to GitHub's encrypted secret store; nothing is printed or written to disk.
param([string]$Repo = "Wuerfelhusten/YourTube")

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$propsFile = Join-Path $root "keystore.properties"
if (-not (Test-Path $propsFile)) { throw "keystore.properties not found in $root" }

$props = @{}
foreach ($line in Get-Content $propsFile) {
    if ($line -match '^\s*([^#=][^=]*)=(.*)$') { $props[$Matches[1].Trim()] = $Matches[2].Trim() }
}
foreach ($k in "storeFile", "storePassword", "keyAlias", "keyPassword") {
    if (-not $props[$k]) { throw "keystore.properties: $k is missing" }
}
$keystore = Join-Path $root $props["storeFile"]
if (-not (Test-Path $keystore)) { throw "Keystore not found: $keystore" }

function Set-Secret([string]$Name, [string]$Value) {
    $Value | gh secret set $Name --repo $Repo
    if ($LASTEXITCODE -ne 0) { throw "gh secret set $Name failed" }
}

Set-Secret "YOURTUBE_KEYSTORE_BASE64" ([Convert]::ToBase64String([IO.File]::ReadAllBytes($keystore)))
Set-Secret "YOURTUBE_KEYSTORE_PASSWORD" $props["storePassword"]
Set-Secret "YOURTUBE_KEY_ALIAS" $props["keyAlias"]
Set-Secret "YOURTUBE_KEY_PASSWORD" $props["keyPassword"]

Write-Host "Secrets set for ${Repo}:"
gh secret list --repo $Repo
