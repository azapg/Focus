# Clean streak trial

The native trial is off by default. It needs an explicit opt-in on the Windows
authority and on each enrolled Android phone. Use a disposable profile with
fictional activity for testing.

- On each Android phone, place a file named `streak-trial` containing the paired Windows laptop ID in Focus's
  private files directory and restart its monitor. Only a phone with one
  approved manager includes coverage in its authenticated snapshot.
- In the Windows Focus data directory, place `streak-trial.json` with a
  `reporters` array containing the paired phone IDs. Use an empty array for
  a desktop-only trial. Enrollment waits for each selected phone to connect
  and report its opt-in.
- The laptop is the one authority. Enrollment fixes the reporter IDs and
  the laptop's current calendar timezone. Streak days and a previously dormant
  wallet start on the next calendar day. The wallet start syncs to paired devices.
  If the enrollment or timezone changes, finalization stops.
- Remove the Windows file to stop the trial. Remove the Android marker and
  restart the monitor to stop sending phone coverage.

The existing Windows device sync loop attempts to finalize the oldest open
day in order. Incomplete days can wait up to 24 hours past local midnight.
The native desktop Store can read `streak_view`, which returns null when the
authority is inactive, and call `streak_buy_freeze` with a stable
`requestId`. The backend checks the wallet and applies the trial price.
The next day is the earliest the freeze can apply.

For a paired trial, suspend Windows and turn off the paired Android screen
across profile midnight. Wake both and confirm coverage reaches the laptop.
Replay resets and retries to verify one final day and one award or consumed
freeze. Repeat with missing evidence to check Pending and then Unverified.
Android Store callers use `StreakAuthority.view` and `buyFreeze` on a worker thread. The authority accepts only a reciprocal bearer token issued to an enrolled phone. The Store UI remains a separate integration.
