# Security and privacy

HomeCall generates an EC P-256 signing key under a private Android Keystore alias. The SHA-256 digest of its encoded public key identifies the installation. Hardware backing depends on the device. SQLite stores the local display name, peer names, public key pins and last reachable IPs. Android backup is disabled.

**Pairing ceremony.** On A, tap Pair beside B. On B, open Settings. Compare all four groups of the 16-hex-character verification code. Confirm on A only when they match. Repeat with the roles reversed. Each phone stores the full 256-bit digest of the identity key verified during the TLS connection. Tap Forget in Settings to revoke a peer. After reinstalling, remove obsolete pins and pair again.

TLS allows self-issued certificates at the handshake layer so first contact can work offline. The TLS stack verifies possession of a separate Keystore key authorized to sign TLS digests. The persistent identity signs a domain-separated binding to that TLS public key. Both sides check the binding before exchanging signaling. Existing identity codes and pairing pins survive an in-place update. The caller verifies the expected public key before writing paired signaling, and the receiver compares the verified key with both the sender field and its own trust database. A name, service advertisement or IP address alone cannot grant call access. Compare codes directly on the two phones or through a trusted independent channel.

WebRTC negotiates DTLS-SRTP media using the fingerprints carried in authenticated SDP. No external ICE server, analytics, account service or call recording is configured. Local devices can still observe service advertisements, timing, traffic size and IP addresses. First-contact probes disclose the display name.

Limits include four concurrent inbound workers, capped frames, socket deadlines, bounded discovery and replay caches, explicit per-peer ringing cooldown and a single active call. A hostile LAN can still exhaust resources or block packets. The implementation has not received an independent security audit.
