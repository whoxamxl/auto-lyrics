# Auto Lyrics — Development Reference

This document describes the current implementation rather than the original LRCLIB-only design. Keep user-facing setup in `README.md`; keep release mechanics in `RELEASE.md`.

## Terminology

| Term | Meaning |
|---|---|
| **Lyrics tab** (AA) | Primary Android Auto browse section. Shows track metadata plus a moving lyric window. |
| **Sync tab** (AA) | Android Auto timing-adjustment section with a fixed three-row lyric preview and ±50 ms controls. |
| **More tab** (AA) | Detailed track/lyrics information: provider, sync state, language, duration, offset, etc. |
| **Now-playing card** (AA) | Standard Android Auto media now-playing UI rendered from `MediaMetadataCompat`. Current lyrics are placed in the display subtitle. |
| **WORD_SYNC** | Synchronized lyrics with per-word/chunk timestamps. |
| **LINE_SYNC** | Synchronized lyrics with line timestamps but no usable per-word timestamps. |
| **Plain lyrics** | Unsynchronized text represented by `LyricsStatus.PLAIN_ONLY`. |
| **LRCLIB** | Provider supporting synchronized and plain results with documented duration metadata. |
| **Musixmatch** | Provider using the anonymous mobile API; supports RichSync word timing and line-synced subtitle fallback. |
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
       ├─ MusixmatchClient ─────────┤ parallel
       ├─ PetitLyricsClient ────────┤ when configured
       ├─ LyricsProviderResolver ◀──┘
       ├─ LyricsCache
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

`MediaListenerService` is a `NotificationListenerService`; its access allows the app to inspect active media sessions through `MediaSessionManager`. The service keeps the currently selected playing session sticky while it remains active so another media session does not steal selection merely because controller ordering changes.

## Track detection and position

`MediaTracker` extracts title, artist, album, duration, and artwork from the active media controller. Metadata is normalized by `MetadataCleaner` before a `TrackInfo` is created.

Track changes are debounced by 600 ms before a new lookup starts. While playback is active, position is reconstructed from the media session's reported position, last update time, and playback speed. Synced lyric indices are checked every 150 ms.

The phone/global offset is included by `MediaTracker.getCurrentPositionMs()`. Android Auto applies its own additional `aa_offset_ms` inside `LyricsBrowserService`.

Do not add a global/provider-specific timing correction merely because one playback path appears early or late. Phone speakers, Bluetooth, Android Auto, codecs, and head units can have different output latency. The existing phone/global and AA-specific controls are the correct place for stable device/output-path compensation unless a provider timestamp bias is demonstrated across multiple playback paths.

## Provider execution

LRCLIB and Musixmatch are started concurrently. PetitLyrics joins the same parallel comparison when it is configured. Each provider gets a hard **5 second total budget** at the `MediaTracker` layer.

Provider speed is **not part of the score**. If multiple providers return within budget, a 200 ms response receives no scoring advantage over a 2 s response. Speed changes the comparison only when a provider times out or otherwise fails to produce a candidate.

Musixmatch is treated as an opportunistic unofficial source for cache-completeness purposes: its temporary unavailability does not shorten an otherwise healthy LRCLIB/PetitLyrics cache result.

Debug logging:

```powershell
adb logcat -s Musixmatch:D ProviderResolver:D PetitLyrics:D
```

Typical resolver diagnostics include elapsed time, timeout state, candidate presence, metadata score, quality score, source confidence, synchronization kind, and selected provider.

## Provider resolution

Provider-specific clients perform lookup/fallback and return normalized `LyricsProviderCandidate` objects. `LyricsProviderResolver` then validates and scores the candidates together.

The final score is:

```text
final score = metadata match     × 0.82
            + lyric quality      × 0.10
            + source confidence  × 0.08
```

A synchronized candidate (`LyricsStatus.FOUND`) always outranks a plain candidate. LRCLIB plain lyrics remain the last-resort fallback when no acceptable synchronized candidate exists.

### Why WORD_SYNC does not automatically beat LINE_SYNC

`WORD_SYNC` and `LINE_SYNC` describe timing **granularity**, not recording identity or correctness. A word-synced payload can still belong to the wrong edit/live/remaster, contain inferior text, or come from a lower-confidence match.

For that reason the resolver does not apply a blanket rule such as:

```text
WORD_SYNC > LINE_SYNC
```

Instead, metadata and lyric payload quality remain dominant. A perfectly matched high-confidence LINE_SYNC result may therefore beat a weaker WORD_SYNC result. This is intentional: the correct recording with line timing is preferable to the wrong recording with richer timestamps.

