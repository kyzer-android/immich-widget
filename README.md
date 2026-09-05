# Immich Widget

A native Android home screen widget that displays photos from your self-hosted [Immich](https://immich.app) server — either from a fixed album or from Immich's "On This Day" memories, with a full-screen slideshow mode, ambient background music, and a background blur effect. No official Immich SDK, no third-party analytics, no ads — just direct REST calls to your own server.

<!-- Add a screenshot or GIF here before publishing, e.g.: -->
<!-- ![Widget screenshot](docs/screenshot.png) -->

## Features

### Two photo sources
- **Album mode** — cycles through photos from one album you choose, in random order on tap, or on an optional auto-advance timer
- **Memory mode** — shows Immich's "On This Day" memories ("X years ago"), grouped by year, with a badge showing how long ago each photo was taken
- **Both** — enable both sources at once and switch between them directly from two small icons on the widget

### Full-screen viewer
- Tap the fullscreen icon on the widget to open the current photo full-screen
- **Album**: swipe left/right through the album in chronological order; optional auto-advance slideshow (configurable interval, 0 = manual swipe only); continuous ambient background music that automatically queues a new track when the previous one ends; exact photo date shown at the bottom
- **Memory**: Instagram-style segmented progress bar; auto-advances through each year's photos (configurable duration per photo); automatically moves to the next year with a **new** background track; closes automatically after the last year; swipe manually without interrupting the music
- Both viewers share a blurred, color-matched background behind the photo (no plain black bars) and can be muted independently of the widget's other settings

### Background music
Ambient tracks are pulled from the [Free To Use](https://freetouse.com/api) public API (royalty-free, no API key required), filtered to calm/relaxing/ambient categories so it fits a photo slideshow rather than random genres. Premium-tagged tracks are automatically excluded to stay within free usage.

### Configuration
A tabbed settings screen (Settings / Album / Memory), reachable via the gear icon on the widget:
- **Settings** — server URL, API key (with a built-in permissions checklist), source mode (Album / Memory / Both), crop vs. fit display mode, shared auto-advance interval, and a battery-optimization check with a one-tap link to the system settings
- **Album** — load and pick an album, clear the local photo cache, per-photo slideshow duration
- **Memory** — manual "sync now" button, default sound on/off, per-photo slideshow duration

### Other details
- Dark theme, forced regardless of system theme, tuned for contrast
- Full French and English localization (follows the device's system language)
- Encrypted local storage for the server URL and API key (Android Keystore-backed)
- Photos are cached locally in WebP at a configurable resolution — no re-downloading on every tap

## Requirements

- A reachable Immich server (self-hosted), accessible over HTTPS from your phone
- An Immich API key with these scopes:
  - `album.read`
  - `asset.read`
  - `asset.view`
  - `memory.read` (only needed if you use Memory mode)
  
  On Immich versions older than 1.138.0, fine-grained key scopes don't exist yet — grant `all` instead.
- Android 8.0 (API 26) or newer

## Building

```bash
git clone <this-repo>
```

Open the project root (the folder containing `settings.gradle.kts`) in Android Studio. Android Studio will offer to sync the Gradle wrapper automatically. Build and run (`Build > Make Project`, then `Run`) on a device or emulator.

No official Immich SDK is used — all API calls go through plain OkHttp requests, so the app has no dependency on a specific client library version. If your Immich server is on a noticeably different version, check its Swagger docs (`/api/docs`) — some JSON field names (e.g. inside `/api/albums`, `/api/search/metadata`, `/api/memories`) have changed across Immich releases.

## Usage

1. Add the widget to your home screen
2. It starts empty — tap the ⚙️ icon in the corner to open settings
3. Enter your server URL and API key, then test the connection
4. Pick a source (Album and/or Memory) and, if using Album, load and select an album
5. Save — a sync starts immediately (large albums with hundreds/thousands of photos will take a few minutes on the first sync)
6. Tap the widget to cycle photos; tap the fullscreen icon to open the slideshow

## Known limitations

- Android widgets have no long-press gesture available to apps (it's reserved by the launcher for resize/remove) — settings are reached via an on-widget gear icon instead
- `PhotoWidgetProvider` must be `exported="true"` (Android requirement, since the system delivers widget-update broadcasts from outside the app's own process); a widget-refresh broadcast could theoretically be sent by another app, but this exposes no data and has no meaningful security impact
- Memory content is fully rebuilt once a day rather than kept in sync continuously, since Immich generates "On This Day" memories via a server-side nightly job — checking more often would not surface anything new
- No automatic retry for individual failed downloads mid-sync; a photo that fails to download will simply be retried on the next sync cycle

## Credits

- [Immich](https://immich.app) — the self-hosted photo/video backup solution this widget connects to
- [Free To Use](https://freetouse.com) — royalty-free background music API

## License

Add a license of your choice (e.g. MIT) before publishing — none is currently specified.
