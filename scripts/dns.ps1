# FocusDNS: switches Windows to NextDNS (encrypted, DNS-over-HTTPS) and back.
# Needs admin. Modes:
#   on      point every physical adapter at NextDNS and turn on DoH for it
#   off     give adapters back their automatic (DHCP) DNS
#   status  prints "STATUS on" or "STATUS off"
#   peek-status  checks the adapter settings before fetching a blocked post
#   test    asks test.nextdns.io whether this machine is really using the profile
param(
    [ValidateSet('configure', 'recover', 'on', 'off', 'status', 'peek-status', 'test')][string]$Mode = 'status',
    [string]$Profile,
    [string[]]$Servers,
    [switch]$ConfirmLegacyProfile,
    [switch]$ResourceTelemetry
)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

$data = Join-Path $env:LOCALAPPDATA 'FocusDNS'
$configPath = Join-Path $data 'nextdns.json'

function New-Config($id, $addresses, [bool]$legacy) {
    if ($id -notmatch '\A[0-9a-fA-F]{6}\z') { throw 'Enter a six-character NextDNS profile ID.' }
    $v4 = @()
    $v6 = @()
    foreach ($address in $addresses) {
        $ip = $null
        if ($address -notmatch '\A[0-9a-fA-F:.]+\z' -or
            ![IPAddress]::TryParse($address, [ref]$ip) -or $ip.IsIPv4MappedToIPv6) {
            throw 'Use the IPv4 and IPv6 DNS addresses listed in NextDNS Setup.'
        }
        if ($ip.AddressFamily -eq 'InterNetwork') {
            if ($ip.ToString() -cne $address) { throw 'Enter complete IPv4 addresses.' }
            $v4 += $ip.ToString()
        } else {
            $v6 += $ip.ToString()
        }
    }
    if (@($v4 | Select-Object -Unique).Count -ne 2 -or $v4.Count -ne 2 -or
        @($v6 | Select-Object -Unique).Count -ne 2 -or $v6.Count -ne 2) {
        throw 'Enter two distinct IPv4 and two distinct IPv6 DNS addresses.'
    }
    @{ profile = $id.ToLowerInvariant(); servers = $v4 + $v6; legacyConfirmed = $legacy }
}

function Save-Config($config) {
    New-Item -ItemType Directory -Path $data -Force | Out-Null
    $temp = Join-Path $data ([Guid]::NewGuid().ToString() + '.tmp')
    try {
        [IO.File]::WriteAllText($temp, ($config | ConvertTo-Json -Compress))
        # File.Move refuses to replace a concurrently created configuration.
        [IO.File]::Move($temp, $configPath)
    } finally {
        Remove-Item -LiteralPath $temp -ErrorAction SilentlyContinue
    }
}

function Get-SavedState {
    $path = Join-Path $data 'state.json'
    if (Test-Path -LiteralPath $path) {
        Get-Content -LiteralPath $path -Raw | ConvertFrom-Json
    }
}

function Test-SavedPause($state) {
    ($state.paused_until -is [int] -or $state.paused_until -is [long]) -and $state.paused_until -gt 0
}

function Get-DohRoot($a) {
    "HKLM:\SYSTEM\CurrentControlSet\Services\Dnscache\InterfaceSpecificParameters\$($a.InterfaceGuid)\DohInterfaceSettings"
}

function Test-Protection($config, $adapters) {
    $up = @($adapters | Where-Object Status -eq 'Up')
    if ($up.Count -eq 0) { return $false }
    foreach ($a in $up) {
        $actual = @(Get-DnsClientServerAddress -InterfaceIndex $a.ifIndex |
            ForEach-Object { $_.ServerAddresses } | Where-Object { $_ } |
            ForEach-Object { [IPAddress]::Parse($_).ToString() })
        if ($actual.Count -ne $config.servers.Count -or
            (Compare-Object -ReferenceObject $config.servers -DifferenceObject $actual)) {
            return $false
        }
        foreach ($ip in $config.servers) {
            $family = if ($ip -like '*:*') { 'Doh6' } else { 'Doh' }
            $flags = Get-ItemPropertyValue -Path "$(Get-DohRoot $a)\$family\$ip" -Name DohFlags -ErrorAction SilentlyContinue
            if ($flags -ne 1) { return $false }
        }
    }
    foreach ($ip in $config.servers) {
        $policy = Get-DnsClientDohServerAddress -ServerAddress $ip -ErrorAction SilentlyContinue
        if (!$policy -or $policy.DohTemplate -ne "https://dns.nextdns.io/$($config.profile)" -or
            $policy.AllowFallbackToUdp -or !$policy.AutoUpgrade) { return $false }
    }
    return $true
}

