# Known limitations

- Two-phone audio, OEM Telecom admission, lock-screen answer, Bluetooth and wired routes, and hotspot host participation require hardware verification. A compiled APK and passing Dart tests do not establish these behaviors.
- Both devices need reachable private or link-local IPv4 addresses. IPv6-only Wi-Fi, guest networks, hotspot client isolation and blocked peer-to-peer traffic can defeat discovery or calls. Host-only ICE has no relay fallback.
- Manual IP and remembered endpoints are the discovery fallbacks. UDP broadcast discovery is optional and currently omitted. Polling can take one or more intervals to reflect a network change.
- The notification channel controls ringtone sound. Device policy can suppress it. Full-screen takeover, forced unlock, unattended boot start and guaranteed delivery after force-stop are unsupported.
- A process kill tears down in-memory call state. Retry the call after rediscovery. Calls do not migrate between networks.
- The build targets Android phones with Telecom and microphone support. Release signing, Play distribution and physical-device acceptance are separate steps.
