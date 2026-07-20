$ErrorActionPreference = "Stop"
Unregister-ScheduledTask -TaskName "Zhixing Work Runner" -Confirm:$false -ErrorAction SilentlyContinue
Write-Host "Removed scheduled task: Zhixing Work Runner"
