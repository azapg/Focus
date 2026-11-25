# Removes FocusDNS: stops it, removes the login task, gives DNS back to automatic.
$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
    Start-Process powershell.exe -Verb RunAs -ArgumentList "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`"" -Wait
    exit
}
$dir = Join-Path $env:ProgramFiles 'FocusDNS'
Get-NetFirewallRule -Name 'FocusDNS-Discovery' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
Unregister-ScheduledTask -TaskName FocusDNS -Confirm:$false -ErrorAction SilentlyContinue
Get-Process FocusDNS -ErrorAction SilentlyContinue | Stop-Process -Force
try {
    & (Join-Path $PSScriptRoot 'dns.ps1') -Mode off
} catch {
    Write-Warning ("Windows DNS settings were preserved. " + $_.Exception.Message)
}
Remove-Item $dir -Recurse -Force -ErrorAction SilentlyContinue
"FocusDNS removed. Logs kept in $(Join-Path $env:LOCALAPPDATA 'FocusDNS')"
