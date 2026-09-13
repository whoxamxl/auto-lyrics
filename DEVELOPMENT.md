# Auto Lyrics — Development Reference

This document describes the current implementation rather than the original LRCLIB-only design. Keep user-facing setup in `README.md`; keep release mechanics in `RELEASE.md`.

## Terminology

| Term | Meaning |
|---|---|
| **Lyrics tab** (AA) | Primary Android Auto browse section. Shows track metadata plus a moving lyric window. |
| **Sync tab** (AA) | Android Auto timing-adjustment section with a fixed three-row lyric preview and ±50 ms controls. |
| **More tab** (AA) | Detailed track/lyrics information: provider, sync state, language, duration, offset, etc. |
| **Now-playing card** (AA) | Standard Android Auto media now-playing UI rendered from `MediaMetadataCompat`. Current lyrics are placed in the display subtitle. |
| **Synced lyrics** | Timestamped lyrics represented by `LyricsStatus.FOUND`. |
| **Plain lyrics** | Unsynchronized text represented by `LyricsStatus.PLAIN_ONLY`. |
| **Word-synced lyrics** | Synced lyrics whose `LyricLine.words` contain per-word/phrase timing. PetitLyrics Type 3 supplies explicit start/end timing. |
| **LRCLIB** | Provider supporting synchronized and plain results with documented duration metadata. |
| **PetitLyrics** | Optional Japanese-oriented provider enabled by build-time client configuration. |
| **Provider Resolver** | Common scorer used to validate and compare normalized provider candidates. |
| **AA offset** | Android-Auto-specific delay stored as `aa_offset_ms`. |
| **Phone/global offset** | Main lyric offset managed by `MediaTracker.offsetMs` / `lyrics_offset_ms`. |

## Architecture

```text
AutoLyricsApp
  └─ MediaTracker (singleton / StateFlow)
       ├─ active MediaController state
       ├─ LrcLibClient ─────────────┐
       ├─ PetitLyricsClient ────────┤ parallel when configured
       ├─ LyricsProviderResolver ◀──┘
       ├─ LyricsCache
       ├─ KaraokeTiming
       ├─ MetadataCleaner
       ├─ LrcParser
       ├─ LyricsTranslator
       └─ AlbumColorExtractor

Phone UI
  ├─ MainActivity
  └─ PerformanceActivity

Android Auto
  ├─ LyricsBrowserService
  │   ├─ MediaBrowser browse tree
  │   │   ├─ Lyrics
  │   │   ├─ Sync
  │   │   └─ More
  │   ├─ MediaSession / now-playing metadata
  │   └─ transport-control proxy
  └─ BootReceiver
```

`MediaListenerService` is a `NotificationListenerService`; its access allows the app to inspect active media sessions through `MediaSessionManager`.

## Track detection and position

`MediaTracker` extracts title, artist, album, duration, and artwork from the active media controller. Metadata is normalized by `MetadataCleaner` before a `TrackInfo` is created.

Track changes are debounced by 600 ms before a new lookup starts. While playback is active, position is reconstructed from the media session's reported position, last update time, and playback speed. Synced lyric indices are checked every 150 ms.

For word-synced lines, `KaraokeTiming.activeWordIndex()` selects the latest word whose start has been reached and whose explicit end has not passed. This means a real gap between PetitLyrics words produces `currentWordIndex = -1` instead of leaving the previous word highlighted. Formats without end timing retain the previous start-time-only behavior.

The phone/global offset is included by `MediaTracker.getCurrentPositionMs()`. Android Auto applies its own additional `aa_offset_ms` inside `LyricsBrowserService`.

## Provider execution

When PetitLyrics is configured, LRCLIB and PetitLyrics are started concurrently. Each provider gets a hard **5 second total budget** at the `MediaTracker` layer.

PetitLyrics also uses a shorter per-call HTTP timeout. Healthy provider requests are normally much faster than the outer budget; the budget exists to prevent a stalled provider from holding the entire track transition open.

Provider speed is **not part of the score**. If both providers return within budget, a 200 ms response receives no scoring advantage over a 2 s response. Speed only changes the comparison when a provider times out or otherwise fails to produce a candidate.

Debug logging:

```powershell
adb logcat -s "ProviderResolver:D" "PetitLyrics:D" "*:S"
```

Typical resolver diagnostics include elapsed time, timeout state, candidate presence, metadata score, quality score, source confidence, and selected provider.

## Provider resolution

Provider-specific clients perform search/fallback and return normalized `LyricsProviderCandidate` objects. The final LRCLIB-vs-PetitLyrics decision is made by `LyricsProviderResolver`.

The final score is:

```text
final score = metadata match     × 0.82
            + lyric quality      × 0.10
            + source confidence  × 0.08
```

A synchronized candidate always outranks a plain candidate. LRCLIB plain lyrics remain the last-resort fallback when no acceptable synchronized candidate exists.

### Metadata validation

The resolver validates recording/version qualifiers before scoring. Metadata weighting is approximately:

```text
title     55%
artist    30%
duration  12%  (when reliable)
album      3%
```

Weights are renormalized when a field is unavailable or intentionally treated as non-comparable.

PetitLyrics duration is not currently trusted for common resolver scoring because observed response fields are not sufficiently reliable across formats/clients. LRCLIB duration is used.

### Cross-script artist names

Romanized player metadata and native Japanese provider metadata are not directly comparable. Examples include Latin-script artist metadata against Japanese-script provider metadata.

For a near-exact title, the resolver may neutralize a cross-script artist mismatch when there is independent evidence:

- PetitLyrics returned the candidate from a request that explicitly included `key_artist` (`artistQueryCorroborated=true`), or
- album similarity is strong enough, or
- reliable duration similarity is strong enough.

This distinction is important: a PetitLyrics **title-only** fallback does not get artist-query corroboration, so same-title/different-artist candidates still need another signal.

### Multi-contributor artist metadata

Some players expose a contributor list instead of one performer, for example:

```text
Alan Menken, Howard Ashman, Samuel E. Wright, Disney
```

For near-exact title matches, artist similarity can inspect comma/semicolon-delimited components and accept a strong component match such as `Samuel E. Wright`.

The full metadata string is preserved; the app does not globally truncate artist names at the first comma. This avoids breaking legitimate names that contain punctuation.

### Lyric payload quality

The resolver scores the payload separately from metadata. Current quality checks include:

- suspicious Japanese / Latin-only alternation,
- near-duplicate timestamps around transliteration lines,
- lyrics extending far beyond the track duration,
- unusually short timing coverage for a long synchronized track,
- very late first timestamps.

The Japanese/Latin alternation penalty is primarily intended to catch LRCLIB entries containing interleaved romanized transliterations as if they were separate timed lyric rows.

### Source confidence

Source confidence is deliberately a small part of the final score (8%). Current policy gives high-quality PetitLyrics Type 3/Type 2 data an advantage on Japanese tracks and gives LRCLIB synchronized data the advantage on non-Japanese tracks. Metadata and payload quality remain the dominant factors.

## LRCLIB search

LRCLIB uses provider-specific search logic because it can use duration and has its own endpoint behavior. Search starts with constrained metadata and can relax when necessary, including title-only discovery when stronger searches do not yield usable synchronized lyrics.

Relaxed discovery does not mean relaxed acceptance: returned records are re-ranked locally using title, contributor-aware artist matching, album, duration, and version qualifiers.

If no acceptable synchronized LRCLIB entry exists but an acceptable plain result does, it can be returned as `LyricsStatus.PLAIN_ONLY`.

## PetitLyrics provider

The PetitLyrics integration follows the request/response structure demonstrated by the reference project `whoxamxl/petitlyric_sync_lyric_download` while using this app's own configured identifiers.

Supported formats:

- **lyricsType=3 / WSY** — word-sync XML. Auto Lyrics imports every non-empty `wordstring` into `LyricWord`, retains `starttime` and valid `endtime`, and uses the first timed word as the containing `LyricLine.timeMs`. `linestring` remains the canonical full-line text. PetitLyrics-authored whitespace in `wordstring` is preserved rather than trimmed or regenerated.
- **lyricsType=2 / LSY** — binary line-sync timing. Auto Lyrics decodes the timing payload and combines it with a **lyricsType=1** plain-text companion, preferably resolved by the same `lyricsId`.

Type 3 timed blank rows are retained as `♪` lines using their timing, but an empty `wordstring` is not materialized as an invisible karaoke token.

The word renderer uses `KaraokeTiming.separatorFor()` to support both source styles:

```text
PetitLyrics WSY:  word strings already contain authored spacing → separator ""
legacy tokenized: words reconstruct the line with spaces          → separator " "
```

This keeps Japanese WSY text from gaining artificial spaces while retaining compatibility with tokenized enhanced-LRC data.

PetitLyrics search progressively relaxes:

```text
title + artist + album
        ↓
title + artist
        ↓
title only
```

The search stage that produced a candidate is preserved through `artistQueryCorroborated`, allowing the resolver to distinguish an artist-constrained cross-script hit from a title-only fallback.

Type-1 companion fallback is metadata-ranked when an exact `lyricsId` lookup is unavailable; it is not allowed to blindly use the first returned record.

