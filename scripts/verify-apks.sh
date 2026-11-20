#!/usr/bin/env bash
set -euo pipefail
for flavor in Android TV; do
  apks=(AnantPlay-"$flavor"-v*.apk)
  [[ ${#apks[@]} == 1 && -f ${apks[0]} ]]
  apk=${apks[0]}
  "$ANDROID_HOME/build-tools/${BUILD_TOOLS_VERSION:-35.0.0}/apksigner" verify "$apk"
  cmp assets/NotoSansSinhala-Regular.ttf <(unzip -p "$apk" assets/NotoSansSinhala-Regular.ttf)
  cmp assets/NotoSansSinhala-OFL.txt <(unzip -p "$apk" assets/NotoSansSinhala-OFL.txt)
  echo "$apk: $(wc -c < "$apk") bytes" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
done
