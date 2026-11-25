# Installs FocusDNS to Program Files (only admins can change it) and starts it at every login with admin rights
# (so it can change DNS without a UAC prompt each time). Asks for admin once.
#   -Exe       path to the built focus-dns.exe
#   -SelfTest  check the configured profile after starting Focus
param([Parameter(Mandatory)][string]$Exe, [switch]$SelfTest)

$admin = ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $admin) {
    $a = "-NoProfile -ExecutionPolicy Bypass -File `"$PSCommandPath`" -Exe `"$Exe`""
    if ($SelfTest) { $a += ' -SelfTest' }
    Start-Process powershell.exe -Verb RunAs -ArgumentList $a -Wait
    exit
}

$ErrorActionPreference = 'Stop'
$data = Join-Path $env:LOCALAPPDATA 'FocusDNS'     # logs and state
$dir = Join-Path $env:ProgramFiles 'FocusDNS'      # the app itself
New-Item -ItemType Directory -Force $data, $dir | Out-Null
Start-Transcript -Path (Join-Path $data 'install.log') -Force | Out-Null

Get-Process FocusDNS -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Milliseconds 500
Copy-Item $Exe (Join-Path $dir 'FocusDNS.exe') -Force
Copy-Item (Join-Path $PSScriptRoot 'dns.ps1') (Join-Path $dir 'dns.ps1') -Force
# Older versions lived in the (user-writable) data folder.
Remove-Item (Join-Path $data 'FocusDNS.exe'), (Join-Path $data 'dns.ps1') -Force -ErrorAction SilentlyContinue

$user = "$env:USERDOMAIN\$env:USERNAME"
$trigger = New-ScheduledTaskTrigger -AtLogOn -User $user
$trigger.Delay = 'PT5S'
$task = @{
    TaskName  = 'FocusDNS'
    Action    = New-ScheduledTaskAction -Execute (Join-Path $dir 'FocusDNS.exe') -Argument '--from-task' -WorkingDirectory $dir
    Trigger   = $trigger
    Principal = New-ScheduledTaskPrincipal -UserId $user -LogonType Interactive -RunLevel Highest
    Settings  = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries `
                  -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero) `
                  -RestartCount 999 -RestartInterval (New-TimeSpan -Minutes 1) `
                  -MultipleInstances IgnoreNew -Priority 5
}
Register-ScheduledTask @task -Force | Out-Null
"task registered for $user (delay=$($trigger.Delay), startWhenAvailable=True, restart=999x1m)"

# Allow only this app's mDNS traffic from the local subnet, including home Wi-Fi
# that Windows classifies as Public. This opens no Windows management server.
Get-NetFirewallRule -Name 'FocusDNS-Discovery' -ErrorAction SilentlyContinue | Remove-NetFirewallRule
New-NetFirewallRule -Name 'FocusDNS-Discovery' -DisplayName 'FocusDNS nearby devices' `
    -Program (Join-Path $dir 'FocusDNS.exe') -Direction Inbound -Action Allow `
    -Protocol UDP -LocalPort 5353 -RemoteAddress LocalSubnet -Profile Any | Out-Null

Start-ScheduledTask -TaskName FocusDNS
"started"
if ($SelfTest) {
    $dns = Join-Path $dir 'dns.ps1'
    try {
        & $dns -Mode recover
        $status = & $dns -Mode status
        $result = @($status)
        if ($status -eq 'STATUS on') { $result += & $dns -Mode test }
        $result | Tee-Object -FilePath (Join-Path $data 'selftest.txt')
    } catch {
        Write-Warning $_.Exception.Message
    }
}
Stop-Transcript | Out-Null
