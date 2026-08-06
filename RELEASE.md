Building and Releasing Session
==============================

## Building

### Dependencies

Session uses standard Gradle/Android project structure.

Ensure the following are set up on your machine:

- **Android Studio** (recommended) or the Android SDK command-line tools
- **JDK 17** or later
- The following packages installed via the Android SDK Manager:
  - Android SDK Build Tools (see `buildToolsVersion` in `build.gradle`)
  - SDK Platform matching the `compileSdkVersion` in `build.gradle`
  - Android Support Repository

### Setting up and building in Android Studio

[Android Studio](https://developer.android.com/studio) is the recommended development environment.

1. Open Android Studio. On a new installation the Quickstart panel will appear. If you have
   open projects, close them via **File > Close Project** to return to the Quickstart panel.
2. Choose **Get from Version Control** and paste the repository URL:
   `https://github.com/session-foundation/session-android.git`
3. The default Gradle sync and build configuration should work out of the box.

### Build flavors

The project has three product flavors:

| Flavor   | Description                                  |
|----------|----------------------------------------------|
| `play`   | Google Play Store build                      |
| `fdroid` | F-Droid build (no proprietary dependencies)  |
| `huawei` | Huawei AppGallery build (HMS push)           |

To build a specific flavor manually, run the corresponding Gradle task, for example:

```sh
./gradlew assemblePlayDebug
./gradlew assembleFdroidDebug
./gradlew assembleHuaweiDebug -Phuawei
```

The `-Phuawei` flag is required for Huawei builds to include the HMS dependencies. If building
in Android Studio, add `-Phuawei` under
**Preferences > Build, Execution, Deployment > Gradle-Android Compiler > Command-line Options**.

### Building a signed release APK manually

The build is standard Gradle — `build-and-release.py` is a convenience wrapper and is not
required if you only need to produce a signed APK. Pass the signing credentials directly as
Gradle properties:

```sh
./gradlew \
  -PSESSION_STORE_FILE='/path/to/keystore.jks' \
  -PSESSION_STORE_PASSWORD='<keystore-password>' \
  -PSESSION_KEY_ALIAS='<key-alias>' \
  -PSESSION_KEY_PASSWORD='<key-password>' \
  assembleFdroidRelease
```

The keystore file can be recreated from the stored credentials — the `keystore` field in each
section is the keystore file base64-encoded, so it can be decoded back to a keystore file with
any standard base64 tool, e.g.:

```sh
python3 scripts/_keyring.py get release-creds \
  | python3 -c 'import sys,tomllib,base64; open("keystore.jks","wb").write(base64.b64decode(tomllib.loads(sys.stdin.read())["build"]["play"]["keystore"]))'
```

---

## Release process

> **Quick checklist**
>
> 1. [ ] `scripts/prepare-release.py MAJOR.MINOR.PATCH` — creates the release branch off
>        `master`, bumps the version, pushes, and creates the GitHub draft release
> 2. [ ] Merge `dev` into the release branch (full release) or cherry-pick patch commits, then push
> 3. [ ] `scripts/update-release-notes.py MAJOR.MINOR.PATCH` — (re)generate the draft's changelog
> 4. [ ] One-time: store the signing credentials in your keyring (see Credentials below)
> 5. [ ] `scripts/build-and-release.py` from the release branch — builds, opens the F-Droid PR,
>        and uploads artifacts to the GitHub draft
> 6. [ ] Sign the release files and append the signature block (your release-signing script)
> 7. [ ] `scripts/play-store.py upload [--track …] [--submit|--rollout F]` — upload the AAB to Play
> 8. [ ] Review and merge the automated F-Droid PR in `session-foundation/session-fdroid`
> 9. [ ] Publish the GitHub draft: `gh release edit MAJOR.MINOR.PATCH --draft=false`

The GitHub draft (step 1) and the keyring credentials (step 4) must exist **before**
`build-and-release.py` runs: without a draft the artifact upload is silently skipped, and
without the credentials the script exits immediately. Run `update-release-notes.py` (step 3)
before the signing step (step 6), since regenerating the notes overwrites the whole release body.

### Branching strategy

| Branch | Purpose |
|--------|---------|
| `master` | Represents production — always reflects what is live |
| `dev` | Integration branch for ongoing development |
| `release/MAJOR.MINOR.PATCH` | Short-lived branch used to prepare and build a release |

To start a release:

```sh
git checkout master
git checkout -b release/1.23.4
```

For a **full release**, merge `dev` into the release branch:

```sh
git merge dev
```

For a **patch release**, cherry-pick only the relevant commits:

```sh
git cherry-pick <commit>...
```

Once the branch is ready, bump the version code in `app/build.gradle` (the `versionCode` and
`versionName` fields), commit the change, then proceed with the steps below.

### Prerequisites

- **fdroidserver** — provides the `fdroid` command the script calls. Install it via your
  system package manager: `apt install fdroidserver` (Debian/Ubuntu),
  `brew install fdroidserver` (Homebrew), or `port install fdroidserver` (MacPorts).
  Other methods (PPA, pip, Docker, …): <https://f-droid.org/docs/Installing_the_Server_and_Repo_Tools/>
- [**gh**](https://cli.github.com/) — GitHub CLI, authenticated and with access to
  `session-foundation/session-android` and `session-foundation/session-fdroid`
- **Android SDK** — either `ANDROID_HOME` set in the environment, or `sdk.dir` defined in
  `local.properties`. This would have been set up for you if you have opened the project in Android Studio.
- **keyring** — signing/publishing credentials are read from the OS keyring, not from disk.
  Install: `apt install python3-keyring` (Linux) or `pip install keyring` (macOS).
- **Google API libraries** (only for `play-store.py`):
  `apt install python3-google-auth python3-googleapi`
  (or `pip install google-auth google-api-python-client`).

### Credentials (OS keyring)

The signing credentials are a TOML document (ask a project maintainer for it) with this
structure:

```toml
[build.play]
keystore          = "<base64-encoded JKS keystore>"
keystore_password = "<password>"
key_alias         = "<alias>"
key_password      = "<password>"

[build.huawei]
keystore          = "<base64-encoded JKS keystore>"
keystore_password = "<password>"
key_alias         = "<alias>"
key_password      = "<password>"

[fdroid]
keystore          = "<base64-encoded PKCS12 keystore>"
keystore_password = "<password>"
key_alias         = "<alias>"
key_password      = "<password>"
```

Rather than leaving that file on disk, store its contents in the OS keyring once, then delete
the plaintext file:

```sh
python3 scripts/_keyring.py set release-creds < release-creds.toml
rm release-creds.toml
```

`build-and-release.py` reads it back from the keyring at build time. (The keyring is
cross-platform via the `keyring` library — macOS Keychain, Linux Secret Service, etc.)

### Running the script

From the project root, run:

```sh
./scripts/build-and-release.py
```

This performs a full build and release. The built artifacts are placed under where they suppose
to be according to standard Android project layout.

#### Options

| Flag            | Description                                                                 |
|-----------------|-----------------------------------------------------------------------------|
| `--build-only`  | Build all flavors but skip the F-Droid PR and GitHub release upload steps  |
| `--build-type`  | Gradle build type to use (default: `release`)                               |

For example, to perform builds without publishing anything:

```sh
./scripts/build-and-release.py --build-only
```

### What the script does in detail

1. **Play build** — assembles split APKs and an AAB bundle for the `play` flavor, signed with
   the Play keystore.
2. **F-Droid build** — assembles split APKs for the `fdroid` flavor.
3. **F-Droid repo update** (skipped with `--build-only`) — see [FDROID_RELEASE.md](FDROID_RELEASE.md)
   for a full explanation of the architecture and manual steps:
   - Clones `session-foundation/session-fdroid` into `build/fdroidrepo` if not already present.
   - Creates a `release/<version>` branch.
   - Copies the new APKs into the repo, pruning old versions (keeps the latest
     four releases).
   - Regenerates repository metadata using `fdroid update`.
   - Commits and opens a pull request against `master` for human review and merge.
4. **Huawei build** — assembles a universal APK for the `huawei` flavor.
5. **GitHub release upload** (skipped with `--build-only`):
   - Looks for a release draft in this repository matching the version name.
   - If found, uploads the Play split APKs, the AAB bundle, and the Huawei APKs to it.
   - If no draft exists, this step is skipped (no error).

---

## Publishing to the Play Store

`build-and-release.py` uploads the AAB to the *GitHub* release draft, not to Google Play.
`scripts/play-store.py` handles Play via the Play Developer API, with three subcommands:

- `play-store.py status` — show each track's releases (version code, status, rollout %)
- `play-store.py upload [AAB] --track T [--submit | --rollout F]` — upload an AAB
- `play-store.py rollout {FRACTION|complete|halt} --track T` — change a staged rollout without re-uploading

One-time setup:

- Create a Play service account (Google Cloud Console → IAM & Admin → Service Accounts),
  download its JSON key, then grant it access in Play Console → **Users & permissions**
  (invite the service-account email; give it **Release to production** + **Release to testing
  tracks**). Store the key in the keyring and delete the plaintext:
  ```sh
  python3 scripts/_keyring.py set play-service-account < service-account.json
  rm service-account.json
  ```
- Install the Google API libraries (see Prerequisites).

Then, after `build-and-release.py` has produced the AAB:

```sh
scripts/play-store.py upload                                  # draft on the internal track
scripts/play-store.py upload --track production --submit      # full rollout
scripts/play-store.py upload --track production --rollout 0.1  # staged: 10% of users
scripts/play-store.py status                                  # check what landed
```

`upload` with no flag leaves the release a **draft** (parked, not submitted). `--submit`
submits a full rollout; `--rollout F` submits a staged rollout to fraction `F` — then ramp it
with `play-store.py rollout …` (e.g. from cron; Play has no native auto-ramp). The AAB path
defaults to the play release bundle produced by `build-and-release.py`.

**Managed publishing:** with it on (Play Console → Publishing overview), a submitted release is
reviewed but **held until you click Publish** in the console — there is no API for that final
publish step, nor for querying review status. Keep it on for release control.

---

## Contributing code

Code contributions should be submitted via GitHub as pull requests from feature branches,
[as explained here](https://help.github.com/articles/using-pull-requests).
