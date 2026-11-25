# Run as the browser user (no admin). Does not start FocusDNS or change DNS.
param(
    [string]$HostExe,
    [string]$Extension = (Join-Path $PSScriptRoot '..\extension'),
    [ValidateSet('Chrome','Edge','Brave','Chromium')][string[]]$Browser = @('Chrome','Edge'),
    [string]$Destination = (Join-Path $env:LOCALAPPDATA 'FocusDNS\browser-bridge'),
    [switch]$Remove
)
$ErrorActionPreference = 'Stop'
$hostName = 'org.nightingalelab.focusdns'
$roots = @{ Chrome='Google\Chrome'; Edge='Microsoft\Edge'; Brave='BraveSoftware\Brave-Browser'; Chromium='Chromium' }
if ($Remove) {
    foreach ($b in $Browser) { Remove-Item "HKCU:\Software\$($roots[$b])\NativeMessagingHosts\$hostName" -Force -ErrorAction SilentlyContinue }
    Write-Output 'Registration removed. Disable/remove the extension in the browser. History is retained.'
    exit
}
if (-not $HostExe) { throw '-HostExe is required unless -Remove is used.' }
$sourceExe = (Resolve-Path $HostExe).Path
$sourceExtension = (Resolve-Path $Extension).Path
New-Item -ItemType Directory -Force $Destination | Out-Null
$targetExe = Join-Path $Destination 'focus-browser-host.exe'
if ($sourceExe -ne $targetExe) { Copy-Item $sourceExe $targetExe -Force }
$targetExtension = Join-Path $Destination 'extension'
New-Item -ItemType Directory -Force $targetExtension | Out-Null
Copy-Item (Join-Path $sourceExtension '*') $targetExtension -Recurse -Force
$manifest = @{
    name=$hostName; description='Focus local browser activity bridge'; path=$targetExe; type='stdio'
    allowed_origins=@('chrome-extension://mfafgohdpfnknfgedjiicnmgomjpdock/')
}
$manifestPath = Join-Path $Destination "$hostName.json"
# UTF-8 without BOM, also on Windows PowerShell 5.
[IO.File]::WriteAllText($manifestPath, ($manifest | ConvertTo-Json -Depth 4), (New-Object Text.UTF8Encoding($false)))
foreach ($b in $Browser) {
    $key = "HKCU:\Software\$($roots[$b])\NativeMessagingHosts\$hostName"
    New-Item -Path $key -Force | Out-Null
    Set-Item -Path $key -Value $manifestPath
}
Write-Output "Bridge registered for $($Browser -join ', ')."
Write-Output "Load unpacked: $targetExtension"
Write-Output 'Extension ID must be mfafgohdpfnknfgedjiicnmgomjpdock. Native host runs as your browser user.'
