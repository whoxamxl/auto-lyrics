# Auto Lyrics

Auto Lyrics is an Android app that follows the active media session and displays lyrics on both the phone and Android Auto. It supports synchronized lyrics from **LRCLIB** and, when configured, **PetitLyrics**, then scores the available candidates instead of hard-coding one provider as the first choice.

## Features

- **Android Auto lyrics** — current line is marked with `▶` and surrounded by nearby lines.
- **Lyrics / Sync / More browse tabs** — lyrics are the primary view, timing adjustment is kept separate, and detailed track/provider information lives under More.
- **Adaptive lyric window** — the main Lyrics tab shows 5 rows without translations and 3 rows when translated subtitles are present.
- **Fixed 3-row Sync preview** — current line plus surrounding context while adjusting the Android Auto offset.
- **Now-playing lyric line** — the Android Auto now-playing card also carries the current lyric line.
- **Multi-provider resolution** — LRCLIB and PetitLyrics can be queried in parallel and compared using metadata match, lyric quality, and source confidence.
- **Synced and plain fallback** — synchronized lyrics are preferred; LRCLIB plain text remains a last-resort fallback.
- **Robust metadata matching** — handles recording/version qualifiers, romanized-vs-native-script artist names, and multi-contributor metadata such as `Alan Menken, Howard Ashman, Samuel E. Wright, Disney`.
- **Works with normal media-session players** — Spotify, YouTube Music, Apple Music, Poweramp, and other apps that expose usable Android media metadata.
- **Phone companion UI** — lyrics remain available on the phone, including the performance/karaoke view.
- **Local cache** — previously resolved lyrics are shown immediately and refreshed according to provider confidence/availability.

## Architecture

```text
MediaListenerService (NotificationListenerService)
    │
    ▼
MediaTracker (singleton / StateFlow)
    │
    ├── track + playback state
    ├── LRCLIB ────────────────┐
    ├── PetitLyrics ───────────┤ parallel when configured
    ├── LyricsProviderResolver ◀┘
    ├── LyricsCache
    └── translation / album-art helpers
            │
            ├──────────────────────────┐
            ▼                          ▼
      Phone UI                 LyricsBrowserService
                               (Android Auto MediaBrowser + MediaSession)
```

The provider resolver compares plausible candidates with one common final scoring model. Synchronized candidates always outrank plain lyrics. See [DEVELOPMENT.md](DEVELOPMENT.md) for the scoring, cache policy, provider formats, and implementation details.

## Requirements

- Android 8.0 / API 26 or newer
- Android Studio with Android SDK 34
- JDK 17
- Notification access for Auto Lyrics so it can inspect active media sessions
- Android Auto developer mode / unknown sources when using a sideloaded build

## Install a Release

Prebuilt APKs are published under [GitHub Releases](https://github.com/whoxamxl/auto-lyrics/releases/latest).

Because Auto Lyrics is sideloaded, Android Auto normally needs developer mode enabled before the app is shown:

1. Open Android Auto settings on the phone.
2. Enable Android Auto developer mode.
3. Open **Developer settings**.
4. Enable **Unknown sources**.
5. Reconnect Android Auto if Auto Lyrics does not appear immediately.
6. Open Auto Lyrics on the phone and grant notification access.

## Build from Source

### Basic build

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

Install it with ADB:

```powershell
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

### PetitLyrics configuration

LRCLIB works without extra configuration. PetitLyrics is optional in local builds and requires your own registered client values.

Copy the template:

```powershell
Copy-Item .env.example .env
```

Then fill in:

```dotenv
PETITLYRICS_USER_ID=
PETITLYRICS_APP_NAME=
PETITLYRICS_PKG_NAME=
PETITLYRICS_CLIENT_APP_ID=
```

`.env` is ignored by Git. Do not commit private client values. The values are compiled into `BuildConfig`, so they should also **not be treated as cryptographically secret once an APK is distributed**.

Configuration precedence is:

```text
process environment
    ↓
.env
    ↓ only when .env does not exist
.env.example
```

See [DEVELOPMENT.md](DEVELOPMENT.md) for provider details and [RELEASE.md](RELEASE.md) for the release workflow.

## Android Auto UI

### Lyrics

The primary browse tab. It shows the track header plus a moving lyric window:

```text
   previous line
   previous line
▶  current line
   next line
   next line
```

The non-current rows reserve a visual gutter so their lyric text aligns with the current row. When translations are present, the main window is reduced to 3 rows to avoid overcrowding.

### Sync

The secondary tab for Android Auto timing correction. It keeps a fixed 3-row lyric preview and provides `−50 ms` / `+50 ms` controls. This offset is Android-Auto-specific and is separate from the phone/global lyric offset.

### More

Shows detailed information such as title, artist, album, provider, synchronization state, detected language, duration, and Android Auto offset.

## How Lyrics Are Resolved

1. The active media session provides title, artist, album, duration, playback state, and position.
2. Metadata is cleaned before lookup.
3. LRCLIB and configured PetitLyrics requests are launched in parallel with provider time budgets.
4. Provider-specific search logic gathers plausible candidates. Searches may progressively relax their metadata constraints.
5. `LyricsProviderResolver` validates metadata and compares candidate quality/source confidence.
6. Synchronized results are preferred. LRCLIB plain lyrics are used only when no acceptable synchronized result exists.
7. The selected result is cached. Incomplete provider comparisons are refreshed sooner than fully corroborated selections.

This design is intentionally not “first provider to respond wins”; normal request latency is not part of the score. A provider only loses due to speed when it exceeds its timeout budget and therefore fails to produce a candidate for that comparison.

## Testing with Android Auto Desktop Head Unit (DHU)

1. Install **Android Auto Desktop Head Unit emulator** from Android Studio → SDK Manager → SDK Tools.
2. Enable Android Auto developer mode on the phone.
3. Enable **Unknown sources** in Android Auto developer settings.
4. Install the Auto Lyrics debug APK on the phone.
5. Connect the phone to DHU using the Android Auto/DHU setup supported by your SDK version.
6. Open **Lyrics** from the Auto Lyrics browse UI and verify line updates, Sync controls, and More metadata.

Useful provider logs during development:

```powershell
adb logcat -s "ProviderResolver:D" "PetitLyrics:D" "*:S"
```

## Known Constraints

- Lyrics availability depends on the upstream providers; not every recording has usable synchronized lyrics.
- PetitLyrics integration uses an internal/unofficial endpoint and can change independently of Auto Lyrics.
- Media-session metadata quality varies by player. Auto Lyrics includes heuristics for common metadata problems, but unusual formats can still miss or mismatch.
- PetitLyrics client identifiers embedded in a distributed Android APK are extractable by design; do not use values that rely on client-side secrecy.
- Sideloaded Android Auto media apps generally require Android Auto developer settings to be enabled.

## Developer Documentation

- [DEVELOPMENT.md](DEVELOPMENT.md) — architecture, provider resolver, cache behavior, PetitLyrics formats, and Android Auto implementation notes.
- [RELEASE.md](RELEASE.md) — versioning, release secrets, tagging, and post-release verification.

## License

MIT
