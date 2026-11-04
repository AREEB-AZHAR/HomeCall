# LAN signaling v1

**Transport.** IPv4 TCP port 43871. Signaling binds to a directly connected private or link-local interface matching the peer subnet. Discovery probes use separate workers from call messages. Every request opens a TLS 1.2 connection with client and server certificates. Each application record starts with a four-byte unsigned big-endian length followed by 1–98,304 UTF-8 bytes. The request contains JSON. The reply is a PEER_INFO JSON envelope. A successful write and reply acknowledges transport only. A call transition requires a separate authenticated event in the opposite direction.

| Field | Meaning |
| --- | --- |
| v | Integer protocol version 1 |
| id | Random 128-bit lowercase hexadecimal message ID |
| kind | Event listed below |
| from, to | Full lowercase SHA-256 public-key digests. An initial HELLO may target * |
| session | Random 128-bit call ID shared until teardown. Empty for discovery |
| at | Unix milliseconds. Phones must agree to within two minutes |
| data | JSON object specific to the event |

| Event | Receiver behavior |
| --- | --- |
| HELLO | Validate the TLS identity and name, refresh the peer address. The reply supplies peer information |
| PEER_INFO | TLS response with the display name and the request message ID in data.replyTo. The sender validates both identities and correlates the reply |
| CALL_INVITE / CALL_RINGING | Admit one paired caller and acknowledge ringing |
| CALL_ACCEPT / CALL_REJECT | Start negotiation or terminate. A busy rejection carries reason `busy` |
| CALL_CANCEL / CALL_END | Withdraw the invitation or release a session |
| SDP_OFFER / SDP_ANSWER | Apply `data.sdp` only for the expected session, direction and negotiation stage |
| ICE_CANDIDATE | Submit a host candidate with `candidate`, `sdpMid` and `sdpMLineIndex` |
| HEARTBEAT / ERROR | Update liveness or close a failed session |

The lower lexical device digest keeps its outgoing invitation when two paired phones call each other simultaneously. Other callers receive busy. The invitation deadline is 30 seconds and negotiation gets 35 seconds. Unknown senders cannot ring. The inbox rejects unsupported versions, stale or duplicate IDs, wrong recipients and malformed payloads. It retains up to 1,024 sender/message combinations.

DNS-SD uses `_homecall._tcp.local.` and TXT `v=1`. Only successful TLS contact refreshes identity and presence. Client isolation can prevent both direct IP pairing and calls even when DNS-SD entries are visible.
