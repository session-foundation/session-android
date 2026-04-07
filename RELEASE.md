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

The keystore file can be recreated from `release-creds.toml` — the `keystore` field in each
section is the JKS file base64-encoded, so it can be decoded back to a `.jks` file using any
standard base64 tool.

---

## Release process

> **Quick checklist**
>
> 1. [ ] Create a `release/MAJOR.MINOR.PATCH` branch from `master`
> 2. [ ] Merge `dev` into the release branch (full release), or cherry-pick the relevant patch commits
> 3. [ ] Bump the version code
> 4. [ ] Create a GitHub release draft for the version (e.g. `1.23.4`) in this repository
> 5. [ ] Obtain `release-creds.toml` from a project maintainer and place it in the project root
> 6. [ ] Run `./scripts/build-and-release.py` from the release branch
> 7. [ ] Upload the AAB bundle to the Play Store
> 8. [ ] Review and merge the automated F-Droid PR in `session-foundation/session-fdroid`
> 9. [ ] Review and publish the GitHub release draft

Steps 6–9 are explained in detail below. Steps 4 and 5 must be completed **before** running
the script — if no release draft exists the artifact upload is silently skipped, and without
the credentials file the script will exit immediately.

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

- [**uv**](https://docs.astral.sh/uv/) — the script uses `uv run` to manage its Python
  dependencies (`fdroidserver`) automatically
- [**gh**](https://cli.github.com/) — GitHub CLI, authenticated and with access to
  `session-foundation/session-android` and `session-foundation/session-fdroid`
- **Android SDK** — either `ANDROID_HOME` set in the environment, or `sdk.dir` defined in
  `local.properties`. This would have been set up for you if you have opened the project in Android Studio.

### Credentials file

The script requires a `release-creds.toml` file in the project root. This file is not
committed to the repository — ask a project maintainer for it. Its structure is:

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

## Contributing code

Code contributions should be submitted via GitHub as pull requests from feature branches,
[as explained here](https://help.github.com/articles/using-pull-requests).
