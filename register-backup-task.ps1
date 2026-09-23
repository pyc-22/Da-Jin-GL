[CmdletBinding()]
param([string]$TaskName = 'DajinSystem-MySQL-Backup')
$script = Join-Path $PSScriptRoot 'backup.ps1'
$action = New-ScheduledTaskAction -Execute 'pwsh.exe' -Argument "-NoProfile -ExecutionPolicy Bypass -File `"$script`""
$trigger = New-ScheduledTaskTrigger -Daily -At 2:00am
Register-ScheduledTask -TaskName $TaskName -Action $action -Trigger $trigger -Description 'Dajin system daily MySQL backup' -Force
Write-Host "Registered $TaskName for daily 02:00 backups."
