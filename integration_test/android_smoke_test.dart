import 'package:flutter_test/flutter_test.dart';
import 'package:integration_test/integration_test.dart';
import 'package:homecall/features/household.dart';
import 'package:homecall/core/protocol.dart';
import 'package:homecall/features/media.dart';

void main() {
  IntegrationTestWidgetsFlutterBinding.ensureInitialized();
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
