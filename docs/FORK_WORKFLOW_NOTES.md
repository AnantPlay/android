# Fork builds and releases

The **Build** workflow compiles both Phone and TV APKs on PRs and on `main`. It uses Temurin 17, Android platform 34, build-tools 35.0.0, and the Go version in `warp/go.mod`. The WARP build requires a runner Android NDK. CI verifies both APK signatures, compares the bundled font and license to their source files, and reports exact APK sizes in the run summary. Download the `apks` artifact from that run; its APKs use an ephemeral key and are not suitable for updates.

For signed GitHub Releases, create and back up a PKCS12 keystore with alias `weblock` (the local `build.sh` generates `release.jks` and `release.pass` if missing). In repository **Settings → Secrets and variables → Actions**, set `KEYSTORE_B64` to the single-line Base64 encoding of the keystore and `KEYSTORE_PASS` to its password. Keep these files out of Git and reuse the same key for all releases; Android will reject updates with a different signing key.

Push an increasing `vMAJOR.MINOR.PATCH` tag for the desired `main` commit. If both secrets are configured, the Release workflow publishes both APKs and checksums. Without either secret, it uploads test APKs only and skips GitHub Release publication; rerun the workflow after configuring the secrets.
