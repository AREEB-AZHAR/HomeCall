import 'dart:convert';
import 'dart:math';

final _random = Random.secure();
String token() => List.generate(
  16,
  (_) => _random.nextInt(256).toRadixString(16).padLeft(2, '0'),
).join();
const events = {
  'HELLO',
  'PEER_INFO',
  'CALL_INVITE',
  'CALL_RINGING',
  'CALL_ACCEPT',
  'CALL_REJECT',
  'CALL_CANCEL',
  'SDP_OFFER',
  'SDP_ANSWER',
  'ICE_CANDIDATE',
  'CALL_END',
  'HEARTBEAT',
  'ERROR',
};
final keyPattern = RegExp(r'^[0-9a-f]{64}$');
final tokenPattern = RegExp(r'^[0-9a-f]{32}$');
String envelope(
  String kind,
  String from,
  String to,
  String session,
  Map<String, dynamic> data,
) => jsonEncode({
  'v': 1,
  'id': token(),
  'kind': kind,
  'from': from,
  'to': to,
  'session': session,
  'at': DateTime.now().millisecondsSinceEpoch,
  'data': data,
});

class Inbox {
  final _seen = <String>{};
  Map<String, dynamic>? read(
    String body,
    String tlsId,
    String own,
    bool paired,
  ) {
    if (body.length > 98304 || !keyPattern.hasMatch(tlsId) || tlsId == own) {
      return null;
    }
    try {
      final m = jsonDecode(body);
      if (m is! Map<String, dynamic> ||
          m['v'] != 1 ||
          m['from'] != tlsId ||
          !events.contains(m['kind']) ||
          !(m['id'] is String && tokenPattern.hasMatch(m['id'])) ||
          m['at'] is! int ||
          (DateTime.now().millisecondsSinceEpoch - (m['at'] as int)).abs() >
              120000 ||
          m['data'] is! Map<String, dynamic>) {
        return null;
      }
      final hello = m['kind'] == 'HELLO';
      if (m['to'] != own && !(hello && m['to'] == '*')) return null;
      if (!hello &&
          (!paired ||
              m['session'] is! String ||
              !tokenPattern.hasMatch(m['session']))) {
        return null;
      }
      final d = m['data'];
      if (hello &&
          (d['name'] is! String ||
              d['name'].isEmpty ||
              d['name'].length > 40)) {
        return null;
      }
      if ((m['kind'] == 'SDP_OFFER' || m['kind'] == 'SDP_ANSWER') &&
          (d['sdp'] is! String || !d['sdp'].startsWith('v=0'))) {
        return null;
      }
      if (m['kind'] == 'ICE_CANDIDATE' &&
          (d['candidate'] is! String ||
              !d['candidate'].contains(' typ host') ||
              d['candidate'].length > 2048 ||
              (d['sdpMid'] != null && d['sdpMid'] is! String) ||
              d['sdpMLineIndex'] is! int)) {
        return null;
      }
      if (!_seen.add(tlsId + m['id'])) return null;
      if (_seen.length > 1024) _seen.remove(_seen.first);
      return m;
    } on FormatException {
      return null;
    }
  }
}

enum Phase {
  idle,
  inviting,
  ringing,
  connecting,
  connected,
  ended,
  rejected,
  busy,
  failed,
  cancelled,
  timedOut,
}

extension CallPhase on Phase {
  bool get live => [
    Phase.inviting,
    Phase.ringing,
    Phase.connecting,
    Phase.connected,
  ].contains(this);
}

bool yieldInvite(
  String own,
  String other,
  Phase phase,
  bool outgoing,
  bool samePeer,
) =>
    samePeer &&
    outgoing &&
    (phase == Phase.inviting || phase == Phase.ringing) &&
    own.compareTo(other) > 0;
