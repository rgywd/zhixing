param(
    [string]$Config = (Join-Path $PSScriptRoot "work-runner.json")
)

$ErrorActionPreference = "Stop"
$workRoot = Split-Path $PSScriptRoot -Parent
if (-not (Test-Path -LiteralPath $Config -PathType Leaf)) {
    throw "Runner config not found: $Config"
}

Push-Location $workRoot
try {
    if (-not (Test-Path -LiteralPath (Join-Path $workRoot "node_modules") -PathType Container)) {
        npm ci
    }
    $env:WORK_RUNNER_CONFIG = (Resolve-Path -LiteralPath $Config).Path
    node runner/index.js
}
finally {
    Pop-Location
}
