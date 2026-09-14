# Auto Lyrics — Development Reference

This document describes the current implementation rather than the original LRCLIB-only design. Keep user-facing setup in `README.md`; keep release mechanics in `RELEASE.md`.

## Terminology

| Term | Meaning |
|---|---|
| **Lyrics tab** (AA) | Primary Android Auto browse section. Shows track metadata plus a moving lyric window. |
| **Sync tab** (AA) | Android Auto timing-adjustment section with a fixed three-row lyric preview and ±50 ms controls. |
| **More tab** (AA) | Detailed track/lyrics information: provider, sync state, language, duration, offset, etc. |
| **Now-playing card** (AA) | Standard Android Auto media now-playing UI rendered from `MediaMetadataCompat`. Current lyrics are placed in the display subtitle. |
| **WORD_SYNC** | Synchronized lyrics with usable sub-line timing tokens. A token may be a word, syllable, fragment, or character; the name does not guarantee linguistic word segmentation. |
| **LINE_SYNC** | Synchronized lyrics with line timestamps but no usable sub-line timing tokens. |
| **Timing token** | Raw provider unit stored as `LyricWord(timeMs, text, endTimeMs?)`. Timing tokens are preserved even when rendering groups several tokens into one display word/phrase. |
| **Display grouping** | Renderer-only mapping from fine timing tokens to human-readable lexical ranges. It never rewrites provider timestamps in cache/state. |
| **Plain lyrics** | Unsynchronized text represented by `LyricsStatus.PLAIN_ONLY`. |
| **LRCLIB** | Provider supporting synchronized and plain results with documented duration metadata. |
| **Musixmatch** | Provider using the anonymous mobile API; supports RichSync timing and line-synced subtitle fallback. |
| **PetitLyrics** | Optional Japanese-oriented provider enabled by build-time client configuration; Type 3 exposes token start/end timing. |
| **SyncLRC** | Karaoke-only public API source for Enhanced LRC. Auto Lyrics accepts only real `type=karaoke` responses from this provider. |
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
       ├─ SyncLrcClient ────────────┤ Karaoke mode only
       ├─ LyricsProviderResolver ◀──┘
       ├─ LyricsCache (STANDARD / KARAOKE variants)
       ├─ MetadataCleaner
       ├─ LrcParser
       ├─ KaraokeTiming
       ├─ LyricsTranslator
       └─ AlbumColorExtractor

Phone UI
  ├─ MainActivity
  └─ PerformanceActivity
       └─ LyricWordLayout display grouping

Android Auto
  └─ LyricsBrowserService
       ├─ MediaBrowser browse tree
       │   ├─ Lyrics
       │   ├─ Sync
       │   └─ More
       ├─ MediaSession / now-playing metadata
       ├─ transport-control proxy
       └─ LyricWordLayout display grouping
```

`MediaListenerService` is a `NotificationListenerService`; its access allows the app to inspect active media sessions through `MediaSessionManager`. The service keeps the currently selected playing session sticky while it remains active so another media session does not steal selection merely because controller ordering changes.

## Track detection and position

`MediaTracker` extracts title, artist, album, duration, and artwork from the active media controller. Metadata is normalized by `MetadataCleaner` before a `TrackInfo` is created.

Track changes are debounced by 600 ms before a new lookup starts. While playback is active, position is reconstructed from the media session's reported position, last update time, and playback speed. Synced lyric indices are checked every 150 ms.

The phone/global offset is included by `MediaTracker.getCurrentPositionMs()`. Android Auto applies its own additional `aa_offset_ms` inside `LyricsBrowserService`.

Do **not** add a provider-wide pre-offset merely because lyrics appear early on one output path. The media-session logical position can lead audible output because of Bluetooth, codec, Android Auto, DAC, or head-unit buffering. Stable output-path latency belongs in the existing phone/global or AA-specific offset controls unless a source timestamp bias is demonstrated independently across multiple playback paths.

## Provider execution

In standard mode:

```text
LRCLIB ───────┐
Musixmatch ───┼─ parallel comparison
PetitLyrics ──┘  when configured
```

In Karaoke mode:

```text
LRCLIB ───────┐
Musixmatch ───┤
PetitLyrics ──┤ when configured
SyncLRC ──────┼─ parallel comparison
              └─ WORD_SYNC coverage only
