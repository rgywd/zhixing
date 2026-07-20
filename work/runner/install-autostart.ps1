param(
    [string]$Config = (Join-Path $PSScriptRoot "work-runner.json")
)

$ErrorActionPreference = "Stop"
$launcher = Join-Path $PSScriptRoot "start-runner.ps1"
$configPath = (Resolve-Path -LiteralPath $Config).Path
$action = New-ScheduledTaskAction `
    -Execute "pwsh.exe" `
    -Argument "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$launcher`" -Config `"$configPath`""
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $env:USERNAME
$settings = New-ScheduledTaskSettingsSet -RestartCount 3 -RestartInterval (New-TimeSpan -Minutes 1)
Register-ScheduledTask `
    -TaskName "Zhixing Work Runner" `
    -Action $action `
    -Trigger $trigger `
    -Settings $settings `
    -Description "Runs the local Codex phone-line runner after sign-in" `
    -Force | Out-Null
Write-Host "Installed scheduled task: Zhixing Work Runner"
