# Session Android — release & CI scripts

Helper scripts for cutting a release, pushing builds to Google Play / F-Droid,
and the Drone CI artifact plumbing.

Most of the Python scripts need `gh` (GitHub CLI) and read their secrets from the
OS keyring (see `_keyring.py`). `build-and-release.py` additionally needs
`fdroid` and the Google API client libraries; `play-store.py` needs the Google
API client libraries.

## Release pipeline (run in this order)

A full release is four scripts, run in sequence:

1. **`prepare-release.py <version>`** — forks a `release/<version>` branch off
   `master` (or `--from <branch>`), commits a `canonicalVersionCode` /
   `canonicalVersionName` bump, pushes it, and creates a GitHub **draft** release
   targeting the branch. Then it stops — you merge/cherry-pick the payload into
   the branch yourself. `--dry-run` shows the plan without touching anything.

2. **`update-release-notes.py <version>`** — regenerates the draft's changelog
   from the branch's merged-PR titles (GitHub's own "What's Changed" generator)
   and overwrites the release body. Re-run whenever the branch changes; run it
   **before** the PGP-signing step, since it clobbers the whole body.

3. **`build-and-release.py`** — builds the signed release artifacts for all
   distribution flavors: `play` (AAB + split APKs), `fdroid`, and `huawei`.
   Signing creds come from the keyring (`release-creds`, a TOML blob). Unless
   `--build-only` is given it also opens an F-Droid release PR
   (`session-foundation/session-fdroid`) and uploads the artifacts to the GitHub
   draft release. `--build-type` overrides the default `release`.

4. **`play-store.py upload`** — uploads the built AAB to Google Play (see below),
   then you publish the draft (`gh release edit <version> --draft=false`).

## Dev / test build to the internal track

When you just want a real, signed build on the **internal** testing track —
e.g. to exercise real Google Play Billing purchases against a dev Pro backend —
skip the whole pipeline above (no release branch, version-name bump, notes,
F-Droid PR, or GitHub draft). Because the build is release-signed and lives on a
track, Play Billing works; as a license tester the purchases are real (backend
validates the token) but uncharged.

1. Build a signed `play` AAB, using the signing creds from the keyring:

   ```sh
   scripts/build-and-release.py --play-only
   ```

   `--play-only` skips the `fdroid`/`huawei` flavors and every upload/PR step, so
   it needs neither `fdroid`/`gh` nor huawei creds — just the `release-creds`
   entry. (If you'd rather not use the keyring at all, you can build the bundle
   straight from gradle with `./gradlew :app:bundlePlayRelease -PSESSION_STORE_FILE=…
   -PSESSION_STORE_PASSWORD=… -PSESSION_KEY_ALIAS=… -PSESSION_KEY_PASSWORD=…`.)

2. Make sure `versionCode` is higher than anything already on the track — check
   with `play-store.py status`, and bump `canonicalVersionCode` in
   `app/build.gradle.kts` if needed (a throwaway local bump is fine here).

3. Upload to the internal track:

   ```sh
   scripts/play-store.py upload                            # internal, as a draft
   scripts/play-store.py upload --track internal --submit  # live to testers (no review)
   ```

4. Opt in as a tester (Play Console → internal testing → testers list + the
   opt-in URL) and install from Play on a device signed into that account.

Once one build is on the track you don't need to re-upload for every change: a
locally-built, identically-signed `./gradlew :app:installPlayRelease` with the
**same** versionCode installs over adb and Play Billing keeps working — only
bump + re-upload when you deliberately move the versionCode.

### Fastest loop: a local signed build onto an emulator

