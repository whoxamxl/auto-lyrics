# Auto Lyrics — Release Guide

This is the release checklist for maintainers. The GitHub Actions workflow publishes a GitHub Release when a tag matching `v*` is pushed.

## Release invariant

The following values must describe the same release:

```text
app/build.gradle.kts: versionName = "X.Y.Z"
Git tag:              vX.Y.Z
Generated APK:        auto-lyrics-X.Y.Z.apk
```

`versionCode` must also increase for every new Android release.

**Do not create the tag before the version bump is committed to `main`.**

The workflow currently derives the APK filename from `versionName`; the tag itself is what triggers the release. A mismatched tag and `versionName` can therefore produce a GitHub Release whose tag and APK filename disagree.

## Required GitHub Actions secrets

Tagged release builds require these repository secrets:

```text
PETITLYRICS_USER_ID
PETITLYRICS_APP_NAME
PETITLYRICS_PKG_NAME
PETITLYRICS_CLIENT_APP_ID
```

The workflow validates all four before building a tagged release.

PR and ordinary `main` builds intentionally do not receive these values. Those CI artifacts build with PetitLyrics disabled unless another non-secret fallback is explicitly present in the checkout.

## Standard release procedure

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

Check the diff:

```powershell
git diff -- app/build.gradle.kts
```

### 3. Commit and push the version bump

```powershell
git add app/build.gradle.kts
git commit -m "Bump version to X.Y.Z"
git push origin main
```

Wait for the normal `main` CI build to pass before tagging.

### 4. Verify the version before tagging

```powershell
Select-String -Path .\app\build.gradle.kts -Pattern 'versionCode|versionName'
```

Expected shape:

```text
versionCode = N
versionName = "X.Y.Z"
```

The tag you are about to create must be exactly:

```text
vX.Y.Z
```

### 5. Create and push the tag

```powershell
git tag vX.Y.Z
git push origin vX.Y.Z
```

Do not reuse an existing version tag.

## What the tag workflow does

For a `v*` tag, `.github/workflows/build.yml` performs the following sequence:

1. Check out the tagged commit.
2. Set up JDK 17 / Gradle 8.4.
3. Inject the four PetitLyrics repository secrets as environment variables.
4. Fail if any required PetitLyrics value is missing.
5. Run `testDebugUnitTest`.
6. Run `assembleRelease`.
7. Read `versionName` from `app/build.gradle.kts`.
8. Copy the release APK to:

   ```text
   app/build/outputs/distribution/auto-lyrics-X.Y.Z.apk
   ```

9. Upload that APK as a workflow artifact.
10. Create the GitHub Release and attach the APK.

The release APK is minified and signed using the repository's configured signing setup.

## Post-release verification

Open the new GitHub Release and verify all of the following:

- release tag is `vX.Y.Z`,
- attached file is `auto-lyrics-X.Y.Z.apk`,
- release is not draft/prerelease unless intentionally requested,
- Actions run completed successfully,
- `Validate PetitLyrics release configuration` passed,
- unit tests passed,
- release APK build passed.

For a functional smoke test, install the release APK and verify:

1. LRCLIB lookup works.
2. PetitLyrics lookup works on a known supported track.
3. Android Auto shows `Lyrics | Sync | More`.
4. Current lyrics display the `▶` marker.
5. Lyrics tab row count is 5 without translation / 3 with translation.
6. Sync remains a fixed 3-row lyric preview.
7. ±50 ms AA offset controls still update the view.

## Local release-like build

A local `.env` can be used for provider configuration, but it does not reproduce GitHub's repository-secret injection exactly.

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleRelease
```

Output:

```text
app/build/outputs/apk/release/app-release.apk
```

## If a tag was created with the wrong version

Prefer creating a new corrected release version rather than rewriting an already published release/tag.

For example, if `vX.Y.Z` was published while `versionName` still contained an older value:

1. bump `versionCode` and `versionName` correctly,
2. commit and push `main`,
3. create the next unused tag,
4. verify the APK filename matches before considering the release complete.

Avoid force-moving published release tags unless there is a specific reason and all consumers of that tag are understood.

## Related documentation

- [README.md](README.md) — installation, user-facing features, local build basics.
- [DEVELOPMENT.md](DEVELOPMENT.md) — architecture, providers, resolver, cache, Android Auto implementation, regression checklist.
