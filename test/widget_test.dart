import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:meshnet_app/main.dart';

void main() {
  testWidgets('MeshNet app loads (smoke test)', (WidgetTester tester) async {
    await tester.pumpWidget(
      const ProviderScope(
        child: MeshNetApp(),
      ),
    );
    await tester.pump(const Duration(seconds: 3));

    expect(find.text('MeshNet'), findsWidgets);
    expect(find.text('OFFLINE MESH NETWORK'), findsOneWidget);
  });
}