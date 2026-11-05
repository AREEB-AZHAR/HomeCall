# Verification

## Development host

Windows 11, Flutter 3.41.3, Dart 3.11.1 and Android SDK. The debug APK compiled. Static analysis passed. Ten automated tests passed across protocol validation, simultaneous-call arbitration, incoming and outgoing transitions, cancellation, busy and spam handling, setup and pairing UI. A separate widget capture rendered the onboarding image. These checks exercise the production Dart code with a mocked platform channel in call-controller tests.

Run from the repository root:

```sh
flutter pub get
flutter analyze
flutter test --reporter expanded
flutter test --update-goldens tool/capture_ui_test.dart
flutter build apk --debug
```

The Android-only smoke suite connects to the phone's own Wi-Fi listener to exercise both Keystore signatures and the TLS identity proof. It expects the deliberate self-peer rejection after the handshake. A second check verifies persistent identity and creates a real microphone stream, peer connection and audio-only Opus offer. Join an IPv4 Wi-Fi network and grant microphone access before running the suite. This device check is pending.

```sh
flutter test integration_test/android_smoke_test.dart -d DEVICE_SERIAL
```

The native JVM transport regression creates two temporary identities, exchanges HELLO and PEER_INFO over real TLS sockets on a local IPv4 interface, reconnects using the discovered pin and rejects a mismatched pin before sending the message. Run `gradlew.bat :app:testDebugUnitTest --tests dev.homecall.homecall.LanTransportTest` from the Android directory with JAVA_HOME pointing to JDK 17 or 21. The test requires a local IPv4 interface and reports a skip when none exists.

Version 1.0.2 (3) targets API 36 with minimum API 26. Its release and debug build commands are in the README.

The notification regression runs the actual HomeCallService under Robolectric on API 26, 31 and 35. All six checks passed. They verify that ringing is the foreground notification, answering replaces it with an ongoing call, and returning to availability clears the incoming actions. Run `gradlew.bat :app:testDebugUnitTest --tests dev.homecall.homecall.HomeCallServiceTest` from the Android directory. Repeat the incoming call on the affected physical phone to confirm its notification behavior.

## Two-phone acceptance record

Use the same APK on both phones. Record both models, Android versions, network type, caller, callee, OS permissions, battery settings, expected result and observed result. **All hardware rows below are pending.**

| Setup or action | Evidence to collect |
| --- | --- |
| A and B on home Wi-Fi with internet | Discover, verify both codes, call in both directions. Speak a different phrase on each phone |
| Disconnect router WAN, disable mobile data | Repeat bidirectional audio, record call duration and that both phrases were heard |
| Hotspot with mobile data off | Try host to client, client to host and client to client. Repeat via direct IP if multicast fails |
| Accept, decline, cancel before answer | Observe both screens and notification teardown. Try a second call immediately after a successful call |
| Receiver at home screen, then backgrounded, then locked | Check ringtone, actions, lock/unlock requirements and return to active call |
| Mute, speaker, wired and Bluetooth | Verify mute from the far end and listen for actual route changes. Remove the accessory mid-call |
| Hang up on each side | Both sessions end and Android's microphone indicator clears |
| Disable Wi-Fi during a call | Session ends within the connection timeout. Reconnect, change DHCP address, refresh and call again |
| Call simultaneously or from an unpaired third phone | One session survives glare. The unknown phone cannot cause ringing |
| Force-stop, call, then reopen | No delivery is expected while stopped. Availability resumes after reopening |
| Cellular call or other audio interruption | Verify Telecom admission or disconnection and that subsequent calls can obtain audio focus |
| Repeated malformed/duplicate frames | Run protocol tests, then exercise under load on an isolated test LAN before release |

For device screenshots, capture the paired-device list on A, the incoming notification on locked B and the connected call controls after both sides can hear audio. Mask device names or addresses before publishing.
