#!/bin/sh
# Builds dist/Folio.app: a launcher that starts the engine (if needed) and opens the UI in the browser.
# Launching through the app makes macOS ask for microphone / system-audio access in Folio's name.
set -e
cd "$(dirname "$0")/.."
ROOT="$(pwd)"
APP=dist/Folio.app
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
sh capture/build.sh >/dev/null

cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleExecutable</key><string>Folio</string>
  <key>CFBundleIdentifier</key><string>com.qshlab.folio</string>
  <key>CFBundleName</key><string>Folio</string>
  <key>CFBundleIconFile</key><string>Folio</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.1</string>
  <key>LSMinimumSystemVersion</key><string>14.4</string>
  <key>NSMicrophoneUsageDescription</key><string>Folio records your meetings and lectures to transcribe them on this Mac.</string>
  <key>NSAudioCaptureUsageDescription</key><string>Folio records the other side of online meetings to transcribe them on this Mac.</string>
</dict></plist>
PLIST

cat > "$APP/Contents/MacOS/Folio" <<SH
#!/bin/sh
PORT=\${FOLIO_PORT:-8765}
URL="http://127.0.0.1:\$PORT/"
if ! curl -s -o /dev/null "\${URL}api/projects"; then
  cd "$ROOT" && nohup .venv/bin/python -m folio >> "\$HOME/Library/Logs/Folio.log" 2>&1 &
  i=0
  while [ \$i -lt 100 ] && ! curl -s -o /dev/null "\${URL}api/projects"; do sleep 0.2; i=\$((i+1)); done
fi
open "\$URL"
SH
chmod +x "$APP/Contents/MacOS/Folio"

# Icon from web/icon.svg
TMP=$(mktemp -d)
qlmanage -t -s 1024 -o "$TMP" web/icon.svg >/dev/null 2>&1 || true
if [ -f "$TMP/icon.svg.png" ]; then
  mkdir "$TMP/Folio.iconset"
  for s in 16 32 128 256 512; do
    sips -z $s $s "$TMP/icon.svg.png" --out "$TMP/Folio.iconset/icon_${s}x${s}.png" >/dev/null
    sips -z $((s*2)) $((s*2)) "$TMP/icon.svg.png" --out "$TMP/Folio.iconset/icon_${s}x${s}@2x.png" >/dev/null
  done
  iconutil -c icns "$TMP/Folio.iconset" -o "$APP/Contents/Resources/Folio.icns"
fi
rm -rf "$TMP"
codesign --force --deep --sign - "$APP" >/dev/null 2>&1
echo "built $APP"
