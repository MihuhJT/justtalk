#!/bin/zsh
# Builds "JustTalk.app" next to this script.
set -e
cd "$(dirname "$0")"
APP="JustTalk.app"
rm -rf "$APP" icon.iconset
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources" icon.iconset
# without -target the minimum macOS becomes the SDK's and the app won't start on older systems
# universal: Apple Silicon + Intel Macs
SRC=($(ls *.swift | grep -v "^icon.swift$"))
for arch in arm64 x86_64; do
  swiftc -O -parse-as-library -swift-version 5 -target $arch-apple-macos14 $SRC -o /tmp/jt-mac-$arch
done
lipo -create /tmp/jt-mac-arm64 /tmp/jt-mac-x86_64 -output "$APP/Contents/MacOS/JustTalk"
swiftc -target arm64-apple-macos14 icon.swift -o /tmp/jt-mac-icon-gen && /tmp/jt-mac-icon-gen icon.png
for s in 16 32 128 256 512; do
  sips -z $s $s icon.png --out icon.iconset/icon_${s}x${s}.png >/dev/null
  sips -z $((s*2)) $((s*2)) icon.png --out icon.iconset/icon_${s}x${s}@2x.png >/dev/null
done
iconutil -c icns icon.iconset -o "$APP/Contents/Resources/AppIcon.icns"
cp ../android/app/src/main/res/font/onest.ttf "$APP/Contents/Resources/Onest.ttf"
cat > "$APP/Contents/Info.plist" <<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
  <key>CFBundleName</key><string>JustTalk</string>
  <key>CFBundleDisplayName</key><string>JustTalk</string>
  <key>CFBundleIdentifier</key><string>com.fayor.justtalk.mac</string>
  <key>CFBundleExecutable</key><string>JustTalk</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>0.15</string>
  <key>CFBundleVersion</key><string>1</string>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSApplicationCategoryType</key><string>public.app-category.social-networking</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSMicrophoneUsageDescription</key><string>Для звонков, голосовых и кружков</string>
  <key>NSCameraUsageDescription</key><string>Для кружков</string>
  <key>NSAppTransportSecurity</key><dict><key>NSAllowsLocalNetworking</key><true/></dict>
</dict></plist>
PLIST
rm -rf icon.iconset
codesign --force -s - "$APP"
echo "built $APP"
