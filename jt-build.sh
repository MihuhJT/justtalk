#!/bin/zsh
# Builds the JustTalk release APK and copies it to apk/JustTalk-<version>.apk.
# Version comes from android/app/build.gradle.kts (versionName, first word: "0.15 Beta" -> 0.15).
set -e
cd "$(dirname "$0")"
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"

VERSION=$(sed -n 's/.*versionName = "\([^" ]*\).*/\1/p' android/app/build.gradle.kts)
CODE=$(sed -n 's/.*versionCode = \([0-9]*\).*/\1/p' android/app/build.gradle.kts)
OUT="apk/JustTalk-$VERSION.apk"
if [[ -f "$OUT" && "$1" != "--force" ]]; then
  echo "$OUT already exists: bump versionName/versionCode in android/app/build.gradle.kts (or pass --force)"
  exit 1
fi
(cd android && ./gradlew --quiet assembleRelease)

cp android/app/build/outputs/apk/release/app-release.apk "$OUT"
echo "built $OUT (versionName $VERSION, versionCode $CODE)"
