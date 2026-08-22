import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:meshnet_app/theme/app_theme.dart';

void main() {
  group('MeshAppTheme color constants', () {
    test('primary color is defined', () {
      expect(MeshAppTheme.primary, isNotNull);
      expect(MeshAppTheme.primary.toARGB32(), 0xFF4E8CFF);
    });

    test('success color is defined', () {
      expect(MeshAppTheme.success.toARGB32(), 0xFF3DDC84);
    });

    test('warning color is defined', () {
      expect(MeshAppTheme.warning.toARGB32(), 0xFFFFB74D);
    });

    test('error color is defined', () {
      expect(MeshAppTheme.error.toARGB32(), 0xFFFF5252);
    });

    test('info color is defined', () {
      expect(MeshAppTheme.info.toARGB32(), 0xFF7C8AFF);
    });

    test('bgDeep color is defined', () {
      expect(MeshAppTheme.bgDeep.toARGB32(), 0xFF0A0E1A);
    });

    test('bgSurface color is defined', () {
      expect(MeshAppTheme.bgSurface.toARGB32(), 0xFF111827);
    });

    test('bgCard color is defined', () {
      expect(MeshAppTheme.bgCard.toARGB32(), 0xFF1A2338);
    });

    test('bgElevated color is defined', () {
      expect(MeshAppTheme.bgElevated.toARGB32(), 0xFF212D45);
    });

    test('bgInput color is defined', () {
      expect(MeshAppTheme.bgInput.toARGB32(), 0xFF0D1220);
    });

    test('bgNav color is defined', () {
      expect(MeshAppTheme.bgNav.toARGB32(), 0xFF0E1322);
    });

    test('textWhite color is defined', () {
      expect(MeshAppTheme.textWhite.toARGB32(), 0xFFF0F4F8);
    });

    test('textGray color is defined', () {
      expect(MeshAppTheme.textGray.toARGB32(), 0xFF8899B0);
    });

    test('textDim color is defined', () {
      expect(MeshAppTheme.textDim.toARGB32(), 0xFF556680);
    });

    test('border color is defined', () {
      expect(MeshAppTheme.border.toARGB32(), 0xFF222E44);
    });

    test('borderLight color is defined', () {
      expect(MeshAppTheme.borderLight.toARGB32(), 0xFF2A3655);
    });

    test('sentBubble color is defined', () {
      expect(MeshAppTheme.sentBubble.toARGB32(), 0xFF4E8CFF);
    });

    test('receivedBubble color is defined', () {
      expect(MeshAppTheme.receivedBubble.toARGB32(), 0xFF1A2338);
    });

    test('primary and sentBubble are the same color', () {
      expect(MeshAppTheme.primary, MeshAppTheme.sentBubble);
    });

    test('bgCard and receivedBubble are the same color', () {
      expect(MeshAppTheme.bgCard, MeshAppTheme.receivedBubble);
    });
  });

  group('MeshAppTheme.cardShadow', () {
    test('cardShadow is not empty', () {
      expect(MeshAppTheme.cardShadow.isNotEmpty, isTrue);
    });

    test('cardShadow has one entry', () {
      expect(MeshAppTheme.cardShadow.length, 1);
    });
  });

  group('MeshAppTheme.subtleShadow', () {
    test('returns non-empty list', () {
      final shadow = MeshAppTheme.subtleShadow(Colors.blue);
      expect(shadow.isNotEmpty, isTrue);
    });

    test('returns one shadow entry', () {
      final shadow = MeshAppTheme.subtleShadow(Colors.red);
      expect(shadow.length, 1);
    });
  });

  group('MeshAppTheme.dark()', () {
    late ThemeData theme;

    setUp(() {
      theme = MeshAppTheme.dark();
    });

    test('returns ThemeData', () {
      expect(theme, isA<ThemeData>());
    });

    test('brightness is dark', () {
      expect(theme.brightness, Brightness.dark);
    });

    test('useMaterial3 is true', () {
      expect(theme.useMaterial3, isTrue);
    });

    test('scaffoldBackgroundColor is bgDeep', () {
      expect(theme.scaffoldBackgroundColor, MeshAppTheme.bgDeep);
    });

    test('colorScheme primary is correct', () {
      expect(theme.colorScheme.primary, MeshAppTheme.primary);
    });

    test('colorScheme secondary is info', () {
      expect(theme.colorScheme.secondary, MeshAppTheme.info);
    });

    test('colorScheme tertiary is warning', () {
      expect(theme.colorScheme.tertiary, MeshAppTheme.warning);
    });

    test('colorScheme surface is bgCard', () {
      expect(theme.colorScheme.surface, MeshAppTheme.bgCard);
    });

    test('colorScheme error is error', () {
      expect(theme.colorScheme.error, MeshAppTheme.error);
    });

    test('colorScheme onSurface is textWhite', () {
      expect(theme.colorScheme.onSurface, MeshAppTheme.textWhite);
    });

    test('appBarTheme background is transparent', () {
      expect(theme.appBarTheme.backgroundColor, Colors.transparent);
    });

    test('appBarTheme foreground is textWhite', () {
      expect(theme.appBarTheme.foregroundColor, MeshAppTheme.textWhite);
    });

    test('appBarTheme elevation is 0', () {
      expect(theme.appBarTheme.elevation, 0);
    });

    test('navigationBarTheme background is bgNav', () {
      expect(theme.navigationBarTheme.backgroundColor, MeshAppTheme.bgNav);
    });

    test('navigationBarTheme height is 66', () {
      expect(theme.navigationBarTheme.height, 66);
    });

    test('dividerTheme color is border', () {
      expect(theme.dividerTheme.color, MeshAppTheme.border);
    });

    test('textTheme headlineLarge has correct color', () {
      expect(theme.textTheme.headlineLarge?.color, MeshAppTheme.textWhite);
    });

    test('textTheme bodyMedium has correct color', () {
      expect(theme.textTheme.bodyMedium?.color, MeshAppTheme.textGray);
    });

    test('textTheme bodySmall has correct color', () {
      expect(theme.textTheme.bodySmall?.color, MeshAppTheme.textDim);
    });
  });

  group('MeshAppTheme.light()', () {
    late ThemeData theme;

    setUp(() {
      theme = MeshAppTheme.light();
    });

    test('returns ThemeData', () {
      expect(theme, isA<ThemeData>());
    });

    test('brightness is light', () {
      expect(theme.brightness, Brightness.light);
    });

    test('useMaterial3 is true', () {
      expect(theme.useMaterial3, isTrue);
    });

    test('scaffoldBackgroundColor is light gray', () {
      expect(theme.scaffoldBackgroundColor, const Color(0xFFF5F7FA));
    });

    test('colorScheme primary is correct', () {
      expect(theme.colorScheme.primary, MeshAppTheme.primary);
    });

    test('colorScheme secondary is info', () {
      expect(theme.colorScheme.secondary, MeshAppTheme.info);
    });

    test('colorScheme surface is white', () {
      expect(theme.colorScheme.surface, Colors.white);
    });

    test('colorScheme error is error', () {
      expect(theme.colorScheme.error, MeshAppTheme.error);
    });

    test('appBarTheme foreground is dark text', () {
      expect(theme.appBarTheme.foregroundColor, const Color(0xFF1A1F36));
    });

    test('navigationBarTheme background is white', () {
      expect(theme.navigationBarTheme.backgroundColor, Colors.white);
    });

    test('textTheme headlineLarge has dark text', () {
      expect(theme.textTheme.headlineLarge?.color, const Color(0xFF1A1F36));
    });
  });

  group('MeshAppTheme dark vs light', () {
    test('dark has dark brightness', () {
      expect(MeshAppTheme.dark().brightness, Brightness.dark);
    });

    test('light has light brightness', () {
      expect(MeshAppTheme.light().brightness, Brightness.light);
    });

    test('dark and light have same primary', () {
      expect(
        MeshAppTheme.dark().colorScheme.primary,
        MeshAppTheme.light().colorScheme.primary,
      );
    });

    test('dark and light have different scaffold backgrounds', () {
      expect(
        MeshAppTheme.dark().scaffoldBackgroundColor,
        isNot(MeshAppTheme.light().scaffoldBackgroundColor),
      );
    });
  });
}