PetitLyrics uses an internal/unofficial endpoint and is not affiliated with this project. Availability and response behavior may change independently of Auto Lyrics.

## PetitLyrics local configuration

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

`.env` is ignored by Git and must not be committed. `.env.example` is tracked and should remain safe to publish.

`app/build.gradle.kts` resolves configuration with this precedence:

```text
process environment
    ↓ if missing/blank
.env
    ↓ only when .env itself does not exist
.env.example
```

If any required value is empty, `PetitLyricsClient.isConfigured` is false and PetitLyrics is skipped cleanly.

### Credential model

These identifiers are compiled into Android `BuildConfig`. Keeping them in `.env` / GitHub Actions secrets prevents accidental source-control disclosure, but **does not make them secret inside a distributed APK**. Do not use credentials whose security model requires them to remain confidential on the client device.

## Cache policy

Cache identity currently includes:

```text
cache namespace (v12)
normalized title
normalized artist
normalized album
rounded duration in seconds
```

Namespace `v12` invalidates older cached Type 3 rows that were stored without per-word timing. Cached words now persist both start time and optional end time.

A cached result is displayed immediately. Its per-entry `refreshAfterMs` decides whether a background provider comparison is needed.

- **Fully corroborated comparison:** 7 days.
- **Incomplete/provisional comparison:** 15 minutes.

When PetitLyrics is configured, the current implementation considers the provider set complete only when both LRCLIB and PetitLyrics return candidates and neither times out. This is intentionally conservative.

### Known cache-result limitation

Provider clients still collapse several outcomes to `null`; the `MediaTracker` boundary does not yet distinguish:

```text
NOT_FOUND
TRANSIENT_ERROR
TIMEOUT
```

Timeout itself is tracked, but a clean provider “no result” and some transient failures are otherwise indistinguishable. As a result, a legitimate one-provider-only song can remain on the 15-minute refresh policy instead of the normal 7-day policy. This costs extra network traffic but avoids pinning a fallback result for a week after a temporary outage.

A future cleanup can replace the nullable provider boundary with an explicit result type such as `Found / NotFound / TransientError`.

## Android Auto browse UI

`LyricsBrowserService` exposes three root browse items in this order:

```text
Lyrics | Sync | More
```

Keeping Lyrics first makes it the primary/default browse section.

### Lyrics tab

For synchronized and plain lyrics:

- current row is marked with `▶`,
- surrounding rows reserve a matching visual gutter,
- **5 rows** are shown when no translations are present,
- **3 rows** are shown when translated subtitles are present.

The current line is kept near the center where track boundaries allow it.

When a line contains word timing and Android Auto karaoke is enabled, the current word/short look-ahead segment is marked with `【…】`. Explicit PetitLyrics `endtime` values suppress highlighting during real gaps between words. Provider-authored spacing is preserved.

The track header includes artist, position/duration, synchronization state, provider, and detected language where available.

### Sync tab

The Sync tab is intentionally independent from the adaptive Lyrics window. It uses a fixed:

```text
SYNC_WINDOW_SIZE = 3
```

It shows the previous/current/next lyric context when possible, plus:

```text
AA Offset
−50 ms
+50 ms
```

The current Sync row can use the same karaoke-text helper when word data exists.

### More tab

More contains detail rows for the available metadata:

- Title
- Artist
- Album
- Provider
- Lyrics state (Synced / Not synced / etc.)
- Detected language
- Duration
- AA Offset

### Now-playing subtitle

`MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE` carries the current lyric text. The original lyric line is prefixed with `▶`. When translation is available, it is placed on the second line without a second marker.

Word-synced lines use the same Android Auto karaoke text generation as the browse rows. The helper is stateless, so seeking backward within a line recalculates the correct word instead of retaining a later highlight.

### Browse refresh behavior

Browse refreshes notify the three section IDs rather than rebuilding the root tabs on every lyric update. Updates are throttled to reduce Android Auto browse churn.

## Android Auto constants

Current `LyricsBrowserService` UI constants:

| Constant | Value | Purpose |
|---|---:|---|
| `SYNC_WINDOW_SIZE` | 3 | Fixed lyric row count in Sync. |
| `DEFAULT_WINDOW_SIZE` | 5 | Lyrics-tab row count without translations. |
| `TRANSLATED_WINDOW_SIZE` | 3 | Lyrics-tab row count when translations exist. |
| `CURRENT_LINE_PREFIX` | `▶  ` | Current row marker. |
| `IDLE_LINE_PREFIX` | em-space + en-space | Visual gutter matching the marker width. |
| `PAD_WIDTH` | 60 | Character padding for browse items. |
| `NOTIFY_THROTTLE_MS` | 500 ms | Minimum browse-tree refresh interval. |
| `BROWSE_KARAOKE_WINDOW_MS` | 600 ms | Word-highlight look-ahead used by browse text when words exist. |
| `SUBTITLE_KARAOKE_WINDOW_MS` | 300 ms | Word-highlight look-ahead used by now-playing subtitle when words exist. |
| `SESSION_REFRESH_MS` | 1500 ms | MediaSession playback-state refresh interval. |
| `PLAIN_LOOP_DELAY_MS` | 2000 ms | Plain-lyrics browse advance check interval. |