Provider/source confidence can still favor particular synchronization formats where justified (for example PetitLyrics word/line data on Japanese tracks), but sync granularity alone is not an unconditional override.

### Metadata validation

The resolver validates recording/version qualifiers before scoring. Metadata weighting is approximately:

```text
title     55%
artist    30%
duration  12%  (when reliable)
album      3%
```

Weights are renormalized when a field is unavailable or intentionally treated as non-comparable.

PetitLyrics duration is not currently trusted for common resolver scoring because observed response fields are not sufficiently reliable across formats/clients. LRCLIB and Musixmatch duration may be used when present.

### Cross-script artist names

Romanized player metadata and native Japanese provider metadata are not directly comparable. For a near-exact title, the resolver may neutralize a cross-script artist mismatch when there is independent evidence:

- the provider lookup was artist-constrained and records that fact through `artistQueryCorroborated=true`,
- album similarity is strong enough, or
- reliable duration similarity is strong enough.

A title-only fallback does not receive artist-query corroboration, so same-title/different-artist candidates still need another signal.

### Multi-contributor artist metadata

Some players expose a contributor list instead of one performer, for example:

```text
Alan Menken, Howard Ashman, Samuel E. Wright, Disney
```

For near-exact title matches, artist similarity can inspect comma/semicolon-delimited components and accept a strong component match such as `Samuel E. Wright`.

The full metadata string is preserved; the app does not globally truncate artist names at the first comma.

### Lyric payload quality

The resolver scores the payload separately from metadata. Current checks include:

- suspicious Japanese / Latin-only alternation,
- near-duplicate timestamps around transliteration lines,
- lyrics extending far beyond the track duration,
- unusually short timing coverage for a long synchronized track,
- very late first timestamps.

The Japanese/Latin alternation penalty primarily catches LRCLIB entries containing interleaved romanized transliterations as if they were separate timed rows.

### Source confidence

Source confidence is deliberately a small part of the final score (8%). Current policy favors PetitLyrics Type 3/Type 2 data on Japanese tracks and LRCLIB synchronized data on non-Japanese tracks. Musixmatch currently uses the generic synchronized-provider confidence unless a more specific policy is added later. Metadata and payload quality remain dominant.

## LRCLIB provider

LRCLIB starts with constrained metadata and relaxes search when necessary, including title-only discovery when stronger searches do not yield usable synchronized lyrics.

Relaxed discovery does not mean relaxed acceptance: returned records are re-ranked locally using title, contributor-aware artist matching, album, duration, and version qualifiers.

If no acceptable synchronized LRCLIB entry exists but an acceptable plain result does, it can be returned as `LyricsStatus.PLAIN_ONLY`.

## Musixmatch provider

Musixmatch uses the anonymous **mobile** API path:

```text
https://apic-appmobile.musixmatch.com/ws/1.1/
```

The desktop API was tested first but returned unrelated fixed matches despite successful HTTP/API status. The implementation therefore follows the mobile flow also used by current third-party clients.

Current request identity:

```text
app_id = mac-ios-v2.0
x-mxm-app-version = 10.1.1
X-User-Agent / User-Agent = Musixmatch iOS-style client string
```

### Authentication and lookup flow

```text
token.get
    ↓ anonymous user_token (briefly cached)
macro.subtitles.get
    ├── matcher.track.get
    ├── track.richsync.get
    └── track.subtitles.get
```

The macro query includes title and artist plus album/duration when available. The provider does **not** deep-search arbitrary `track` objects in the macro payload; it explicitly reads `macro_calls["matcher.track.get"]` so unrelated nested tracks cannot accidentally become the accepted match.

The returned matcher metadata is normalized into a candidate and passed through the same local metadata validation used by the resolver. Instrumental mismatches and weak metadata matches are rejected before any lyric payload is accepted.

If a request returns HTTP/API 401, the cached token is invalidated and one fresh-token retry is allowed.

### RichSync

When `has_richsync=1`, Auto Lyrics prefers `track.richsync.get`. RichSync line objects contain a line start (`ts`) and chunks whose `o` value is an offset from that line start:

```text
word timestamp = ts + o
```

The resulting timestamps are stored in `LyricWord` and persist through `LyricsCache`.

If RichSync is absent or unusable, `track.subtitles.get` LRC is parsed as LINE_SYNC. Both are `LyricsStatus.FOUND` because both are synchronized; only the timing granularity differs.

Musixmatch uses an unofficial/internal endpoint and is not affiliated with this project. Availability and response behavior may change independently of Auto Lyrics.

## PetitLyrics provider

