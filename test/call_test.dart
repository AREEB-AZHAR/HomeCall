import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:homecall/features/household.dart';
import 'package:homecall/core/protocol.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final a = 'a' * 64, b = 'b' * 64;
  late Household h;
  late Peer p;
  final sent = <Map<String, dynamic>>[];
  Future<void> receive(
    String kind,
    String session, {
    String? key,
    Map<String, dynamic> data = const {},
  }) => h.receive({
    'ip': p.ip,
    'id': key ?? b,
    'body': envelope(kind, key ?? b, a, session, data),
  });
  setUp(() {
    h = Household()..id = a;
    p = Peer(b, 'Bedroom', '192.168.1.2', paired: true);
    h.peers[b] = p;
    sent.clear();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(Household.channel, (call) async {
          if (call.method == 'send') {
            sent.add(jsonDecode(call.arguments['body']));
          }
          return null;
        });
  });
  tearDown(() {
    h.deadline?.cancel();
  });
  test('outgoing invitation rings and can be rejected', () async {
    await h.call(p);
    expect(h.phase, Phase.inviting);
    expect(sent.single['kind'], 'CALL_INVITE');
    await receive('CALL_RINGING', h.session);
    expect(h.phase, Phase.ringing);
    await receive('CALL_REJECT', h.session);
    expect(h.phase, Phase.rejected);
    expect(h.voice, isNull);
  });
  test('incoming call can be cancelled only by its peer and session', () async {
    final s = token();
    await receive('CALL_INVITE', s);
    expect(h.phase, Phase.ringing);
    await receive('CALL_CANCEL', token());
    await receive('CALL_CANCEL', s, key: 'c' * 64);
    expect(h.phase, Phase.ringing);
    await receive('CALL_CANCEL', s);
    expect(h.phase, Phase.cancelled);
  });
  test(
    'busy response preserves the current session and repeated rings are limited',
    () async {
      final s = token();
      await receive('CALL_INVITE', s);
      await receive('CALL_INVITE', token());
      expect(sent.last['data']['reason'], 'busy');
      expect(h.session, s);
      await h.hangup();
      await receive('CALL_INVITE', token());
      expect(h.phase, Phase.rejected);
    },
  );
  test('untrusted invitation cannot create a session', () async {
    p.paired = false;
    await receive('CALL_INVITE', token());
    expect(h.phase, Phase.idle);
    expect(sent, isEmpty);
  });
}