## GitHub Actions

`.github/workflows/build.yml` runs for:

- pull requests to `main`,
- pushes to `main`,
- tags matching `v*`.

PR/main builds:

- run unit tests,
- build a debug APK,
- do **not** inject PetitLyrics repository secrets.

`v*` tag builds:

- validate all four PetitLyrics release secrets,
- run unit tests,
- build the release APK,
- inject the PetitLyrics values through environment variables,
- copy the APK to `auto-lyrics-${versionName}.apk`,
- upload the artifact,
- create a GitHub Release.

Release mechanics, including the required `versionName`/tag match, are documented in [RELEASE.md](RELEASE.md).

## Tests and regression checklist

Run local unit tests before pushing provider/resolver changes:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Provider/matching regression cases should continue to cover:

- Japanese native-script artist vs romanized player metadata with valid corroboration.
- Cross-script same-title candidate without corroboration is rejected.
- Multi-contributor player metadata can match one exact contributor on a near-exact title.
- Unrelated contributor does not become a valid artist match.
- Japanese/Latin interleaved lyric payload receives a quality penalty.
- PetitLyrics Type 3 imports word start/end timing and preserves provider spacing.
- Explicit Type 3 gaps do not leave the previous word highlighted.
- PetitLyrics Type 2 timing decode + Type 1 companion selection.
- Synchronized candidates outrank plain candidates.

Manual phone/performance regression pass for word-sync changes:

1. Type 3 track highlights the active word/phrase without inserted Japanese spaces.
2. Highlight clears during a provider-authored gap between `endtime` and the next `starttime`.
3. Seeking backward within the same line returns to the earlier word.
4. Performance mode preserves the same source spacing as the main phone view.

Manual DHU regression pass after Android Auto UI changes:

1. Lyrics / Sync / More tabs are in that order.
2. Lyrics shows 5 rows without translation and 3 with translation.
3. Current-row `▶` gutter aligns with surrounding lyric text.
4. Sync stays at exactly 3 lyric rows when enough lines exist.
5. `−50 ms` / `+50 ms` changes AA offset and refreshes the view.
6. More shows current provider/details without affecting lyrics timing.
7. Now-playing subtitle marks the current lyric with `▶`.
8. Word-synced Type 3 text preserves provider spacing and clears karaoke brackets in explicit timing gaps.
9. Plain lyrics still advance and use the same current-row alignment.

## Key files

| File | Purpose |
|---|---|
| `app/src/main/java/com/autolyrics/media/MediaTracker.kt` | Active media tracking, provider concurrency/budgets, resolver orchestration, cache refresh policy, current line/word state. |
| `app/src/main/java/com/autolyrics/lyrics/LrcLibClient.kt` | LRCLIB lookup, provider-specific matching, relaxed search. |
| `app/src/main/java/com/autolyrics/lyrics/PetitLyricsClient.kt` | PetitLyrics search, Type 3/Type 2 parsing, query provenance. |
| `app/src/main/java/com/autolyrics/lyrics/KaraokeTiming.kt` | Shared active-word and source-spacing rules for word-synced rendering. |
| `app/src/main/java/com/autolyrics/lyrics/LyricsProviderResolver.kt` | Common metadata/quality/source scoring and final provider selection. |
| `app/src/main/java/com/autolyrics/lyrics/LyricsCache.kt` | Persistent lyrics cache and per-entry refresh interval. |
| `app/src/main/java/com/autolyrics/lyrics/MetadataCleaner.kt` | Player metadata cleanup before provider search. |
| `app/src/main/java/com/autolyrics/auto/LyricsBrowserService.kt` | Android Auto MediaBrowser tree, MediaSession metadata, Sync controls, word-karaoke text. |
| `app/src/main/res/xml/automotive_app_desc.xml` | Declares the Android Auto media integration. |
| `.env.example` | Local PetitLyrics template / no-`.env` fallback. |
| `.github/workflows/build.yml` | CI, release build, release-secret injection/validation, GitHub Release creation. |
| `RELEASE.md` | Release checklist and version/tag invariants. |
