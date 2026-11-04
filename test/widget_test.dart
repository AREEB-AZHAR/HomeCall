import 'package:flutter_test/flutter_test.dart';
import 'package:homecall/app.dart';
import 'package:homecall/features/household.dart';
import 'package:flutter/material.dart';

void main() {
  testWidgets('onboarding explains local calling and requests a display name', (
    tester,
  ) async {
    final model = Household()..ready = true;
    await tester.pumpWidget(HomeCall(model));
    expect(find.text('Call across your Wi-Fi'), findsOneWidget);
    await tester.enterText(find.byType(TextField), 'Kitchen');
    expect(find.text('Kitchen'), findsOneWidget);
    await tester.pumpWidget(const SizedBox());
  });
  testWidgets(
    'untrusted device has pairing action and settings exposes identity',
    (tester) async {
      final model = Household()
        ..ready = true
        ..name = 'Kitchen'
        ..id = 'a' * 64;
      model.peers['b' * 64] = Peer('b' * 64, 'Bedroom', '192.168.1.2');
      await tester.pumpWidget(HomeCall(model));
      expect(find.text('Pair'), findsOneWidget);
      expect(find.byTooltip('Call Bedroom'), findsNothing);
      await tester.tap(find.byTooltip('Settings'));
      await tester.pumpAndSettle();
      expect(find.text('AAAA AAAA AAAA AAAA'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());
    },
  );
}
