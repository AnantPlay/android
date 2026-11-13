# Third-party software and data

AnantPlay is MIT licensed (see `LICENSE`). It includes, or downloads at build or run time, the following.

## Libraries in the app

| Component | License | Used for |
| --- | --- | --- |
| [AndroidX Media3](https://github.com/androidx/media) (ExoPlayer) 1.11.1 | Apache-2.0 | the native player (`libs/media3-*.jar`) |
| [AndroidX WebKit](https://developer.android.com/jetpack/androidx/releases/webkit) 1.12.1 | Apache-2.0 | WebView features (`libs/androidx-webkit-*.jar`) |
| [Guava](https://github.com/google/guava) (Android flavor) | Apache-2.0 | required by Media3 |
| [wireguard-go](https://git.zx2c4.com/wireguard-go) | MIT | the in-app WARP tunnel (`warp/`, built into `libwarp.so`) |
| [gVisor](https://github.com/google/gvisor) netstack | Apache-2.0 | userspace networking for the tunnel |
| [golang.org/x/crypto, x/net, x/sys, x/time](https://pkg.go.dev/golang.org/x) | BSD-3-Clause | the tunnel |

## Data fetched at build or run time (not stored in this repository)

| Source | What for |
| --- | --- |
| [HaGeZi DNS blocklists](https://github.com/hagezi/dns-blocklists) (GPL-3.0) | the ad and pop-up domain list (refreshed weekly) |
| [Cinemeta](https://v3-cinemeta.strem.io) | posters, titles and episode lists |
| OpenSubtitles through the [Stremio addon](https://opensubtitles-v3.strem.io) | subtitles in the native player |
| [Tenor](https://tenor.com)'s CDN (`media.tenor.com`) | three cat GIFs on the loading screen, fetched once and kept on the device (not bundled; they belong to their creators) |
| Cloudflare WARP | the optional tunnel; registered through Cloudflare's own endpoint at first start |

Sites, channel playlists and title links come only from the sources a user adds in *Settings, Sources*; the app carries none. The list AnantPlay offers by default is [AnantPlay/index](https://github.com/AnantPlay/index).

AnantPlay is not affiliated with or endorsed by Cloudflare, Tenor, Stremio, OpenSubtitles, the maintainers of any source, or any streaming site.

## Icons

| Component | License | Used for |
| --- | --- | --- |
| [Material Symbols](https://github.com/google/material-design-icons) by Google (Rounded, filled) | Apache-2.0 | the player icons (`res/drawable/ic_p_*.xml`, `ic_pb_*.xml`) |
