#!/bin/zsh
# Builds "JT Server.app" next to this script.
set -e
cd "$(dirname "$0")"
APP="JT Server.app"
export DEVELOPER_DIR=/Library/Developer/CommandLineTools
rm -rf "$APP" icon.iconset
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" icon.iconset
# without -target the minimum macOS becomes the SDK's and the app won't start
swiftc -O -parse-as-library -target arm64-apple-macos14 main.swift -o "$APP/Contents/MacOS/JTServer"
swiftc -target arm64-apple-macos14 icon.swift -o /tmp/jt-icon-gen && /tmp/jt-icon-gen icon.png
for s in 16 32 128 256 512; do
  sips -z $s $s icon.png --out icon.iconset/icon_${s}x${s}.png >/dev/null
  sips -z $((s*2)) $((s*2)) icon.png --out icon.iconset/icon_${s}x${s}@2x.png >/dev/null
done
iconutil -c icns icon.iconset -o "$APP/Contents/Resources/AppIcon.icns"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>JT Server</string>
  <key>CFBundleIdentifier</key><string>com.fayor.jtserver</string>
  <key>CFBundleExecutable</key><string>JTServer</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>1.0</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
rm -rf icon.iconset
codesign --force -s - "$APP"
echo "built $APP"
