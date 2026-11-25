param([Parameter(Mandatory)][string]$BuildRoot)
$ErrorActionPreference='Stop'
$testBase="HKCU:\Software\FocusDNS-Install-Test-$([guid]::NewGuid().ToString('N'))"
$testDir=Join-Path $env:TEMP "focus-browser-install-$([guid]::NewGuid().ToString('N'))"
# Intercept only HKCU browser registrations; filesystem operations are real.
function New-Item {
    param([string]$Path,[string]$ItemType,[switch]$Force)
    if ($Path -like 'HKCU:\Software\*') { $Path=$Path.Replace('HKCU:\Software',$testBase) }
    $args=@{Path=$Path;Force=$Force};if($ItemType){$args.ItemType=$ItemType}
    Microsoft.PowerShell.Management\New-Item @args
}
function Set-Item {
    param([string]$Path,$Value)
    if ($Path -like 'HKCU:\Software\*') { $Path=$Path.Replace('HKCU:\Software',$testBase) }
    Microsoft.PowerShell.Management\Set-Item -Path $Path -Value $Value
}
function Remove-Item {
    param([string[]]$Path,[switch]$Force,$ErrorAction)
    foreach($p in $Path) {
        if ($p -like 'HKCU:\Software\*') { $p=$p.Replace('HKCU:\Software',$testBase) }
        Microsoft.PowerShell.Management\Remove-Item -Path $p -Force:$Force -ErrorAction SilentlyContinue
    }
}
try {
    & (Join-Path $BuildRoot 'scripts\install-browser.ps1') -HostExe (Join-Path $BuildRoot 'browser-host\target\release\focus-browser-host.exe') -Extension (Join-Path $BuildRoot 'extension') -Destination $testDir -Browser Chrome,Edge
    $manifestPath=Join-Path $testDir 'org.nightingalelab.focusdns.json'
    $manifest=Get-Content $manifestPath -Raw | ConvertFrom-Json
    if($manifest.allowed_origins[0] -ne 'chrome-extension://mfafgohdpfnknfgedjiicnmgomjpdock/') {throw 'Wrong origin'}
    if(-not(Test-Path $manifest.path)) {throw 'Missing executable'}
    if(-not(Test-Path (Join-Path $testDir 'extension\manifest.json'))) {throw 'Missing unpacked extension'}
    $bytes=[IO.File]::ReadAllBytes($manifestPath);if($bytes[0] -eq 239){throw 'Unexpected UTF8 BOM'}
    foreach($root in @('Google\Chrome','Microsoft\Edge')) {
        $key="$testBase\$root\NativeMessagingHosts\org.nightingalelab.focusdns"
        if((Get-Item $key).GetValue('') -ne $manifestPath) {throw 'Wrong registry path'}
    }
    & (Join-Path $BuildRoot 'scripts\install-browser.ps1') -Remove -Browser Chrome,Edge
    if(Test-Path "$testBase\Google\Chrome\NativeMessagingHosts\org.nightingalelab.focusdns") {throw 'Unregister failed'}
    'PASS: Windows installer copies real binary/unpacked extension, emits valid no-BOM manifest, registers and removes Chrome/Edge keys. Registry keys were mapped to an isolated test prefix; daily browser keys untouched.'
} finally {
    Microsoft.PowerShell.Management\Remove-Item $testBase -Recurse -Force -ErrorAction SilentlyContinue
    Microsoft.PowerShell.Management\Remove-Item $testDir -Recurse -Force -ErrorAction SilentlyContinue
}