```

Each provider gets a hard **5 second total budget** at the `MediaTracker` layer. Provider speed is **not part of the score**. A 200 ms response receives no scoring advantage over a 2 s response as long as both complete inside the budget.

Musixmatch and SyncLRC are treated as opportunistic sources for cache-completeness purposes. Their temporary unavailability does not by itself make a healthy LRCLIB/PetitLyrics standard comparison incomplete. Karaoke caching has an additional rule described below so a temporary word-sync miss does not pin LINE_SYNC for a week.

Debug logging:

```powershell
adb logcat -s Musixmatch:D SyncLRC:D ProviderResolver:D PetitLyrics:D
```

Typical diagnostics include elapsed time, timeout state, candidate presence, metadata score, quality score, source confidence, synchronization kind, resolver mode, and selected provider.

## Provider resolution

Provider-specific clients perform lookup/fallback and return normalized `LyricsProviderCandidate` objects. `LyricsProviderResolver` then validates and scores candidates together.

The base final score is:

```text
final score = metadata match     × 0.82
            + lyric quality      × 0.10
            + source confidence  × 0.08
```

A synchronized candidate (`LyricsStatus.FOUND`) always outranks a plain candidate. LRCLIB plain lyrics remain the last-resort fallback when no acceptable synchronized candidate exists.

### Standard vs Karaoke selection

The resolver deliberately separates recording correctness from timing richness.

**Standard mode** is accuracy-first: the synchronized candidate with the best ordinary final score wins.

**Karaoke mode** first computes that same standard winner, then considers only candidates that:

- declare `WORD_SYNC`,
- actually contain `LyricWord` timing data,
- have metadata score no more than **0.03** below the standard winner,
- have lyric payload quality no more than **0.05** below the standard winner.

Among those near-equivalent word-timed candidates, the highest ordinary final score wins. This allows, for example, an equivalent Musixmatch/SyncLRC word-timed result to replace an LRCLIB LINE_SYNC result during Karaoke mode without allowing a materially weaker live/remix/mismatched recording to win just because it has richer timestamps.

### Metadata validation

The resolver validates recording/version qualifiers before scoring. Metadata weighting is approximately:

```text
title     55%
artist    30%
duration  12%  (when reliable)
album      3%
```

Weights are renormalized when a field is unavailable or intentionally treated as non-comparable.

PetitLyrics duration is not trusted for common resolver scoring because observed response fields are not sufficiently reliable across formats/clients. LRCLIB, Musixmatch, and SyncLRC duration may be used when present.

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

For near-exact title matches, artist similarity can inspect comma/semicolon-delimited components and accept a strong component match such as `Samuel E. Wright`. The full media-session artist string is preserved; it is not globally truncated at the first comma.

### Lyric payload quality

The resolver scores payload quality separately from metadata. Current checks include:

- suspicious Japanese / Latin-only alternation,
- near-duplicate timestamps around transliteration lines,
- lyrics extending far beyond the track duration,
- unusually short timing coverage for a long synchronized track,
- very late first timestamps.

The Japanese/Latin alternation penalty primarily catches LRCLIB entries containing interleaved romanized transliterations as if they were separate timed rows.

### Source confidence

Source confidence is deliberately only 8% of the final score. Current policy favors PetitLyrics Type 3/Type 2 on Japanese tracks and LRCLIB synchronized data on non-Japanese tracks. Musixmatch and SyncLRC currently use the generic synchronized-provider confidence rather than receiving a special high-trust override. Metadata and payload quality remain dominant.

## Timing tokens and display grouping

Raw timing granularity and visual highlight granularity are intentionally separate.

A provider may return:

```text
Pro @ 1000 ms
vi  @ 1100 ms
der @ 1200 ms
```

while the original source line says `Provider`. `LyricLine.words` retains all three timestamps. `LyricWordLayout` aligns those raw tokens back to `LyricLine.text` and highlights the lexical source range `Provider` for each of them.

For Japanese, source data may be character-granular:

```text
君 / を / 忘 / れ / な / い
```

The renderer uses Java/Android word boundaries plus a conservative Japanese display heuristic that merges an adjacent hiragana suffix into a preceding kanji-containing range. The result is intended to look closer to `君を / 忘れない` than six separate character highlights. This is a UI heuristic, not a morphological analyzer; raw provider timing remains untouched.

`LyricWordLayout` falls back to its previous separator reconstruction when provider timing tokens cannot be aligned to the original source line.

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

The macro query includes title and artist plus album/duration when available. The provider explicitly reads `macro_calls["matcher.track.get"]`; unrelated nested track objects are not accepted as the match.

The returned matcher metadata is normalized into a candidate and passed through the same local metadata validation used by the resolver. Instrumental mismatches and weak metadata matches are rejected before lyric payload acceptance.

If a request returns HTTP/API 401, the cached token is invalidated and one fresh-token retry is allowed.

### RichSync

RichSync line objects contain a line start (`ts`) and chunks whose `o` value is an offset from that line start:

```text
timing token timestamp = ts + o
```

Those timestamps are stored in `LyricWord` and persist through `LyricsCache`. Chunk text is kept without synthetic separators. `LyricWordLayout` reconstructs the original line and performs display grouping at render time.

If RichSync is absent or unusable, `track.subtitles.get` LRC is parsed as LINE_SYNC. Both are `LyricsStatus.FOUND`; only timing granularity differs.

Musixmatch uses an unofficial/internal endpoint and is not affiliated with this project. Availability and response behavior may change independently of Auto Lyrics.

## PetitLyrics provider

The PetitLyrics integration follows the request/response structure demonstrated by the reference project `whoxamxl/petitlyric_sync_lyric_download` while using this app's configured identifiers.

Supported formats:

- **lyricsType=3 / WSY** — word/token-sync XML. Each `<word>` contributes `starttime`, optional `endtime`, and raw `wordstring` to `LyricWord`. Empty word strings still establish a line timestamp but do not create invisible karaoke tokens.
- **lyricsType=2 / LSY** — binary line-sync timing combined with a **lyricsType=1** plain-text companion, preferably resolved by the same `lyricsId`.

Type 3 can be finer than linguistic words. Japanese payloads may contain one character per timed element and English payloads can contain fragments. That granularity is preserved in state/cache and normalized visually by `LyricWordLayout` rather than merged destructively in the provider parser.

`endTimeMs` is preserved through `LyricsCache` and used by `KaraokeTiming`. When a Type 3 word explicitly ends before the next word starts, the gap has no active highlight instead of keeping the previous token lit.

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

## SyncLRC provider

`SyncLrcClient` is a Karaoke-mode-only consumer of the public SyncLRC API:

```text
GET https://synclrc.dev/lyrics
    ?track=<title>
    &artist=<artist>
    &type=karaoke
    [&album=<album>]
    [&duration=<rounded seconds>]
