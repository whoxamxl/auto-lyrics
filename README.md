# OpenAutoLyrics

**Synchronized lyrics for Android Auto and phone, with multi-provider matching, word-synced karaoke, translations, and timing controls.**

OpenAutoLyrics follows the active Android media session and resolves lyrics from multiple providers instead of accepting the first response. It is designed for sideloaded Android Auto use as well as a phone companion/performance view.

## Highlights

- **Android Auto lyrics** with a moving lyric window and now-playing lyric line
- **Multi-provider resolution** across LRCLIB, Musixmatch, optional PetitLyrics, and Karaoke-only SyncLRC
- **Candidate scoring** based on recording metadata, lyric quality, synchronization quality, and provider confidence
- **Word-synced Karaoke** using Musixmatch RichSync and Enhanced-LRC timing when available
- **Translation support** with on-device language detection and translation
- **Timing controls** for global/phone playback and a separate Android Auto offset
- **Robust metadata matching** for versions, remasters, native/romanized artist names, and multi-artist metadata
- **Local caching** with separate normal/Karaoke variants
- **Demand-aware provider work** so lyric lookups run only while the phone UI or Android Auto projection needs them
- **Unit/regression coverage** for provider parsing, matching, timing, and layout behavior

## How it works

```text
Phone Activity lifecycle ───────┐
Android Auto CarConnection ─────┤
                                ▼
                      LyricsDemandController
                                │
                                ▼
MediaListenerService → MediaTracker
                         │
                         ├── LRCLIB ─────────┐
                         ├── Musixmatch ─────┤
                         ├── PetitLyrics ────┤→ LyricsProviderResolver
                         └── SyncLRC ────────┘   (Karaoke mode)
                                │
                         LyricsCache / translation
                                │
                    ┌───────────┴───────────┐
                    ▼                       ▼
                Phone UI            Android Auto UI
```

The resolver compares plausible candidates using one common scoring model. A richer timing format does not automatically beat a materially better recording match. Karaoke mode can prefer real word/timing-token data only when the candidate remains close enough in recording and payload quality.

See [DEVELOPMENT.md](DEVELOPMENT.md) for the resolver, cache policy, provider formats, Android Auto behavior, and implementation notes.

## Providers

| Provider | Normal mode | Karaoke / word timing | Notes |
| --- | --- | --- | --- |
| LRCLIB | Yes | No | Primary synchronized/plain source |
| Musixmatch | Yes | Yes | RichSync when available; uses an unofficial/internal API surface |
| PetitLyrics | Optional | Provider-dependent | Requires locally configured client values |
| SyncLRC | No | Yes | Queried only in Karaoke mode for genuine Enhanced-LRC timing |

Provider availability and unofficial/internal API behavior can change independently of OpenAutoLyrics.

## Requirements

- Android 8.0 / API 26 or newer
- Android Studio / Android SDK 34 for source builds
- JDK 17
- Notification access so OpenAutoLyrics can inspect active media sessions
- Android Auto developer mode / **Unknown sources** for sideloaded builds

## Install

Prebuilt APKs are published under [GitHub Releases](https://github.com/whoxamxl/OpenAutoLyrics/releases/latest).

For sideloaded Android Auto use:

1. Enable Android Auto developer mode.
2. Open **Developer settings**.
3. Enable **Unknown sources**.
4. Install and open OpenAutoLyrics on the phone.
5. Grant notification access.
6. Reconnect Android Auto if the app does not appear immediately.

## Build from source

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Debug APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install with ADB:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

### Optional PetitLyrics configuration

Copy `.env.example` to `.env` and provide your own registered values:

```dotenv
PETITLYRICS_USER_ID=
PETITLYRICS_APP_NAME=
PETITLYRICS_PKG_NAME=
PETITLYRICS_CLIENT_APP_ID=
```

`.env` is ignored by Git. These values are compiled into the APK and therefore must not be treated as cryptographically secret after distribution.

## Android Auto UI

The browse UI is organized into three areas:

- **Lyrics** — primary synchronized lyric view
- **Sync** — Android-Auto-specific timing correction with `−50 ms` / `+50 ms` controls
- **More** — track, provider, synchronization, language, duration, and offset information

The phone UI also provides a performance/Karaoke view for finer-grained highlighting.

## Release and development documentation

- [DEVELOPMENT.md](DEVELOPMENT.md) — architecture, resolver, providers, timing, cache behavior, and regression notes
- [RELEASE.md](RELEASE.md) — signing, CI release workflow, versioning, and release verification

## Project history

OpenAutoLyrics is an independently maintained project originally derived from [GitUpGitUp/auto-lyrics](https://github.com/GitUpGitUp/auto-lyrics). The original Git history and author attribution are preserved.

The project has since expanded with a multi-provider resolver, additional providers, Karaoke/word timing, translation support, Android Auto UI changes, Spotify/media-session robustness, caching changes, and automated tests.

## License

OpenAutoLyrics is released under the **MIT License**. See [LICENSE](LICENSE).