if ($Mode -eq 'configure') {
    if (Test-Path -LiteralPath $configPath) { throw 'A NextDNS configuration is already saved.' }
    $state = Get-SavedState
    if (((Test-SavedPause $state) -or $state.scoped_pause -or
        ($state.pending_grants -and @($state.pending_grants.PSObject.Properties).Count -gt 0)) -and
        !$ConfirmLegacyProfile) {
        throw 'An earlier pause or grant needs its original profile. Confirm that profile with -ConfirmLegacyProfile.'
    }
    $candidate = New-Config $Profile $Servers ([bool]$ConfirmLegacyProfile)
    if ($state.profile -and $state.profile -ne $candidate.profile) {
        throw 'This installation already used a different profile. Enter its original profile.'
    }
    Save-Config $candidate
    'NextDNS configuration saved. Restart Focus to apply it.'
    return
}

$Adapters = @(Get-NetAdapter -Physical | Where-Object { $_.Status -ne 'Disabled' })
if ($Mode -eq 'recover') {
    if (Test-Path -LiteralPath $configPath) { return }
    $state = Get-SavedState
    if (!$state) { return }
    # The old pause reset adapter DNS but left the system-wide DoH entries intact.
    $groups = @(Get-DnsClientDohServerAddress |
        Where-Object { $_.DohTemplate -match '\Ahttps://dns\.nextdns\.io/[0-9a-fA-F]{6}\z' } |
        Group-Object DohTemplate)
    if ($groups.Count -ne 1) { return }
    $group = $groups[0]
    if (@($group.Group | Where-Object { $_.AllowFallbackToUdp -or !$_.AutoUpgrade }).Count -gt 0) { return }
    try {
        $candidate = New-Config ($group.Name.Split('/')[-1]) $group.Group.ServerAddress $true
    } catch {
        return
    }
    if ($state.profile -and $state.profile -ne $candidate.profile) { return }
    if ((Test-SavedPause $state) -or (Test-Protection $candidate $Adapters)) {
        Save-Config $candidate
    }
    return
}

if (!(Test-Path -LiteralPath $configPath)) { throw 'NextDNS setup is required. See the Focus installation guide.' }
try {
    $saved = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
    $config = New-Config $saved.profile $saved.servers ($saved.legacyConfirmed -ceq $true)
} catch {
    throw 'The saved NextDNS configuration is invalid. See the Focus installation guide.'
}
if ($Profile -and $Profile -cne $config.profile) {
    throw 'The saved NextDNS profile changed. Restart Focus before applying it.'
}
$Template = "https://dns.nextdns.io/$($config.profile)"
$All = $config.servers

switch ($Mode) {
    'on' {
        # Tell Windows which DoH URL belongs to each NextDNS address.
        foreach ($ip in $All) {
            $p = @{ ServerAddress = $ip; DohTemplate = $Template; AllowFallbackToUdp = $false; AutoUpgrade = $true }
            if (Get-DnsClientDohServerAddress -ServerAddress $ip -ErrorAction SilentlyContinue) {
                Set-DnsClientDohServerAddress @p | Out-Null
            } else {
                Add-DnsClientDohServerAddress @p | Out-Null
            }
        }
        foreach ($a in $Adapters) {
            Set-DnsClientServerAddress -InterfaceIndex $a.ifIndex -ServerAddresses $All
            # Same as Settings > Network > DNS > "DNS over HTTPS: On (automatic template)".
            foreach ($ip in $All) {
                $fam = if ($ip -like '*:*') { 'Doh6' } else { 'Doh' }
                $k = "$(Get-DohRoot $a)\$fam\$ip"
                New-Item -Path $k -Force | Out-Null
                New-ItemProperty -Path $k -Name DohFlags -PropertyType QWord -Value 1 -Force | Out-Null
            }
            "Adapter configured"
        }
        Clear-DnsClientCache
    }
    'off' {
        if (!(Test-Protection $config $Adapters)) {
            throw 'Focus DNS protection could not be confirmed. Adapter DNS was left in place.'
        }
        foreach ($a in $Adapters) {
            Set-DnsClientServerAddress -InterfaceIndex $a.ifIndex -ResetServerAddresses
            Remove-Item -Path (Get-DohRoot $a) -Recurse -Force -ErrorAction SilentlyContinue
            "Adapter reset"
        }
        Clear-DnsClientCache
    }
    { $_ -in 'status', 'peek-status' } {
        if ($Mode -eq 'status' -and !($Adapters | Where-Object Status -eq 'Up')) { 'STATUS offline'; return }
        if (Test-Protection $config $Adapters) { 'STATUS on' } else { 'STATUS off' }
    }
    'test' {
        try {
            # test.nextdns.io wants a fresh random subdomain, so no cached answer can fool it.
            $sub = -join ((97..122) | Get-Random -Count 20 | ForEach-Object { [char]$_ })
            if ($ResourceTelemetry) { 'FOCUS_DNS_PROBE' }
            $response = Invoke-WebRequest -UseBasicParsing -Uri "https://$sub.test.nextdns.io/" -TimeoutSec 8
            if ($ResourceTelemetry) { "FOCUS_DNS_RX_BYTES $($response.RawContentLength)" }
            $r = $response.Content | ConvertFrom-Json
            $matchesProfile = $r.status -eq 'ok' -and $r.profile -eq $config.profile
            "TEST expected_profile=$matchesProfile"
        } catch {
            'TEST probe failed'
        }
    }
}
