# Auto Lyrics — Development Reference

## Terminology

| Term | Meaning |
|---|---|
| **Lyrics Browse View** (AA) | Android Auto browse-tree view backed by `onLoadChildren()`. Shows a window around the current lyric line. |
| **Now-playing card** (AA) | Standard Android Auto media now-playing card rendered from `MediaMetadataCompat`. |
| **Synced lyrics** | Line-level timestamps. `LyricsStatus.FOUND`. |
| **Plain lyrics** / **Unsynced** | Lyrics without timestamps. `LyricsStatus.PLAIN_ONLY`. |
| **LRCLIB** | Primary lyrics source. Auto Lyrics performs local candidate scoring instead of blindly trusting result order. |
| **PetitLyrics** | Optional fallback provider for synced lyrics when LRCLIB has no acceptable synced result. |
| **AA offset** | Android Auto-specific lyrics delay stored as `aa_offset_ms`. |
| **Phone sync** | Global lyrics offset managed by `MediaTracker.offsetMs`. |

## Architecture

```text
AutoLyricsApp
  └─ MediaTracker
       ├─ MediaListenerService
       ├─ LrcLibClient
       ├─ PetitLyricsClient (optional, configured at build time)
       ├─ LrcParser
       ├─ MetadataCleaner
       ├─ LyricsCache
       └─ AlbumColorExtractor

  Phone UI
  ├─ MainActivity
  └─ PerformanceActivity

  Android Auto
  ├─ LyricsBrowserService
  │   ├─ Browse tree
  │   ├─ MediaSession
  │   └─ Transport controls proxy
  └─ BootReceiver
```

## Lyrics Fetch Priority

1. Query LRCLIB.
2. If LRCLIB returns acceptable synchronized lyrics, use it immediately.
3. If LRCLIB has only plain lyrics or no result, query PetitLyrics when configured.
4. If PetitLyrics returns a supported synchronized result, use PetitLyrics.
5. Otherwise fall back to LRCLIB plain lyrics if available.

Android Auto displays the selected provider in the track header, for example:

```text
⟳ Synced · LRCLIB
⟳ Synced · PetitLyrics
```

## PetitLyrics Provider

The PetitLyrics integration is based on the request/response structure demonstrated by the reference project `whoxamxl/petitlyric_sync_lyric_download`.

The Android provider intentionally supports only **lyricsType=3** word-sync XML responses. Auto Lyrics reads the first word start time of each line and imports it as line-level synchronization. Word-level karaoke timing is not imported. The binary `lyricsType=2` line-sync decoder from the reference project is not implemented; those responses fall back to LRCLIB.

This integration uses an internal/unofficial PetitLyrics endpoint and is not affiliated with PetitLyrics. Availability and behavior may change independently of Auto Lyrics.

### Local configuration

Copy the tracked template:

```powershell
Copy-Item .env.example .env
```

Fill in your own registered client values:

```dotenv
PETITLYRICS_USER_ID=your-user-id
PETITLYRICS_APP_NAME=your-app-name
PETITLYRICS_PKG_NAME=your-package-name
PETITLYRICS_CLIENT_APP_ID=your-client-app-id
```

`.env` is ignored by Git and must not be committed.

`app/build.gradle.kts` reads each value from the process environment first and then falls back to the local `.env` file. Missing values are compiled as empty strings, in which case `PetitLyricsClient.isConfigured` is false and the provider is skipped cleanly.

### Important credential note

These values are injected into Android `BuildConfig`. This keeps them out of Git, but **does not make them secret inside a distributed APK**. A sufficiently motivated user can extract client-side constants from an APK. Do not use credentials whose security model assumes they can remain confidential on an end-user device.

## GitHub Actions / Release configuration

Repository Actions secrets should be created with the same four names:

```text
PETITLYRICS_USER_ID
PETITLYRICS_APP_NAME
PETITLYRICS_PKG_NAME
PETITLYRICS_CLIENT_APP_ID
```

`.github/workflows/build.yml` injects these secrets only for `v*` tag builds. Ordinary pull-request and `main` CI artifacts compile with empty PetitLyrics values, so the provider is disabled there and the identifiers are not embedded in routine artifacts.

### Release procedure

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Merge the release changes into `main`.
3. Confirm the four GitHub Actions secrets are configured in the repository.
4. Create and push a matching version tag, for example:

```bash
git tag v1.9.8
git push origin v1.9.8
```

The `Build APK` workflow will run unit tests, build the APK with the GitHub Actions secret values injected, rename the APK with the version name, and create the GitHub Release for `v*` tags.

For local release/debug builds, a populated `.env` is sufficient:

```bash
gradle testDebugUnitTest
gradle assembleDebug
```

## Key Files

| File | Purpose |
|---|---|
| `app/src/main/java/com/autolyrics/media/MediaTracker.kt` | Media tracking and provider selection. |
| `app/src/main/java/com/autolyrics/lyrics/LrcLibClient.kt` | LRCLIB search and candidate matching. |
| `app/src/main/java/com/autolyrics/lyrics/PetitLyricsClient.kt` | Optional PetitLyrics type-3 client and parser. |
| `app/src/main/java/com/autolyrics/lyrics/LyricsCache.kt` | Lyrics cache keyed by normalized title, artist, album, and duration. |
| `app/src/main/java/com/autolyrics/lyrics/MetadataCleaner.kt` | Query metadata cleanup. |
| `app/src/main/java/com/autolyrics/auto/LyricsBrowserService.kt` | Android Auto browse + MediaSession integration. |
| `.env.example` | Local PetitLyrics configuration template. |
| `.github/workflows/build.yml` | CI, tests, APK artifact, and tagged GitHub releases. |

## Android Auto Browse Constants

| Constant | Value | Purpose |
|---|---|---|
| `WINDOW_SIZE` | 3 | Number of synchronized lines in the AA browse window. |
| `PLAIN_WINDOW_SIZE` | 4 | Number of unsynchronized lines in the AA browse window. |
| `PAD_WIDTH` | 60 | Character padding for browse items. |
| `NOTIFY_THROTTLE_MS` | 500 ms | Minimum browse-tree refresh interval. |
| `BROWSE_KARAOKE_WINDOW_MS` | 600 ms | Legacy word-timing display window. |
| `SUBTITLE_KARAOKE_WINDOW_MS` | 300 ms | Legacy now-playing word-timing display window. |
| `SESSION_REFRESH_MS` | 1500 ms | MediaSession refresh interval. |
| `PLAIN_LOOP_DELAY_MS` | 2000 ms | Plain lyrics browse advance interval. |
