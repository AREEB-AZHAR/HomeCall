import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:homecall/features/household.dart';
import 'package:homecall/core/protocol.dart';
import 'package:homecall/features/media.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
  testWidgets(
    'Android Wi-Fi listener completes mutual TLS before rejecting a self-call',
    (tester) async {
      final channel = Household.channel;
      final id = (await channel.invokeMethod<String>('identity'))!;
      final ips = await channel.invokeListMethod<String>('addresses') ?? [];
      expect(ips, isNotEmpty, reason: 'Join a local IPv4 Wi-Fi network first.');
      await channel.invokeMethod('start', {'name': 'HomeCall transport test'});
      // Self-probes are intentionally rejected only after both Keystore signatures
      // and the remote identity binding have verified on the live socket.
      await expectLater(
        channel.invokeMethod('send', {
          'ip': ips.first,
          'probe': true,
          'body': envelope('HELLO', id, '*', '', {
            'name': 'HomeCall transport test',
          }),
        }),
        throwsA(
          isA<PlatformException>().having(
            (e) => e.message,
            'reason',
            'Device identity mismatch',
          ),
        ),
      );
    },
  );
  testWidgets('Keystore identity is stable and native audio offers Opus', (
    tester,
  ) async {
    final first = await Household.channel.invokeMethod<String>('identity');
    expect(first, matches(keyPattern));
    expect(await Household.channel.invokeMethod<String>('identity'), first);
    final voice = Voice();
    try {
      await voice.open((_) {}, (_) {});
      final offer = await voice.describe(true);
      expect(offer, contains('m=audio'));
      expect(offer.toLowerCase(), contains('opus/48000'));
      expect(offer, isNot(contains('m=video')));
    } finally {
      await voice.close();
    }
  });
}
