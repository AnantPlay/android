#!/bin/sh
# Downloads the ad blocklist the app starts with (it refreshes it itself later). It is not stored in the repository:
#   assets/blocklist.txt   HaGeZi's domain blocklists (https://github.com/hagezi/dns-blocklists)
# A file that already exists is kept; run with -f to download it again.
set -e
cd "$(dirname "$0")/.."
HAGEZI=https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard
if [ "$1" = -f ] || [ ! -s assets/blocklist.txt ]; then
  mkdir -p assets
  { curl -fsSL --retry 3 "$HAGEZI/pro.mini-onlydomains.txt"; curl -fsSL --retry 3 "$HAGEZI/popupads-onlydomains.txt"; } \
    | grep -v '^#' | grep -v '^$' | sort -u > assets/blocklist.txt.tmp
  mv assets/blocklist.txt.tmp assets/blocklist.txt && echo "fetched assets/blocklist.txt"
fi
