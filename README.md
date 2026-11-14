# Sonora: a fast, free music player for Android, powered by YouTube

A native Kotlin + Jetpack Compose music app. It works like Spotify, takes its catalog from YouTube / YouTube Music the way ReVanced and NewPipe do, plays audio only, has no ads, needs no account, and plays offline.

## Features

**Listening**
- Search YouTube Music songs, with live suggestions and recent searches
- Home feed with Quick picks based on what you last played, plus Trending, Viral, Chill, Throwback and more. Pull to refresh.
- Mood chips (Chill, Focus, Workout, Party, Sleep…) and a colorful "Browse all" genre grid
- **Endless autoplay.** Tapping a song starts a radio of related tracks, and the queue tops itself up as it runs low.
- Background playback with a lock-screen and notification player. Works with Bluetooth, headset buttons and Android Auto via Media3 MediaSession.
- Shuffle, repeat one/all, queue editing (jump to or remove a track), play next, add to queue
- Sleep timer, sharing
- **Lyrics.** Time-synced lyrics (from LRCLIB) that follow the song: the current line is highlighted and you can tap any line to jump there. Falls back to YouTube Music's lyrics, and results are cached for offline use.
- **Dislike.** Hide a song everywhere (search, home, radio, Liked, playlists). If it's playing, it's skipped. Library → Disliked songs lists everything you've hidden, and you can undo one or restore them all.
- **Content filter.** Thai / Thailand content is blocked everywhere: Thai-script titles, Thai keywords, Thai labels and artists. It applies to search queries, suggestions, results, the home feed, radio/autoplay and YouTube Music lists. The rules live in `data/filter/ContentFilter.kt`.

**Offline**
- **Download any song.** It is saved to private app storage and plays with no connection.
- "Download all" for Liked Songs and playlists
- Everything you stream is also cached on disk (768 MB LRU), so a song you've played all the way through replays instantly and offline
- Artwork is cached (300 MB) so your library looks right offline too
- Liked Songs, playlists and history are stored locally in Room. They work offline and need no account.

**UI and UX**
- Dark theme with a lime accent, bold type and 2025–26 style
- The full-screen player picks up the artwork's color with an animated gradient
- The artwork grows slightly while playing and shrinks when paused. Swipe it left or right to skip.
- Drag the player down to dismiss it. Swipe the mini player to skip.
- The mini player has a live progress line
- Animated equalizer bars mark the song that's playing in every list
- Shimmer placeholders while loading, empty and offline states that tell you what to do, and edge-to-edge layout

## Performance

| What | How |
|---|---|
| Instant start | Only 1 s of buffer is needed before playback begins (`DefaultLoadControl`) |
| No YouTube throttling | Streams are fetched in 1 MB range chunks (`YtDataSource`). Downloads use 2 MB chunks. |
| Smooth scrolling | Artwork is decoded at the size it's shown (160 px rows, 1080 px player). Lists are keyed. Data classes are `@Immutable`. |
| Few recompositions | The seek bar, mini-player progress, equalizer and shimmer animate in the draw phase only |
| Fast lookups | Stream URLs are cached until they expire. Home feed sections load in parallel and are cached for 30 minutes. Artwork colors use an LRU cache. |
| Small APK | R8 minify + resource shrinking. No DI framework and no Google Play Services. |

## Build it

### Option A: GitHub, no setup needed
1. Create a new GitHub repo and push this folder to it.
2. The **Actions** tab runs `Build APK` automatically.
3. Download the `Sonora-release-apk` artifact and install it on your phone. You'll need to allow installs from unknown sources.

### Option B: Android Studio
1. Open this folder in Android Studio (Ladybug or newer).
2. Let Gradle sync. It needs JDK 17, which Android Studio bundles.
3. For a quick test run the `app` config. For full speed use `./gradlew assembleRelease`.

> Debug builds of Compose apps are slow by design. **Judge performance on the release APK.**

## Architecture

```
app/src/main/java/com/sonora/music/
├── AppGraph.kt              # singletons (OkHttp, Room, cache, repos) — no DI lib
├── data/
│   ├── youtube/             # NewPipeExtractor bridge: search, suggestions, streams, related, home feed
│   ├── db/                  # Room: songs (likes/history/downloads), playlists, search history
│   ├── download/            # WorkManager chunked downloader
│   └── LibraryRepository.kt
├── playback/
│   ├── PlaybackService.kt   # Media3 MediaSessionService + ExoPlayer
│   ├── YtDataSource.kt      # yt://song/<id> → downloaded file → disk cache → YouTube
│   └── PlayerConnection.kt  # MediaController wrapper exposing StateFlows to the UI
└── ui/                      # Compose: home, search, library, collection, player
```

## Maintenance: when YouTube breaks playback

YouTube changes its internals regularly. When songs stop playing, update this line in `app/build.gradle.kts` to the
[latest NewPipeExtractor release](https://github.com/TeamNewPipe/NewPipeExtractor/releases):

```kotlin
implementation("com.github.teamnewpipe:NewPipeExtractor:v0.27.1")
```

Then rebuild. That fixes it most of the time.

## Notes
- This is a personal project. Streaming and downloading from YouTube without Premium is against YouTube's Terms of Service. Use it for yourself and don't publish it on the Play Store.
- NewPipeExtractor is GPL-3.0, so if you distribute this app its source must also be GPL-3.0.
- The release build is signed with the debug key for convenience. Use your own keystore if you share the APK.
