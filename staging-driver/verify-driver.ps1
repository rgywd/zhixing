param(
    [switch]$Install,
    [string]$WorkBaseUrl = $env:ZHIXING_STAGING_WORK_BASE_URL,
    [string]$WorkToken = $env:ZHIXING_STAGING_WORK_TOKEN,
    [string]$Message = "Staging 验收：验证 Work 会话标题不是仓库名"
)

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent $PSScriptRoot
$adb = if ($env:ADB_PATH) {
    $env:ADB_PATH
} else {
    Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
}

$bytes = New-Object byte[] 32
[Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$driverToken = [Convert]::ToHexString($bytes).ToLowerInvariant()
$env:STAGING_DRIVER_TOKEN = $driverToken
$env:STAGING_DRIVER_PORT = "18787"
$headers = @{ Authorization = "Bearer $driverToken" }
$driver = Start-Process `
    -FilePath "node.exe" `
    -ArgumentList @("staging-driver/server.js") `
    -WorkingDirectory $repoRoot `
    -WindowStyle Hidden `
    -PassThru

try {
    $ready = $false
    for ($attempt = 0; $attempt -lt 30; $attempt++) {
        try {
            $null = Invoke-RestMethod `
                -Uri "http://127.0.0.1:18787/v1/status" `
                -Headers $headers `
                -TimeoutSec 2
            $ready = $true
            break
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    if (-not $ready) {
        throw "staging-driver did not become ready"
    }

    $installation = $null
    if ($Install) {
        $installation = Invoke-RestMethod `
            -Method Post `
            -Uri "http://127.0.0.1:18787/v1/installations" `
            -Headers $headers `
            -ContentType "application/json" `
            -Body "{}" `
            -TimeoutSec 600
    }

    $workBootstrap = $null
    $workScenario = $null
    if ($WorkBaseUrl -or $WorkToken) {
        if (-not $WorkBaseUrl -or -not $WorkToken) {
            throw "WorkBaseUrl and WorkToken must be provided together"
        }
        $workBootstrap = Invoke-RestMethod `
            -Method Post `
            -Uri "http://127.0.0.1:18787/v1/config/work" `
            -Headers $headers `
            -ContentType "application/json" `
            -Body (@{ baseUrl = $WorkBaseUrl; token = $WorkToken } | ConvertTo-Json -Compress) `
            -TimeoutSec 60
        $workScenario = Invoke-RestMethod `
            -Method Post `
            -Uri "http://127.0.0.1:18787/v1/runs/work-title" `
            -Headers $headers `
            -ContentType "application/json" `
            -Body (@{ message = $Message } | ConvertTo-Json -Compress) `
            -TimeoutSec 180
    }

    & $adb shell monkey -p dev.sundby.zhixing.staging -c android.intent.category.LAUNCHER 1 | Out-Null
    Start-Sleep -Seconds 2
    $status = Invoke-RestMethod `
        -Uri "http://127.0.0.1:18787/v1/status" `
        -Headers $headers `
        -TimeoutSec 10
    $reportDirectory = Join-Path $repoRoot "build\reports\staging"
    New-Item -ItemType Directory -Force -Path $reportDirectory | Out-Null
    $screenshot = Join-Path $reportDirectory "current.png"
    Invoke-WebRequest `
        -Uri "http://127.0.0.1:18787/v1/screenshots/current" `
        -Headers $headers `
        -OutFile $screenshot `
        -TimeoutSec 30

    [pscustomobject]@{
        installation = $installation
        status = $status
        workBootstrap = $workBootstrap
        workScenario = $workScenario
        screenshot = $screenshot
        screenshotBytes = (Get-Item $screenshot).Length
    } | ConvertTo-Json -Depth 8
} finally {
    if ($driver -and -not $driver.HasExited) {
        Stop-Process -Id $driver.Id
    }
}
