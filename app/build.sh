#!/bin/sh
# Builds the Mac app for download and in-app updates:
#   dist/Folio.app              the app (launcher + capture helper + engine code + web UI)
#   dist/Folio-mac.dmg          for the download page (drag to Applications)
#   dist/Folio-mac-<v>.zip      what the in-app updater downloads
#   dist/mac.json               the update feed (version, zip URL, SHA-256)
# Python, packages and models are not inside: the app installs them on first launch (app/setup.sh).
# Signed with Folio's own certificate (app/signing/, not in git; back it up), so updates keep the
# microphone / system-audio permissions and the updater can tell a genuine update from a fake one.
set -e
cd "$(dirname "$0")/.."
V=$(cat app/VERSION)
NOTES=${NOTES:-}
APP=dist/Folio.app
SIGN=app/signing
rm -rf "$APP" dist/Folio-mac*.zip dist/Folio-mac.dmg
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/engine"

swiftc -O -o "$APP/Contents/MacOS/Folio" app/launcher/main.swift
swiftc -O -o "$APP/Contents/MacOS/folio-capture" capture/main.swift \
  -Xlinker -sectcreate -Xlinker __TEXT -Xlinker __info_plist -Xlinker capture/Info.plist

R="$APP/Contents/Resources"
rsync -a --exclude __pycache__ folio "$R/engine/"
rsync -a --exclude dist web "$R/engine/"
cp app/setup.sh app/requirements.lock "$R/"
cat app/requirements.lock app/setup.sh | shasum -a 256 | cut -d' ' -f1 | tr -d '\n' > "$R/setup-stamp"

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>Folio</string>
  <key>CFBundleIdentifier</key><string>com.qshlab.folio</string>
  <key>CFBundleName</key><string>Folio</string>
  <key>CFBundleDisplayName</key><string>Folio</string>
  <key>CFBundleIconFile</key><string>Folio</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$V</string>
  <key>CFBundleVersion</key><string>$V</string>
  <key>LSMinimumSystemVersion</key><string>14.4</string>
  <key>LSArchitecturePriority</key><array><string>arm64</string></array>
  <key>LSUIElement</key><true/>
  <key>NSHumanReadableCopyright</key><string>© 2026 qshlab</string>
  <key>CFBundleURLTypes</key><array><dict>
    <key>CFBundleURLName</key><string>Folio</string>
    <key>CFBundleURLSchemes</key><array><string>folio</string></array>
  </dict></array>
  <key>NSMicrophoneUsageDescription</key><string>Folio records your meetings and lectures to transcribe them on this Mac.</string>
  <key>NSAudioCaptureUsageDescription</key><string>Folio records the other side of online meetings to transcribe them on this Mac.</string>
</dict></plist>
PLIST

# Icon from web/icon.svg
TMP=$(mktemp -d)
qlmanage -t -s 1024 -o "$TMP" web/icon.svg >/dev/null 2>&1 || true
if [ -f "$TMP/icon.svg.png" ]; then
  mkdir "$TMP/Folio.iconset"
  for s in 16 32 128 256 512; do
    sips -z $s $s "$TMP/icon.svg.png" --out "$TMP/Folio.iconset/icon_${s}x${s}.png" >/dev/null
    sips -z $((s*2)) $((s*2)) "$TMP/icon.svg.png" --out "$TMP/Folio.iconset/icon_${s}x${s}@2x.png" >/dev/null
  done
  iconutil -c icns "$TMP/Folio.iconset" -o "$R/Folio.icns"
fi
rm -rf "$TMP"

# Sign. codesign only finds identities in the keychain search list, so add Folio's keychain for the
# duration of the build and put the list back afterwards.
. "$SIGN/signing.env"
KC="$(pwd)/$SIGN/folio-signing.keychain-db"
OLD_LIST=$(security list-keychains -d user | tr -d '"' | xargs)
trap 'security list-keychains -d user -s $OLD_LIST' EXIT
security list-keychains -d user -s $OLD_LIST "$KC"
security unlock-keychain -p "$FOLIO_KEYCHAIN_PASSWORD" "$KC"
ID=$(security find-identity -p codesigning "$KC" | awk '/Folio \(qshlab\)/ {print $2; exit}')
codesign --force --sign "$ID" --identifier com.qshlab.folio.capture "$APP/Contents/MacOS/folio-capture"
codesign --force --sign "$ID" "$APP"
codesign --verify --deep --strict "$APP"

# Package
ZIP="Folio-mac-$V.zip"
ditto -c -k --keepParent "$APP" "dist/$ZIP"
DMG_DIR=$(mktemp -d)
cp -R "$APP" "$DMG_DIR/"
ln -s /Applications "$DMG_DIR/Applications"
hdiutil create -quiet -volname Folio -srcfolder "$DMG_DIR" -fs HFS+ -format UDZO "dist/Folio-mac.dmg"
rm -rf "$DMG_DIR"
cat > dist/mac.json <<JSON
{
  "version": "$V",
  "url": "${FOLIO_DOWNLOAD_BASE:-https://folio.qshlab.com}/$ZIP",
  "sha256": "$(shasum -a 256 "dist/$ZIP" | cut -d' ' -f1)",
  "size": $(stat -f %z "dist/$ZIP"),
  "notes": "$NOTES"
}
JSON
# Only the packages are needed; drop the built app so macOS doesn't send folio:// links to it.
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -u "$APP" 2>/dev/null || true
rm -rf "$APP"
ls -lh dist/Folio-mac* dist/mac.json | awk '{print $5, $9}'