The PetitLyrics integration follows the request/response structure demonstrated by the reference project `whoxamxl/petitlyric_sync_lyric_download` while using this app's configured identifiers.

Supported formats:

- **lyricsType=3 / WSY** — word-sync XML. The current main implementation uses the first word start time of each line as the line timestamp; full PetitLyrics word timing is not yet imported into `LyricWord`.
- **lyricsType=2 / LSY** — binary line-sync timing combined with a **lyricsType=1** plain-text companion, preferably resolved by the same `lyricsId`.

Search progressively relaxes:

```text
title + artist + album
        ↓
title + artist
        ↓
title only
```

The search stage that produced a candidate is preserved through `artistQueryCorroborated`, allowing the resolver to distinguish an artist-constrained cross-script hit from a title-only fallback.

Type-1 companion fallback is metadata-ranked when an exact `lyricsId` lookup is unavailable; it is not allowed to blindly use the first returned record.

PetitLyrics uses an internal/unofficial endpoint and is not affiliated with this project.

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

`.env` is ignored by Git and must not be committed. `app/build.gradle.kts` resolves configuration in this order:

```text
process environment
    ↓ if missing/blank
.env
    ↓ only when .env itself does not exist
.env.example
```

If any required value is empty, `PetitLyricsClient.isConfigured` is false and PetitLyrics is skipped cleanly.

These identifiers are compiled into Android `BuildConfig`. Keeping them outside source control prevents accidental disclosure in Git, but **does not make them secret inside a distributed APK**.

## Cache policy

Cache identity currently includes:

```text
cache namespace (v11)
normalized title
normalized artist
normalized album
rounded duration in seconds
```

A cached result is displayed immediately. `LyricsCache` preserves line timestamps plus each `LyricWord(timeMs, text)`, so a cached Musixmatch RichSync result remains word-synchronized after restart/reload.

Per-entry `refreshAfterMs` controls background refresh:

- **Fully corroborated LRCLIB/PetitLyrics comparison:** 7 days.
- **Incomplete/provisional comparison:** 15 minutes.

When PetitLyrics is configured, the provider set is considered complete only when LRCLIB and PetitLyrics both return candidates and neither times out. Musixmatch availability intentionally does not gate this cache-completeness decision because it is an opportunistic unofficial source.

### Known cache-result limitation

Provider clients still collapse several outcomes to `null`; the `MediaTracker` boundary does not fully distinguish:

```text
NOT_FOUND
TRANSIENT_ERROR
TIMEOUT
```

Timeout itself is tracked, but a clean provider “no result” and some transient failures are otherwise indistinguishable. A legitimate one-provider-only song can therefore remain on the 15-minute refresh policy instead of the normal 7-day policy. This costs extra traffic but avoids pinning a fallback result for a week after a temporary outage.

A future cleanup can replace the nullable provider boundary with an explicit result type such as `Found / NotFound / TransientError`.

## Android Auto browse UI

`LyricsBrowserService` exposes:

```text
Lyrics | Sync | More
```

Keeping Lyrics first makes it the primary/default browse section.

### Lyrics tab

- current row is marked with `▶`,
- surrounding rows reserve a matching visual gutter,
- **5 rows** are shown when no translations are present,
- **3 rows** are shown when translated subtitles are present.

The current line is kept near the center where track boundaries allow it. The track header includes artist, position/duration, synchronization state, provider, and detected language where available.

### Sync tab

The Sync tab uses a fixed three-row lyric preview and provides AA-specific `−50 ms` / `+50 ms` controls. The current row can use karaoke text when `LyricWord` data exists.

### More tab

More can show title, artist, album, provider, lyric synchronization state, detected language, duration, and AA offset.

### Now-playing subtitle

`MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE` carries the current lyric text. The original line is prefixed with `▶`; translated text, when present, is placed on the second line without another marker.

### Browse refresh behavior and karaoke look-ahead

Browse refreshes notify section IDs instead of rebuilding the root tabs on every lyric update. Updates are throttled to reduce Android Auto browse churn.

`LyricsBrowserService` currently uses short karaoke look-ahead windows when formatting browse/now-playing text. This does not change the media position or provider timestamps; it only affects how much upcoming word text is enclosed in the visual karaoke marker between browse refreshes. If word highlighting appears early specifically in Android Auto, inspect this behavior before introducing a provider-wide timestamp offset.

Current constants include:

