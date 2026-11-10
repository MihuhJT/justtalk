#!/bin/zsh
set -eu
cd "$(dirname "$0")"
# Uses the existing development team selected in Xcode.
xcodebuild -project JustTalk.xcodeproj -scheme JustTalk -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath build "$@" build
