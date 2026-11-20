# Sinhala patch specification (v2.23.1 base)

- **Addon lookup:** `Subs.search` requests `/subtitles/movie/{imdb}.json` or `/subtitles/series/{imdb}:{season}:{episode}.json` first when a base URL is configured. It preserves the provider’s order, normalizes `si` and `sin` to `sin`, resolves relative download links, and falls back to legacy OpenSubtitles REST when there is no usable match. Auto subtitles continues to prefer remembered choices and matching embedded tracks, then takes the first search result.
- **Download:** `SubFetch` defaults Sinhala to UTF-8 and uses the actual HTTP status, not body text, to detect 202. `PlayerActivity` shows a translation notice and schedules background retries after 10 seconds and then 25 seconds. No RAR dependency is added.
- **Rendering and sync:** `PlayerActivity` caches the bundled Noto Sans Sinhala typeface and falls back to the system font if it cannot be loaded. The existing width cap and `SubSync` remain in place. The player has ±100 ms and ±250 ms adjustments.
- **Updates:** `Update` checks only this fork's GitHub Releases and rejects cached APK links from other repositories. Android enforces signing continuity.
