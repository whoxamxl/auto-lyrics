# OpenAutoLyrics — Release Guide

OpenAutoLyrics publishes release APKs through GitHub Actions. Release signing material is never stored in the repository.

## Release invariant

These values must describe the same release:

```text
app/build.gradle.kts: versionName = "X.Y.Z"
Git tag:              vX.Y.Z
Generated APK:        OpenAutoLyrics-X.Y.Z.apk
```

`versionCode` must increase for every Android release.

## Release triggers

`.github/workflows/build.yml` treats either of these as a release build:

```text
1. push a tag matching v*
2. push to main with a head commit message starting with "Release v"
```

The recommended path is the atomic `Release vX.Y.Z` commit so the version bump and release target remain aligned.

## Required GitHub Actions secrets

### Provider configuration

```text
PETITLYRICS_USER_ID
PETITLYRICS_APP_NAME
PETITLYRICS_PKG_NAME
PETITLYRICS_CLIENT_APP_ID
```

### Release signing

```text
OPENAUTOLYRICS_KEYSTORE_BASE64
OPENAUTOLYRICS_KEYSTORE_PASSWORD
OPENAUTOLYRICS_KEY_ALIAS
OPENAUTOLYRICS_KEY_PASSWORD
```

Release CI validates all required values before building. Ordinary PR/main debug builds do not need the release key.

## Create the standalone release key

Create a new key specifically for OpenAutoLyrics. Do **not** reuse the historical `app/signing.p12` that was committed to the old fork history.

Example:

```powershell
keytool -genkeypair -v -keystore openautolyrics-release.p12 -storetype PKCS12 -alias openautolyrics -keyalg RSA -keysize 4096 -validity 10000
```

Keep the resulting `.p12` outside the repository and back it up securely. Losing this key prevents future APKs signed with it from updating the same installed application identity.

To encode the keystore for the GitHub secret on Windows PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("openautolyrics-release.p12")) | Set-Clipboard
```

Paste that value into `OPENAUTOLYRICS_KEYSTORE_BASE64` and configure the three matching password/alias secrets.

The workflow decodes the key only into the GitHub runner's temporary directory and exposes the signing configuration to Gradle through:

```text
RELEASE_STORE_FILE
RELEASE_STORE_PASSWORD
RELEASE_KEY_ALIAS
RELEASE_KEY_PASSWORD
```

No release key file or password belongs in Git.

## Recommended atomic release procedure

Assume the next release is `X.Y.Z`.

### 1. Update local main

```powershell
git switch main
git pull origin main
```

### 2. Bump Android version

Edit `app/build.gradle.kts`:

```kotlin
versionCode = <previous + 1>
versionName = "X.Y.Z"
```

### 3. Commit with the release trigger

```powershell
git add app/build.gradle.kts
git commit -m "Release vX.Y.Z"
git push origin main
```

Do not create the tag manually when using this path.

Before pushing, verify that `vX.Y.Z` is unused:

```powershell
git ls-remote --tags origin refs/tags/vX.Y.Z
```

This command should print nothing. Release CI performs the same remote-tag check and fails before building/publishing if the derived tag already exists.

### 4. Verify CI

The release job should:

1. read `versionName`,
2. validate release version/tag consistency and reject tag reuse,
3. validate PetitLyrics secrets,
4. validate release-signing secrets,
5. create the temporary release keystore,
6. run unit tests,
7. run lint,
8. build the minified signed release APK,
9. publish `OpenAutoLyrics-X.Y.Z.apk`,
10. create/update GitHub Release `vX.Y.Z`.

## Alternative tag-driven release

Commit the version bump normally, let CI pass, then push the exact matching tag:

```powershell
git tag vX.Y.Z
git push origin vX.Y.Z
```

A mismatch between the tag and `versionName` fails release validation.

## Local builds

Debug builds use Android's standard debug signing configuration:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat lintDebug
.\gradlew.bat assembleDebug
```

A signed local release build requires all four `RELEASE_*` environment variables. Without them, Gradle can produce an unsigned release artifact, but that artifact must not be published as an OpenAutoLyrics release.

## Post-release verification

Verify:

- tag is `vX.Y.Z`,
- attached APK is `OpenAutoLyrics-X.Y.Z.apk`,
- Actions completed successfully,
- release signing validation passed,
- tests and lint passed,
- the APK installs and launches,
- notification access works,
- Android Auto discovers the app,
- LRCLIB and Musixmatch resolve known tracks,
- PetitLyrics works when configured,
- Karaoke mode can select genuine word timing,
- `Lyrics | Sync | More` and timing controls behave correctly.

## Standalone migration note

The old fork history contained `app/signing.p12` and its credentials. Treat that key as public/retired. Removing the file from the current tree does not erase it from historical commits; the security boundary is the new OpenAutoLyrics release key stored only outside Git and in GitHub Actions secrets.

## Related documentation

- [README.md](README.md) — user-facing overview and installation
- [DEVELOPMENT.md](DEVELOPMENT.md) — architecture, providers, resolver, cache, timing, and regression notes
