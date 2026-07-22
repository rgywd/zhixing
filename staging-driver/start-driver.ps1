param(
    [int]$Port = 18787
)

$ErrorActionPreference = "Stop"
if (-not $env:STAGING_DRIVER_TOKEN) {
    $bytes = New-Object byte[] 32
    [Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
    $env:STAGING_DRIVER_TOKEN = [Convert]::ToHexString($bytes).ToLowerInvariant()
    Write-Host "STAGING_DRIVER_TOKEN=$($env:STAGING_DRIVER_TOKEN)"
}
$env:STAGING_DRIVER_PORT = $Port
node (Join-Path $PSScriptRoot "server.js")