```

SyncLRC documents Karaoke as Enhanced LRC. Its public project uses LRCLIB as its primary lyrics/metadata path and an upstream LDDC/syncedlyrics stack for Karaoke coverage from sources including NetEase, QQ Music, Kugou, and Musixmatch.

Auto Lyrics does not copy or embed SyncLRC/LDDC source code; it only calls the public JSON API.

### Fallback handling

SyncLRC intentionally falls back when requested Karaoke is unavailable. A `type=karaoke` request can therefore return a body whose actual `type` is `synced` or `plain`.

Auto Lyrics rejects those fallback responses in `SyncLrcClient`. This provider creates a candidate only when:

```text
response.type == "karaoke"
AND Enhanced LRC parses successfully
AND at least one line contains timed tokens
```

LRCLIB already covers LINE_SYNC/plain directly, so accepting SyncLRC's fallback would only duplicate that path and could distort resolver confidence.

### Enhanced LRC parser behavior

`LrcParser.parseKaraoke()` accepts line tags such as:

```text
[00:01.00]
```

and timed-token tags such as:

```text
<00:01.25>
```

Dot or colon fractional separators are tolerated. The parser removes timing tags to recover exact `LyricLine.text`, while each raw substring between timed tags becomes a `LyricWord`. Spaces attached to a token are retained so the original source line can be reconstructed exactly.

SyncLRC is queried only in Karaoke mode. It currently uses the resolver's generic synchronized-provider confidence; it is not granted an unconditional preference over direct providers.

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
cache namespace (v13)
STANDARD or KARAOKE variant
normalized title
normalized artist
normalized album
rounded duration in seconds
```

