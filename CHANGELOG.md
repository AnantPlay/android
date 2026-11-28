# Changelog

Only the current version is kept here: each `vX.Y.Z` tag publishes the TV and Android APKs, and this section becomes the release notes.

## 2.25.0

### ✨ New
- **Subtitles line up with the speech by themselves, in seconds.** When you pick a subtitle file, AnantPlay fetches a few minutes of the video's sound ahead and matches the lines to the voices, also for a file made for another frame rate (one that drifts). It says when it's done; *Subtitles, Sync to audio* runs it again. This works for the site's own subtitle files too. (#35)
- **More subtitles.** TheSubtitleDB is searched together with OpenSubtitles, no key needed. With your own free key from SubDL or Subsource (*Settings, Player*), theirs join the list too. An episode's file is found inside a whole season's download, and machine-made files are marked AI.
- **More like this** in the player: titles of the same kind as the one playing. A movie's *More to watch* button opens it, and *More* has it for series and anime.
- **Ratings on the title page:** IMDb, Rotten Tomatoes and the age rating for your country, and icons for cast, director, country and awards.
- **Gujarati** on India's language chips (with its own New, Popular and full list), and Gujarati and Punjabi subtitles.
- **The TV asks before it plays a cast:** *Cast from Pixel 9a?* with Play, Always allow and Decline. A phone you didn't let in can't stop or seek your video either.

### 🐛 Fixed
- **Sites that moved to a new address** keep opening titles directly, and stay your pick (7Movies, Atlantic, ShuttleTV and others). Direct links for many more sites come with the title links source.
- The Sinhalese, Mandarin, Dutch and Catalan lists showed other films.
- The update download kept going but its progress disappeared after a few seconds; it stays on screen now.
- IPTV: picking a category or language keeps the remote on that chip instead of jumping to the search box.
- DLNA: a TV that refuses the video says why, so a report can tell what to fix. (#24)
