# JustTalk iOS 0.16.2 Beta

Native SwiftUI application for iPhone/iPad, iOS 18+. Uses the existing JustTalk server API; Android and Mac sources are unchanged.

Open `JustTalk.xcodeproj` in Xcode, select your own development team and the connected iPhone, then Run. The iPhone must have Developer Mode enabled and be unlocked. Apple may require trusting the development certificate on the device. A Personal Team installation has a limited provisioning lifetime and may need rebuilding through Xcode.

Implemented: existing-account login and invitation registration, live chats and history, replies, editing, reactions, forwarding, search, profiles/groups, photo library and file attachments, voice messages, video circles, voice calls, polls/gatherings/splits, shared video playback with floating overlay, spots with center targeting, JT bot chat/settings and bot responses/cards. Uses the existing dark palette, Onest font and JT icon.

Foreground WebSocket notifications and local notification delivery are supported. Remote push delivery while iOS suspends or terminates the app requires APNs integration and is not implemented in this client-only port. Calls have background audio support; incoming calls when the app is suspended require separate PushKit/CallKit integration. The app does not claim Android feature parity until device testing is complete.

## Build and checks

`./build.sh` builds a Release app for iOS using the configured signing team.

Protocol fixture checks, without network or user-account changes:

```sh
xcrun swiftc -parse-as-library Net.swift Tests/ProtocolChecks.swift -o /tmp/jt-ios-protocol-checks
/tmp/jt-ios-protocol-checks
```

`JT_SERVER` can override the server for a test environment. Tokens are scoped to the server in the app's own sandbox. Production accounts and server state must not be used for automated mutation tests without authorization.