A cached result is displayed immediately. `LyricsCache` preserves line timestamps plus `LyricWord(timeMs, text, endTimeMs)`, so Musixmatch RichSync, PetitLyrics Type 3, and Enhanced-LRC timing survive restart/reload.

Normal and Karaoke selections use separate cache variants. This prevents a Karaoke-specific WORD_SYNC winner from pinning normal mode, and prevents a standard LRCLIB LINE_SYNC winner from hiding a richer Karaoke candidate.

Per-entry `refreshAfterMs` controls background refresh:

- **Normal fully corroborated LRCLIB/PetitLyrics comparison:** 7 days.
- **Incomplete/provisional comparison:** 15 minutes.
- **Karaoke with a usable selected WORD_SYNC result and otherwise complete normal comparison:** 7 days.
- **Karaoke where no usable word-timed result was selected:** 15 minutes, even if the normal provider set is healthy.

When PetitLyrics is configured, the normal provider set is considered complete only when LRCLIB and PetitLyrics both return candidates and neither times out. Musixmatch and SyncLRC availability intentionally do not gate normal cache completeness because they are opportunistic word-sync sources.

### Known cache-result limitation

Provider clients still collapse several outcomes to `null`; the `MediaTracker` boundary does not fully distinguish:

```text
NOT_FOUND
TRANSIENT_ERROR
TIMEOUT
```

Timeout itself is tracked, but a clean provider “no result” and some transient failures are otherwise indistinguishable. A legitimate one-provider-only song can therefore remain on the 15-minute refresh policy instead of the normal 7-day policy. This costs extra traffic but avoids pinning a fallback result after a temporary outage.

A future cleanup can replace the nullable provider boundary with an explicit result type such as `Found / NotFound / TransientError`.

## Karaoke active-word timing

`KaraokeTiming.activeWordIndex()` is shared by phone/Performance and Android Auto.

Behavior:

- without explicit word end times, the active token is the latest token whose `timeMs <= position`,
- with `endTimeMs`, a token stops being active at that explicit end,
- a silent gap between explicit end and the next token has no active highlight,
- an ended newer token never causes an older open-ended token to reactivate,
- backward seeking within the same line selects the earlier token again.

This timing selection is independent from `LyricWordLayout` display grouping. The timing layer picks a raw token index; the renderer decides which lexical source-text range that token should visually highlight.

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

### Sync tab

The Sync tab uses a fixed three-row lyric preview and provides AA-specific `−50 ms` / `+50 ms` controls. The current row can use Karaoke text when `LyricWord` data exists.

### More tab

More can show title, artist, album, provider, lyric synchronization state, detected language, duration, and AA offset.

### Now-playing subtitle

`MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE` carries the current lyric text. The original line is prefixed with `▶`; translated text, when present, is placed on the second line without another marker.

### Browse refresh behavior

Browse refreshes notify section IDs instead of rebuilding root tabs on every update. The browse tree is throttled to reduce Android Auto churn. Future-word look-ahead was removed: Auto Lyrics no longer intentionally advances 300/600 ms into a future word to mask browse latency.

The browse tree can therefore update somewhat coarser or later than the phone/Performance UI. The now-playing subtitle is checked every 200 ms.

Current constants include:

