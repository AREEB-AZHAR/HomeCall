# Architecture

Each phone runs the same components. Calling requires a reachable LAN interface, an Android Telecom implementation and a mutually verified key on the two phones.

| Component | Responsibility |
| --- | --- |
| `lib/app.dart` | Material 3 setup, household list, verification, incoming and active calls, settings |
| `lib/features/household.dart` | SQLite profile and pins, peer expiry, serialized inbound signaling and session transitions |
| `lib/features/media.dart` | One WebRTC connection and microphone stream per call, Opus preference, ICE buffering and teardown |
| `lib/core/protocol.dart` | Envelope validation, replay window and simultaneous-call arbitration |
| `HomeCallApp` | Application-scoped Flutter engine and native method channel shared by the activity and service |
| `LanTransport` | Android Keystore identity, mutually authenticated TLS transport, bounded socket readers |
| `Discovery` | Android DNS-SD registration, discovery and sequential resolution |
| `CallIntegration` | Self-managed Telecom account, admission, audio route and disconnect callbacks |
| `HomeCallService` | Foreground availability, notification channels, incoming-call actions and listener teardown |

### From discovery to audio

1. NSD advertises the TCP port and protocol version. Resolving an entry triggers a TLS HELLO. Manual IP entry runs the same probe.
2. Each user compares the remote identity code with the code on the other phone. SQLite stores the full public-key digest and last address.
3. The caller requests Telecom admission and sends a pinned invitation. The receiving phone checks pairing, rate limits and current session before registering an incoming Telecom connection.
4. On acceptance the receiver opens audio and signals acceptance. The caller creates an offer and the receiver returns an answer. Each side queues early host ICE candidates until the remote description exists.
5. The connected timer starts on the WebRTC connection callback. Hang-up, failure and timeout close the microphone, peer connection and Telecom connection and restore availability.

Discovery retries known peers every 15 seconds. A missed peer becomes offline after 55 seconds. A connected session fails after missing heartbeats for more than 40 seconds at the next poll. Android can delay timers while idle.
