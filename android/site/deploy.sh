#!/usr/bin/env bash
# Publishes the download page, the latest release APK and the latest Mac app to https://folio.qshlab.com,
# where the apps check for updates (version.json for Android, mac.json for the Mac).
#   cd android && ./gradlew assembleRelease && site/deploy.sh
#   sh app/build.sh && android/site/deploy.sh          (Mac; uses whatever is in folio/dist)
# Needs a one-time `npx wrangler@3 login` on this Mac (already done for wick.qshlab.com).
set -euo pipefail
cd "$(dirname "$0")"
# Keep public/ flat: wrangler 3 on Node 18.15 skips subfolders (no recursive readdir).
if find public -mindepth 2 -type f | grep -q .; then echo "public/ must not contain subfolders" >&2; exit 1; fi
# A private npm cache: ~/.npm on this Mac contains root-owned files that make npx fail.
export npm_config_cache="${npm_config_cache:-$PWD/.npm-cache}"

APK=../app/build/outputs/apk/release/app-release.apk
VERSION=$(sed -nE 's/.*versionName = "([^"]+)".*/\1/p' ../app/build.gradle.kts | head -1)
VERSION_CODE=$(sed -nE 's/.*versionCode = ([0-9]+).*/\1/p' ../app/build.gradle.kts | head -1)
FILE="Folio-$VERSION.apk"
SIZE=$(stat -f%z "$APK" 2>/dev/null || stat -c%s "$APK")
# Cloudflare static assets are limited to 25 MiB per file.
if [ "$SIZE" -gt 26214400 ]; then echo "APK is $SIZE bytes, over the 25 MiB asset limit" >&2; exit 1; fi
SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)

rm -f public/Folio-*.apk
cp "$APK" "public/$FILE"
python3 - "$VERSION" "$VERSION_CODE" "$FILE" "$SIZE" "$SHA" > public/version.json <<'PY'
import json, os, sys
version, code, file, size, sha = sys.argv[1:6]
notes = open("notes.txt", encoding="utf-8").read().strip() if os.path.exists("notes.txt") else ""
print(json.dumps({"version": version, "versionCode": int(code), "file": file, "size": int(size), "sha256": sha,
                  "notes": notes}, ensure_ascii=False))
PY

# Mac app, built by folio/app/build.sh (only the newest update zip is kept)
MAC=../../dist
if [ -f "$MAC/mac.json" ]; then
  rm -f public/Folio-mac*
  cp "$MAC/Folio-mac.dmg" "$MAC"/Folio-mac-*.zip "$MAC/mac.json" public/
  for f in public/Folio-mac*; do
    if [ "$(stat -f%z "$f")" -gt 26214400 ]; then echo "$f is over the 25 MiB asset limit" >&2; exit 1; fi
  done
fi

{ echo "/download /$FILE 302"; echo "/mac /Folio-mac.dmg 302"; } > public/_redirects

npx --yes wrangler@3 deploy
echo "Published $FILE ($SIZE bytes) to https://folio.qshlab.com"