| Constant | Value | Purpose |
|---|---:|---|
| `SYNC_WINDOW_SIZE` | 3 | Fixed lyric row count in Sync. |
| `DEFAULT_WINDOW_SIZE` | 5 | Lyrics-tab row count without translations. |
| `CURRENT_LINE_PREFIX` | `▶  ` | Current row marker. |
| `PAD_WIDTH` | 60 | Character padding for browse items. |
| `NOTIFY_THROTTLE_MS` | 500 ms | Minimum browse-tree refresh interval. |
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
- Standard mode preserves accuracy-first selection.
- Karaoke mode prefers only near-equivalent candidates with actual word/timing-token payloads.
- PetitLyrics Type 3 parses exact `starttime`, `endtime`, and `wordstring` into `LyricWord`.
- PetitLyrics Type 2 timing decode + Type 1 companion selection.
- Explicit PetitLyrics word-end gaps become unhighlighted gaps.
- Musixmatch anonymous token parsing and `UpgradeOnly` rejection.
- Musixmatch macro parsing uses the explicit `matcher.track.get` result.
- A failed matcher call cannot cause unrelated subtitle/RichSync data to be accepted.
- Embedded Musixmatch RichSync is parsed before line subtitle fallback.
- RichSync uses `ts + o` for timing.
- Japanese/no-space RichSync retains exact line text and timing tokens.
- Enhanced LRC preserves source spacing and absolute timing.
- SyncLRC accepts only real `type=karaoke` responses and rejects synced/plain fallback bodies.
- Fine English fragments map to the whole source word for display.
- Japanese character timing maps to word-like display ranges while raw timing remains unchanged.
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
9. WORD_SYNC display grouping preserves source text for English, Japanese/no-space, and mixed-script lines.
10. Android Auto does not mark a future timing token before its timestamp solely to hide browse refresh latency.
11. Karaoke ON can select an equivalent word-timed provider while Karaoke OFF can independently retain the standard winner.

## Key files

| File | Purpose |
|---|---|
| `app/src/main/java/com/autolyrics/media/MediaTracker.kt` | Active media tracking, provider concurrency/budgets, mode-aware resolver orchestration, cache refresh policy. |
| `app/src/main/java/com/autolyrics/lyrics/LrcLibClient.kt` | LRCLIB lookup, provider-specific matching, relaxed search. |
| `app/src/main/java/com/autolyrics/lyrics/MusixmatchClient.kt` | Musixmatch mobile token/macro/RichSync/subtitle flow. |
| `app/src/main/java/com/autolyrics/lyrics/PetitLyricsClient.kt` | PetitLyrics search, Type 3 token timing, Type 2 line timing, query provenance. |
| `app/src/main/java/com/autolyrics/lyrics/SyncLrcClient.kt` | Karaoke-only public SyncLRC API integration and fallback rejection. |
| `app/src/main/java/com/autolyrics/lyrics/LrcParser.kt` | Standard LRC and Enhanced-LRC parsing with exact source-text reconstruction. |
| `app/src/main/java/com/autolyrics/lyrics/LyricsProviderResolver.kt` | Common metadata/quality/source scoring and mode-aware final provider selection. |
| `app/src/main/java/com/autolyrics/lyrics/LyricsCache.kt` | Persistent STANDARD/KARAOKE lyrics cache and per-entry refresh interval. |
| `app/src/main/java/com/autolyrics/lyrics/KaraokeTiming.kt` | Shared raw-token active-index selection including explicit end times. |
| `app/src/main/java/com/autolyrics/lyrics/MetadataCleaner.kt` | Player metadata cleanup before provider search. |
| `app/src/main/java/com/autolyrics/util/LyricWordLayout.kt` | Exact source-text layout plus fine-token → display-word grouping. |
| `app/src/main/java/com/autolyrics/auto/LyricsBrowserService.kt` | Android Auto MediaBrowser tree, MediaSession metadata, Sync controls, Karaoke text. |
| `app/src/main/res/xml/automotive_app_desc.xml` | Declares the Android Auto media integration. |
| `.env.example` | Local PetitLyrics template / no-`.env` fallback. |
| `.github/workflows/build.yml` | CI, release build, release-secret injection/validation, GitHub Release creation. |
| `RELEASE.md` | Release checklist and version/tag invariants. |