| Constant | Value | Purpose |
|---|---:|---|
| `SYNC_WINDOW_SIZE` | 3 | Fixed lyric row count in Sync. |
| `DEFAULT_WINDOW_SIZE` | 5 | Lyrics-tab row count without translations. |
| `CURRENT_LINE_PREFIX` | `▶  ` | Current row marker. |
| `PAD_WIDTH` | 60 | Character padding for browse items. |
| `NOTIFY_THROTTLE_MS` | 500 ms | Minimum browse-tree refresh interval. |
| `BROWSE_KARAOKE_WINDOW_MS` | 600 ms | Browse karaoke look-ahead. |
| `SUBTITLE_KARAOKE_WINDOW_MS` | 300 ms | Now-playing karaoke look-ahead. |
| `SESSION_REFRESH_MS` | 1500 ms | MediaSession playback-state refresh interval. |
| `PLAIN_LOOP_DELAY_MS` | 2000 ms | Plain-lyrics browse advance check interval. |

## GitHub Actions

`.github/workflows/build.yml` runs for pull requests to `main`, pushes to `main`, and tags matching `v*`.

PR/main builds run unit tests, lint, and a debug APK build. The Windows regression job repeats unit tests, debug assemble, and lint with `gradlew.bat`.

`v*` tag builds validate PetitLyrics release configuration, build the release APK, upload it, and create a GitHub Release. Release mechanics are documented in [RELEASE.md](RELEASE.md).

## Tests and regression checklist

Run before merging provider/resolver changes:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
.\gradlew.bat lintDebug
```

Provider/matching regression cases should continue to cover:

- Japanese native-script artist vs romanized player metadata with valid corroboration.
- Cross-script same-title candidate without corroboration is rejected.
- Multi-contributor player metadata can match one exact contributor on a near-exact title.
- Unrelated contributor does not become a valid artist match.
- Japanese/Latin interleaved payload receives a quality penalty.
- PetitLyrics Type 3 parsing.
- PetitLyrics Type 2 timing decode + Type 1 companion selection.
- Musixmatch anonymous token parsing and `UpgradeOnly` rejection.
- Musixmatch macro parsing uses the explicit `matcher.track.get` result.
- A failed matcher call cannot cause unrelated subtitle/richsync data to be accepted.
- Embedded Musixmatch RichSync is parsed before line subtitle fallback.
- RichSync uses `ts + o` for word timing.
- Japanese/no-space RichSync text is preserved without artificial display spaces.
- Synchronized candidates outrank plain candidates.

Manual DHU regression pass after Android Auto UI changes:

1. Lyrics / Sync / More tabs remain in that order.
2. Lyrics shows the intended window size with and without translations.
3. Current-row `▶` gutter aligns with surrounding lyric text.
4. Sync remains a fixed three-row preview when enough lines exist.
5. `−50 ms` / `+50 ms` changes AA offset and refreshes the view.
6. More shows provider/details without affecting timing.
7. Now-playing subtitle marks the current lyric with `▶`.
8. Plain lyrics still advance correctly.
9. WORD_SYNC highlighting does not inject spaces into scripts whose RichSync chunks are naturally adjacent.

## Key files

| File | Purpose |
|---|---|
| `app/src/main/java/com/autolyrics/media/MediaTracker.kt` | Active media tracking, provider concurrency/budgets, resolver orchestration, cache refresh policy. |
| `app/src/main/java/com/autolyrics/lyrics/LrcLibClient.kt` | LRCLIB lookup, provider-specific matching, relaxed search. |
| `app/src/main/java/com/autolyrics/lyrics/MusixmatchClient.kt` | Musixmatch mobile token/macro/RichSync/subtitle flow. |
| `app/src/main/java/com/autolyrics/lyrics/PetitLyricsClient.kt` | PetitLyrics search, Type 3/Type 2 parsing, query provenance. |
| `app/src/main/java/com/autolyrics/lyrics/LyricsProviderResolver.kt` | Common metadata/quality/source scoring and final provider selection. |
| `app/src/main/java/com/autolyrics/lyrics/LyricsCache.kt` | Persistent lyrics cache and per-entry refresh interval. |
| `app/src/main/java/com/autolyrics/lyrics/MetadataCleaner.kt` | Player metadata cleanup before provider search. |
| `app/src/main/java/com/autolyrics/auto/LyricsBrowserService.kt` | Android Auto MediaBrowser tree, MediaSession metadata, Sync controls, karaoke text. |
| `app/src/main/res/xml/automotive_app_desc.xml` | Declares the Android Auto media integration. |
| `.env.example` | Local PetitLyrics template / no-`.env` fallback. |
| `.github/workflows/build.yml` | CI, release build, release-secret injection/validation, GitHub Release creation. |
| `RELEASE.md` | Release checklist and version/tag invariants. |
