# Runs in PowerShell with fake Windows DNS cmdlets and a temporary local settings folder.
$ErrorActionPreference = 'Stop'
$dns = Join-Path $PSScriptRoot '../scripts/dns.ps1'
$previous = $env:LOCALAPPDATA
$env:LOCALAPPDATA = Join-Path ([IO.Path]::GetTempPath()) ('focus-dns-test-' + [Guid]::NewGuid())
$data = Join-Path $env:LOCALAPPDATA 'FocusDNS'
$config = Join-Path $data 'nextdns.json'
$state = Join-Path $data 'state.json'
$servers = @('192.0.2.10', '198.51.100.20', '2001:db8::10', '2001:db8::20')
$active = @()
$policies = @()
$changes = 0

function Assert($condition, $message) {
    if (!$condition) { throw $message }
}
function Assert-Rejected([scriptblock]$operation) {
    try { & $operation | Out-Null } catch { return }
    throw 'Expected DNS operation to be rejected'
}
function Get-NetAdapter { [pscustomobject]@{ Status = 'Up'; ifIndex = 12; InterfaceGuid = 'mock' } }
function Get-DnsClientServerAddress { [pscustomobject]@{ ServerAddresses = $active } }
function Get-ItemPropertyValue { 1 }
function Get-DnsClientDohServerAddress {
    param($ServerAddress)
    if ($ServerAddress) { return $policies | Where-Object ServerAddress -eq $ServerAddress }
    $policies
}
function Set-DnsClientServerAddress { $script:changes++ }
function Add-DnsClientDohServerAddress { throw 'Unexpected DNS policy change' }
function Set-DnsClientDohServerAddress { throw 'Unexpected DNS policy change' }

try {
    Assert-Rejected { & $dns -Mode on }
    Assert-Rejected { & $dns -Mode off }
    Assert-Rejected { & $dns -Mode configure -Profile 'abcdef/extra' -Servers $servers }
    Assert-Rejected { & $dns -Mode configure -Profile 'abcdef' -Servers @('127.1', '192.0.2.1', '::1', '::2') }
    Assert (!(Test-Path $config)) 'Invalid or missing configuration must leave no saved profile'

    & $dns -Mode configure -Profile 'A1B2C3' -Servers $servers | Out-Null
    $saved = Get-Content -Raw $config | ConvertFrom-Json
    Assert ($saved.profile -ceq 'a1b2c3' -and !$saved.legacyConfirmed) 'Fresh setup should normalize the ID'
    Assert-Rejected { & $dns -Mode configure -Profile 'd4e5f6' -Servers $servers }
    Assert-Rejected { & $dns -Mode off }
    Assert-Rejected { & $dns -Mode on -Profile 'd4e5f6' }
    Remove-Item $config

    $policies = @($servers | ForEach-Object {
        [pscustomobject]@{
            ServerAddress = $_
            DohTemplate = 'https://dns.nextdns.io/a1b2c3'
            AllowFallbackToUdp = $false
            AutoUpgrade = $true
        }
    })
    & $dns -Mode recover
    Assert (!(Test-Path $config)) 'A fresh install must not infer ownership of preexisting Windows DNS'

    '{"paused_until":0}' | Set-Content $state
    & $dns -Mode recover
    Assert (!(Test-Path $config)) 'DoH mappings alone are insufficient outside a pause'
    $active = $servers
    & $dns -Mode recover
    $saved = Get-Content -Raw $config | ConvertFrom-Json
    Assert ($saved.profile -eq 'a1b2c3' -and $saved.legacyConfirmed) 'An active legacy configuration should be adopted'
    Assert ((& $dns -Mode status) -eq 'STATUS on') 'Migrated configuration should match active DNS'
    Assert ((& $dns -Mode peek-status) -eq 'STATUS on') 'Peek should accept the active configuration'
    Remove-Item $config

    $active = @()
    '{"paused_until":180000}' | Set-Content $state
    & $dns -Mode recover
    Assert (Test-Path $config) 'Windows keeps the strict DoH policies during a recorded pause'
    Remove-Item $config
    $policies += [pscustomobject]@{ DohTemplate = 'https://dns.nextdns.io/d4e5f6' }
    & $dns -Mode recover
    Assert (!(Test-Path $config)) 'Multiple profiles require input'
    Assert-Rejected { & $dns -Mode configure -Profile 'a1b2c3' -Servers $servers }
    & $dns -Mode configure -Profile 'a1b2c3' -Servers $servers -ConfirmLegacyProfile | Out-Null
    Assert ((Get-Content -Raw $config | ConvertFrom-Json).legacyConfirmed) 'Original profile can be confirmed explicitly'
    Assert ($changes -eq 0) 'Recovery and failed validation must preserve adapter settings'
    'Focus DNS configuration checks passed'
} finally {
    Remove-Item -LiteralPath $env:LOCALAPPDATA -Recurse -Force -ErrorAction SilentlyContinue
    $env:LOCALAPPDATA = $previous
}
