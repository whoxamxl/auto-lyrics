# Auto Lyrics — Development Reference

## Terminology

| Term | Meaning |
|---|---|
| **Lyrics Browse View** (AA) | Android Auto browse-tree view backed by `onLoadChildren()`. Shows a window around the current lyric line. |
| **Now-playing card** (AA) | Standard Android Auto media now-playing card rendered from `MediaMetadataCompat`. |
| **Synced lyrics** | Line-level timestamps. `LyricsStatus.FOUND`. |
| **Plain lyrics** / **Unsynced** | Lyrics without timestamps. `LyricsStatus.PLAIN_ONLY`. |
| **LRCLIB** | Lyrics provider with synced and plain results plus duration metadata. |
| **PetitLyrics** | Optional Japanese-oriented synced-lyrics provider enabled by build-time client configuration. |
| **Provider Resolver** | Cross-provider scorer that compares metadata match, lyric payload quality, and source confidence. |
| **AA offset** | Android Auto-specific lyrics delay stored as `aa_offset_ms`. |
| **Phone sync** | Global lyrics offset managed by `MediaTracker.offsetMs`. |

## Architecture

```text
AutoLyricsApp
  └─ MediaTracker
       ├─ MediaListenerService
       ├─ LrcLibClient ─────────────┐
       ├─ PetitLyricsClient ────────┤ parallel fetch
       ├─ LyricsProviderResolver ◀──┘
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

## Lyrics provider resolution

When PetitLyrics is configured, LRCLIB and PetitLyrics are started in parallel. Each provider gets a **5 second total budget** at the `MediaTracker` layer; PetitLyrics HTTP calls also use a 4 second per-call timeout. Healthy requests are normally much faster than this, so a stalled provider cannot hold a track change for the old 10–15 second socket timeout window.

Provider-specific code performs search/fallback and produces plausible candidates. PetitLyrics candidate ranking delegates metadata validation to the same `LyricsProviderResolver.metadataScore()` logic used for the final cross-provider comparison, avoiding a second title/artist/album scoring formula. LRCLIB retains its search-side matcher because it uses LRCLIB-specific duration and endpoint behavior; the final LRCLIB-vs-PetitLyrics decision is still made only by the common resolver.

The common final comparison is:

```text
final score = metadata match × 0.82
            + lyric quality  × 0.10
            + source confidence × 0.08
```

Metadata matching uses title, artist, duration when reliable, album, and recording-version qualifiers. Cross-script artist names such as `Junko Yagami` vs `八神純子` are treated as non-comparable only when an exact title is corroborated by another signal such as a matching album or strong duration. This prevents title-only cover/same-title matches from receiving a perfect metadata score.

The lyric-quality score detects suspicious Japanese/Latin-only line alternation and near-duplicate timestamps, which helps reject LRCLIB entries containing interleaved romanized transliterations. For Japanese tracks, high-quality PetitLyrics Type 3/Type 2 data has a modest source-confidence advantage; non-Japanese ties favor LRCLIB.

Synchronized candidates always beat plain lyrics. LRCLIB plain text remains the final fallback if neither provider yields acceptable synchronized lyrics.

Android Auto displays the selected provider in the track header, for example:

```text
⟳ Synced · LRCLIB
⟳ Synced · PetitLyrics
```

### Cache policy

Cache identity includes normalized title, artist, album, and rounded duration. A fully corroborated provider comparison is cached for the normal seven-day interval. If PetitLyrics is enabled but only one provider returns a candidate (including timeout/transient-failure cases that cannot be distinguished from an empty result at the legacy client boundary), the selected result is treated as **provisional** and revalidated after 15 minutes rather than being frozen for seven days.

This policy deliberately prefers a little extra network traffic over pinning a fallback result for a week after one provider had a temporary outage.

## PetitLyrics Provider

The PetitLyrics integration is based on the request/response structure demonstrated by the reference project `whoxamxl/petitlyric_sync_lyric_download`.

Supported formats:

- **lyricsType=3 / WSY** — word-sync XML. Auto Lyrics currently imports the first word start time of each line as the line timestamp; word-level karaoke timing is not surfaced.
- **lyricsType=2 / LSY** — binary line-sync timing. Auto Lyrics decodes the timing payload and retrieves a **lyricsType=1** companion text payload, preferably by the same `lyricsId`, then combines them into line-synced lyrics.

PetitLyrics search progressively relaxes from `title + artist + album` to `title + artist`, then `title-only`, while local metadata validation prevents weak results from winning just because they were returned first. Type-1 companion selection is metadata-ranked if an exact `lyricsId` lookup is unavailable, so the first returned Type-1 record is no longer accepted blindly.

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

`.env` is ignored by Git and must not be committed. Keep `.env.example` safe to commit; it is a tracked template and should not contain credentials that must remain private.

`app/build.gradle.kts` resolves each value with this precedence:

```text
process environment
    ↓ if missing/blank
.env
    ↓ only when .env itself does not exist
.env.example
```

If neither local file provides a non-empty value, the corresponding value is compiled as an empty string. When any required PetitLyrics value is empty, `PetitLyricsClient.isConfigured` is false and the provider is skipped cleanly.

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

For a `v*` tag, the workflow validates that **all four** PetitLyrics release secrets are non-empty before testing/building. This prevents accidentally publishing a release APK with PetitLyrics silently disabled.

### Release procedure

1. Update `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Merge the release changes into `main`.
3. Confirm the four GitHub Actions secrets are configured in the repository.
4. Create and push a matching version tag, for example:

```bash
git tag v1.9.8
git push origin v1.9.8
```

The `Build APK` workflow validates release configuration, runs unit tests, builds the release APK with the GitHub Actions secret values injected, renames the APK with the version name, and creates the GitHub Release for `v*` tags.

For local debug builds, a populated `.env` is sufficient:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

## Key Files

| File | Purpose |
|---|---|
| `app/src/main/java/com/autolyrics/media/MediaTracker.kt` | Media tracking, parallel provider execution, timeout budget, cache refresh policy. |
| `app/src/main/java/com/autolyrics/lyrics/LrcLibClient.kt` | LRCLIB search and candidate matching. |
| `app/src/main/java/com/autolyrics/lyrics/PetitLyricsClient.kt` | Optional PetitLyrics Type 3/Type 2 client and parsers. |
| `app/src/main/java/com/autolyrics/lyrics/LyricsProviderResolver.kt` | Common metadata/quality/source scoring and final provider selection. |
| `app/src/main/java/com/autolyrics/lyrics/LyricsCache.kt` | Lyrics cache keyed by normalized title, artist, album, and duration with per-entry refresh interval. |
| `app/src/main/java/com/autolyrics/lyrics/MetadataCleaner.kt` | Query metadata cleanup. |
| `app/src/main/java/com/autolyrics/auto/LyricsBrowserService.kt` | Android Auto browse + MediaSession integration. |
| `.env.example` | Local PetitLyrics configuration template and no-`.env` fallback. |
| `.github/workflows/build.yml` | CI, tests, APK artifact, tagged GitHub releases, and release-secret validation. |

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