For iterating on Pro (or anything needing Google's signature + Play Billing) against a
running emulator, without touching a track at all. This is what "signed but mostly local"
means: the **app** is built locally, but against the **published** libsession glue AAR and
Google-signed via Internal App Sharing (which consumes no versionCode, so you can re-upload
the same code freely).

1. **Pick the glue source.** `local.properties` may point the build at a local
   `libsession-util-android` checkout (a composite build) via
   `session.libsession_util.project.path=…`. To build against the **published** AAR instead —
   the production path, and what CI uses — **comment that line out**:

   ```
   #session.libsession_util.project.path=/…/libsession-util-android
   ```

   Prefer the published AAR unless you're specifically testing local glue changes: the local
   composite can hit AGP/Kotlin-plugin mismatches, and the published AAR is the faithful
   production test.

2. **Build the signed AAB.** Use a **JDK 17 or 21** — *not* 25 (a too-new JDK, e.g. an Android
   Studio bundled JBR that has moved to 25, breaks Gradle), so set `JAVA_HOME` explicitly:

   ```sh
   JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 scripts/build-and-release.py --play-only
   # → app/build/outputs/bundle/playRelease/app-play-release.aab
   ```

3. **Push it onto the emulator via Internal App Sharing:**

   ```sh
   scripts/play-store.py share --open-emu   # upload + fire the link at a running emulator
   ```

   `--open-emu` uploads, sends the install link to the running emulator, and polls the Play
   Store UI until the **Install/Update** button appears; then **tap it on the emulator** to
   install (the script surfaces the button but doesn't tap for you). The emulator must be a
   **Play Store image** (`google_apis_playstore`) for Internal App Sharing to work.

   Without `--open-emu`, plain `scripts/play-store.py share` just prints the install link —
   open it in the emulator's own browser to land on the same Play Store install page.

## Google Play

**`play-store.py`** — command-line control of the Google Play release via the
Play Developer API (service-account key from the keyring, name
`play-service-account`). Subcommands:

- `status` — list each track's releases (version codes, status, rollout %).
- `upload [AAB]` — upload a bundle to a track; **defaults to the `internal`
  track as a draft**. `--track {internal,alpha,beta,production}`, `--submit`
  (full rollout) or `--rollout FRACTION` (staged), `--dry-run`.
- `rollout {FRACTION|complete|halt}` — change an existing staged rollout without
  re-uploading (defaults to `--track production`).
- `share [APK|AAB]` — upload to **Internal App Sharing** and print an install
  link. Unlike a track release this consumes **no** versionCode (re-upload the
  same code freely) and needs no release/rollout/track management, so it's the
  fast loop for iterating on a build that still needs Google's signature (e.g. to
  exercise Play Billing). Add **`--open-emu`** to make it fully hands-off: it
  sends the link to a running Play Store emulator (launching a
  `google_apis_playstore` AVD if none is running), falling back to printing the
  `adb` command if there isn't a single suitable emulator. Play processes each
  uploaded artifact before the link resolves to it — until then the link shows
  the *installed* build ("Open") not the new one ("Update"). There's no
  off-device readiness signal (the upload API exposes no status field, and the
  `downloadUrl` just 302s to a Google login for any HTTP client), so `--open-emu`
  **polls the on-device Play Store UI** (via `uiautomator`): it fires the link,
  and if the Install/Update button isn't up yet it waits and re-fires — up to
  `--open-emu-timeout` seconds (default 120), every `--open-emu-interval` (default
  5). Re-firing also defeats any device-side cache. If it times out, the link is
  left on screen to re-fire manually.
  Whole loop: `build-and-release.py --play-only && play-store.py share --open-emu`.

## Secrets

**`_keyring.py`** — thin cross-platform wrapper over the `keyring` library. Used
as a helper by the release scripts (`get_secret`), and as a CLI to store secrets
once so they never sit on disk in plaintext:

```sh
python3 scripts/_keyring.py set release-creds        < release-creds.toml
python3 scripts/_keyring.py set play-service-account < service-account.json
# ...then delete the plaintext files. `get` / `del` subcommands also exist.
```

## Drone CI (invoked by CI, not run by hand)

- **`drone-static-upload.sh`** — packages the universal APK into a
  `session-android-<tag-or-datetime-commit>-universal.tar.xz` and SFTP-uploads it
  to `oxen.rocks` (needs `SSH_KEY` in the environment).
- **`drone-upload-exists.sh`** — for a PR build, polls `oxen.rocks` for up to 30
  minutes for an already-uploaded artifact matching the PR's fork/branch, so CI
  can skip a redundant rebuild. Exits 0 if found.
