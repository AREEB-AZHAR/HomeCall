import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:homecall/core/protocol.dart';

void main() {
  final a = 'a' * 64, b = 'b' * 64;
  test('TLS identity, recipient and trust must all match before ringing', () {
    final invite = envelope('CALL_INVITE', a, b, token(), {});
    final i = Inbox();
    expect(i.read(invite, b, b, true), isNull);
    expect(i.read(invite, a, 'c' * 64, true), isNull);
    expect(i.read(invite, a, b, false), isNull);
    expect(i.read(invite, a, b, true), isNotNull);
    expect(i.read(invite, a, b, true), isNull);
  });
  test('unknown peers may identify themselves for explicit pairing', () {
    final msg = envelope('HELLO', a, '*', '', {'name': 'Kitchen'});
    expect(Inbox().read(msg, a, b, false)?['kind'], 'HELLO');
  });
  test(
    'malformed, stale, wrong version and non-host candidates are discarded',
    () {
      final i = Inbox();
      for (final s in ['[]', 'null', '{', '{}', ' ' * 100000]) {
        expect(i.read(s, a, b, true), isNull);
      }
      final m = jsonDecode(envelope('CALL_END', a, b, token(), {}));
      for (final bad in [
        {'at': 0},
        {'v': 2},
        {'kind': 'FUTURE'},
        {'session': 'bad'},
        {'id': null},
      ]) {
        expect(i.read(jsonEncode({...m, ...bad}), a, b, true), isNull);
      }
      expect(
        i.read(
          envelope('ICE_CANDIDATE', a, b, token(), {
            'candidate': 'candidate typ relay',
            'sdpMLineIndex': 0,
          }),
          a,
          b,
          true,
        ),
        isNull,
      );
    },
  );
  test('simultaneous callers agree which invite survives', () {
    expect(yieldInvite(a, b, Phase.inviting, true, true), isFalse);
    expect(yieldInvite(b, a, Phase.ringing, true, true), isTrue);
    expect(yieldInvite(b, a, Phase.connected, true, true), isFalse);
    expect(yieldInvite(b, a, Phase.inviting, true, false), isFalse);
  });
}
