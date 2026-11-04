import 'dart:io';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:homecall/app.dart';
import 'package:homecall/features/household.dart';

void main() {
  testWidgets('render the actual onboarding screen for the README', (
    tester,
  ) async {
    final root = Platform.environment['FLUTTER_ROOT']!;
    final fonts = '$root/bin/cache/artifacts/material_fonts';
    for (final entry in {
      'Roboto': 'roboto-regular.ttf',
      'MaterialIcons': 'materialicons-regular.otf',
    }.entries) {
      final font = FontLoader(entry.key)
        ..addFont(
          Future.value(
            ByteData.sublistView(
              File('$fonts/${entry.value}').readAsBytesSync(),
            ),
          ),
        );
      await font.load();
    }
    tester.view.devicePixelRatio = 1;
    tester.view.physicalSize = const Size(400, 760);
    const boundary = Key('capture');
    await tester.pumpWidget(
      RepaintBoundary(
        key: boundary,
        child: HomeCall(Household()..ready = true),
      ),
    );
    await tester.pumpAndSettle();
    await expectLater(
      find.byKey(boundary),
      matchesGoldenFile('../docs/images/onboarding.png'),
    );
    await tester.pumpWidget(const SizedBox());
    tester.view.resetPhysicalSize();
    tester.view.resetDevicePixelRatio();
  });
}
