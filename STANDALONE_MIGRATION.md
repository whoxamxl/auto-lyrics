# OpenAutoLyrics standalone migration

This checklist covers the one-time transition from the GitHub fork to the independent OpenAutoLyrics repository.

## Prepared in `standalone-prep`

- [x] Add a full MIT `LICENSE` file and preserve upstream attribution.
- [x] Rebrand the user-facing app name to `OpenAutoLyrics`.
- [x] Rewrite the README for the standalone project and future repository URL.
- [x] Retire the tracked historical `app/signing.p12`.
- [x] Ignore `.p12`, `.jks`, and `.keystore` signing material.
- [x] Move release signing to GitHub Actions secrets and runner-temporary storage.
- [x] Rename release artifacts to `OpenAutoLyrics-X.Y.Z.apk`.
- [x] Give the standalone app a new install identity: `io.github.whoxamxl.openautolyrics`.
- [x] Keep the Kotlin source namespace stable for this migration and use fully qualified manifest component names.

## Before merging the preparation PR

1. Let Linux and Windows PR CI complete successfully.
2. Review the resulting APK name and application label.
3. Confirm that using a new Android application ID is intentional. The standalone app can coexist with the old `com.autolyrics` build and does not update it in place.

## Create the new release signing key

Create and securely back up a new OpenAutoLyrics release key. Never commit it.

```powershell
keytool -genkeypair -v -keystore openautolyrics-release.p12 -storetype PKCS12 -alias openautolyrics -keyalg RSA -keysize 4096 -validity 10000
```

Configure these repository secrets:

```text
OPENAUTOLYRICS_KEYSTORE_BASE64
OPENAUTOLYRICS_KEYSTORE_PASSWORD
OPENAUTOLYRICS_KEY_ALIAS
OPENAUTOLYRICS_KEY_PASSWORD
```

The existing PetitLyrics release secrets remain required as documented in `RELEASE.md`.

## Detach from the fork network

After the preparation changes are merged and CI is green:

1. Open repository **Settings**.
2. Go to **General → Danger Zone**.
3. Choose **Leave fork network**.
4. Confirm the repository is now standalone before making the branding rename.

Do not rewrite or squash the historical upstream commits solely to hide the origin. Keeping the Git history preserves authorship and makes the project lineage transparent.

## Rename the repository

Rename:

```text
whoxamxl/auto-lyrics
→
whoxamxl/OpenAutoLyrics
```

Then update local clones:

```powershell
git remote set-url origin https://github.com/whoxamxl/OpenAutoLyrics.git
```

Keeping the original upstream remote is optional and useful for historical comparison:

```powershell
git remote -v
```

## GitHub presentation

Recommended repository description:

```text
Synchronized lyrics for Android Auto with multi-provider matching, word-synced karaoke, translations, and timing controls.
```

Recommended topics:

```text
android
automotive
android-auto
lyrics
karaoke
lrc
kotlin
lrclib
musixmatch
spotify
```

After the rename, verify README links, Releases, Actions, and clone URLs.

## First standalone release

A `v2.0.0` release is a clean semantic boundary for the new project identity, signing key, repository brand, and Android application ID. Before publishing it:

1. configure all release secrets,
2. bump `versionCode`,
3. set `versionName = "2.0.0"`,
4. run/verify CI,
5. install the new APK alongside or after removing the old Auto Lyrics build,
6. verify phone UI, notification access, Android Auto discovery, providers, translation, Sync, and Karaoke,
7. publish with the normal `Release v2.0.0` workflow.

## Optional follow-up cleanup

The source namespace remains `com.autolyrics` intentionally to keep the standalone migration small and testable. A later mechanical refactor can rename Kotlin packages, theme identifiers, and internal symbols to `OpenAutoLyrics` without mixing that high-churn change into the fork-detachment work.

A Buy Me a Coffee / support link can also be added to the README once the final public support URL is ready.
