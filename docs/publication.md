# Publishing Focus

## First public snapshot

Have the owner review the source and fictional examples in the private review branch.
Start the public repository from those approved files with a fresh initial commit.
Keep the existing private repository, its history, issues, logs and releases private.

## Release continuity

Keep the public workflow disabled during initial staging. Scheduled runs can
publish whenever either rolling manifest points at a different SHA. A new repo
with no nightly is eligible on its first schedule, and dispatch on `main` also
publishes. Establish the intended publishing controls before enabling either path.

- **Windows updater.** The running app checks the GitHub repo and tag in its
  configuration, falling back to the repo embedded at build time. A replacement
  build does not overwrite a saved `repo` or `tag` in `updater.json`. Old
  installations still look at the old destination. Test a signed transition
  through the old channel or a documented manual switch and reinstall, including
  installations with saved configuration. Verify the public key and signed
  manifest format, app and bridge filenames, native messaging host and extension
  IDs, scheduled task, data directory, and rollback. Windows checks commit identity
  for availability, so a stale release with a different SHA can also appear as an
  update. Prevent opposing feeds from offering one another's older binaries.
- **Android phone.** Preserve `org.nightingalelab.focus.phone` and the signing
  certificate accepted by actual installed builds. The updater itself compares
  certificate sets and requires a strictly higher `versionCode`. Check the first
  public code against the highest distributed build, including any overrides or
  local installs. The default derives from build time, while `versionName` is
  display metadata. The old app uses its previous feed, and any saved repo or tag
  takes precedence over the new default. Plan how a transition build will handle
  saved destinations, cached updates and obsolete private tokens. Test it in place.
  Check installs signed with the nightly key and any separate local signing key.
  [Android update requirements](https://developer.android.com/google/play/app-updates)
- **Android TV.** The TV APK currently has a version code in its manifest and uses
  a local release keystore. The nightly workflow does not publish TV. Decide
  whether to distribute it and preserve the TV package ID, installed certificate
  and a suitable version code if offering in-place updates.
- **Build and notes.** Nightly builds embed the active GitHub repository using
  `FOCUS_UPDATER_REPO`. Local Windows and Android scripts use a GitHub origin when
  one is present and accept an explicit override. Raw Cargo builds can take the
  same variable directly. An empty value means there is no configured nightly feed.
  Verify manifests, hashes, signing continuity, app and bridge downloads, and
  update behavior using disposable installations. Keep signing material in the
  trusted publishing environment. Commit subjects can appear in Android manifest
  metadata. The release-note script can send commit subjects and bodies to an
  external model and falls back to commit subjects when the previous SHA is
  unknown. Start notes from public history, review both generated and fallback
  text, and review the public assets before enabling automated publication.

## Fork PRs

Keep fork checks on the `pull_request` event with read-only permissions and no
release credentials. The planning job needs read access to list changed PR files.
Use isolated hosted runners for untrusted contributions. Protect the publisher and
its signing secrets with branch and environment restrictions. Keep privileged
jobs on trusted refs and review changes to build scripts and dependencies before
they enter a release. Use a test fork to verify the approval prompt, path planning,
read-only access, artifact contents and that both publishing jobs are skipped.
Use the destination's settings to choose which outside contributors need approval.
[GitHub fork workflow settings](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/enabling-features-for-your-repository/managing-github-actions-settings-for-a-repository)

## Decisions before cutover

- Confirm the owner and namespace for the planned `Focus` repository and approve
  the publication license and bundled asset rights.
  [GitHub licensing guidance](https://docs.github.com/en/repositories/managing-your-repositorys-settings-and-features/customizing-your-repository/licensing-a-repository)
- Choose the Windows and phone transition, the first Android version code, when
  to retire the old channel, and the rollback path. Decide whether to ship TV.
- Decide who can approve fork runs and publish signed builds. Confirm that only
  code from the reviewed public tree enters public CI and release jobs.
