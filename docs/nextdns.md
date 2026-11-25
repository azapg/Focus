# NextDNS setup and upgrades

Focus uses the NextDNS profile you choose. Get its six-character ID from the
profile's **Setup** page. Devices using the same profile share its filtering
rules and any temporary allowlist changes made by Ask.

## Windows

Run the setup command from a current checkout in an elevated PowerShell window
after installing Focus. Replace every placeholder with the profile ID and the
two IPv4 and two IPv6 server addresses listed on that profile's NextDNS Setup
page.

```powershell
.\scripts\dns.ps1 -Mode configure -Profile '<profile-id>' -Servers @(
    '<first IPv4>', '<second IPv4>', '<first IPv6>', '<second IPv6>'
)
```

The command saves the configuration in `%LOCALAPPDATA%\FocusDNS\nextdns.json`.
Apply it under that same Windows account by restarting the installed task.

```powershell
Stop-ScheduledTask -TaskName FocusDNS
Start-ScheduledTask -TaskName FocusDNS
```

Focus uses the configured addresses with encrypted DNS-over-HTTPS. Open the
Windows **This PC · NextDNS** settings card to see whether setup is required, or run
`.\scripts\dns.ps1 -Mode test` after Focus starts to check whether the expected
profile is answering.

On upgrade Focus can reuse the earlier Windows DoH configuration when it
finds a single profile with all four strict DoH mappings. The active physical
adapters must match those mappings, except during a recorded Focus pause, when
Windows retains the system-wide mappings while the adapters use automatic DNS.
An unexpired pause keeps its deadline.

If the mappings are missing, ambiguous, or differ from what is active, Focus
leaves Windows DNS in place and shows **NextDNS setup required**. Enter the
original profile and server addresses and restart. If setup detects an earlier
pause or scoped grant, add `-ConfirmLegacyProfile` only after you have verified
that the supplied profile is the one used by the previous installation. This
lets Focus resume a pending pause and clean up older untagged allowlist changes.
If you cannot identify that profile, inspect the original profile's allowlist
in the NextDNS dashboard before clearing any journal or changing DNS. Keep
`state.json` and `nextdns.json` for recovery.

The Chromium extension and native host use the configured Windows profile.
After upgrading, reload the Focus extension from Chromium's extensions page
and restart the browser to pick up the updated host. If you loaded the
extension manually from a different directory, update those files from the
current checkout before reloading. Until the host receives a valid
configuration, the extension skips new NextDNS lookups and Peek is unavailable.

## Android phone and TV

- **Phone:** Open **Settings → NextDNS profile**, save the ID, then choose
  **Connect NextDNS** in **This Phone**. Grant the documented Android settings
  permission before connecting.
- **TV:** Open **NextDNS**, enter the profile ID and select **Connect**.
  The paired Windows settings panel can also send the ID to the TV.

Upgrades keep a profile already saved in Focus. For an older enabled
installation with an empty profile preference, Focus can recover a standard
`<profile-id>.dns.nextdns.io` hostname from Android's strict Private DNS
settings or from the retained hostname during a recorded Focus pause. If it
cannot recover the hostname, **NextDNS profile needed** appears and Focus
leaves Private DNS alone. Enter the original ID to restore management. An
unexpired pause keeps its deadline. Focus retains the pre-Focus settings used
by **Restore previous DNS**.
