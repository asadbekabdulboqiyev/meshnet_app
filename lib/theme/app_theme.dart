import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// MeshNet — Professional dark UI.
/// No gradient — clean, matching colors.
class MeshAppTheme {
  // ── Accent colors — all solid, matching ──
  static const Color primary = Color(0xFF4E8CFF);       // Clean blue
  static const Color success = Color(0xFF3DDC84);       // Material green
  static const Color warning = Color(0xFFFFB74D);       // Warm amber
  static const Color error = Color(0xFFFF5252);         // Clear red
  static const Color info = Color(0xFF7C8AFF);          // Light indigo

  // ── Background colors — deep, professional ──
  static const Color bgDeep = Color(0xFF0A0E1A);
  static const Color bgSurface = Color(0xFF111827);
  static const Color bgCard = Color(0xFF1A2338);
  static const Color bgElevated = Color(0xFF212D45);
  static const Color bgInput = Color(0xFF0D1220);
  static const Color bgNav = Color(0xFF0E1322);

  // ── Text colors ──
  static const Color textWhite = Color(0xFFF0F4F8);
  static const Color textGray = Color(0xFF8899B0);
  static const Color textDim = Color(0xFF556680);

  // ── Border ──
  static const Color border = Color(0xFF222E44);
  static const Color borderLight = Color(0xFF2A3655);

  // ── Chat ──
  static const Color sentBubble = Color(0xFF4E8CFF);
  static const Color receivedBubble = Color(0xFF1A2338);

  // ── Shadows ──
  static List<BoxShadow> cardShadow = [
    const BoxShadow(color: Colors.black26, blurRadius: 8, offset: Offset(0, 2)),
  ];

  static List<BoxShadow> subtleShadow(Color color) {
    return [
      BoxShadow(color: color.withValues(alpha: 0.15), blurRadius: 8, offset: const Offset(0, 2)),
    ];
  }

  // ════════════════════════════════════════
  //  DARK THEME
  // ════════════════════════════════════════
  static ThemeData dark() {
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.dark,
      scaffoldBackgroundColor: bgDeep,
      colorScheme: const ColorScheme.dark(
        primary: primary,
        secondary: info,
        tertiary: warning,
        surface: bgCard,
        onPrimary: bgDeep,
        onSecondary: bgDeep,
        onSurface: textWhite,
        error: error,
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: Colors.transparent,
        foregroundColor: textWhite,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        systemOverlayStyle: SystemUiOverlayStyle(
          statusBarColor: Colors.transparent,
          statusBarIconBrightness: Brightness.light,
          statusBarBrightness: Brightness.dark,
        ),
        titleTextStyle: TextStyle(
          color: textWhite,
          fontSize: 20,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.3,
        ),
      ),
      cardTheme: CardThemeData(
        color: bgCard,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(14)),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: bgNav,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        height: 66,
        indicatorColor: primary.withValues(alpha: 0.1),
        labelTextStyle: WidgetStateProperty.resolveWith((states) {
          final selected = states.contains(WidgetState.selected);
          return TextStyle(
            fontSize: 11,
            fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
            color: selected ? primary : textDim,
          );
        }),
      ),
      dividerTheme: const DividerThemeData(
        color: border,
        thickness: 1,
        space: 1,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: bgInput,
        hintStyle: const TextStyle(color: textDim, fontSize: 15),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: border),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: border),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: primary, width: 1.5),
        ),
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      ),
      textTheme: const TextTheme(
        headlineLarge: TextStyle(color: textWhite, fontWeight: FontWeight.w700, fontSize: 26, letterSpacing: -0.5),
        headlineMedium: TextStyle(color: textWhite, fontWeight: FontWeight.w700, fontSize: 22, letterSpacing: -0.3),
        titleLarge: TextStyle(color: textWhite, fontWeight: FontWeight.w700, fontSize: 18),
        titleMedium: TextStyle(color: textWhite, fontWeight: FontWeight.w600, fontSize: 16),
        bodyLarge: TextStyle(color: textWhite, fontSize: 16, height: 1.5),
        bodyMedium: TextStyle(color: textGray, fontSize: 15, height: 1.5),
        bodySmall: TextStyle(color: textDim, fontSize: 13),
        labelSmall: TextStyle(color: textDim, fontSize: 11, fontWeight: FontWeight.w600, letterSpacing: 0.5),
      ),
      iconTheme: const IconThemeData(color: textGray, size: 22),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: bgElevated,
        contentTextStyle: const TextStyle(color: textWhite, fontSize: 14),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(10),
          side: const BorderSide(color: border, width: 1),
        ),
      ),
    );
  }

  // ════════════════════════════════════════
  //  LIGHT THEME
  // ════════════════════════════════════════
  static ThemeData light() {
    return ThemeData(
      useMaterial3: true,
      brightness: Brightness.light,
      scaffoldBackgroundColor: const Color(0xFFF5F7FA),
      colorScheme: ColorScheme.light(
        primary: primary,
        secondary: info,
        tertiary: warning,
        surface: Colors.white,
        onPrimary: Colors.white,
        onSecondary: Colors.white,
        onSurface: const Color(0xFF1A1F36),
        error: error,
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: Colors.transparent,
        foregroundColor: Color(0xFF1A1F36),
        elevation: 0,
        scrolledUnderElevation: 0.5,
        centerTitle: false,
        systemOverlayStyle: SystemUiOverlayStyle(
          statusBarColor: Colors.transparent,
          statusBarIconBrightness: Brightness.dark,
          statusBarBrightness: Brightness.light,
        ),
        titleTextStyle: TextStyle(
          color: Color(0xFF1A1F36),
          fontSize: 20,
          fontWeight: FontWeight.w700,
          letterSpacing: -0.3,
        ),
      ),
      cardTheme: CardThemeData(
        color: Colors.white,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(14),
          side: const BorderSide(color: Color(0xFFE8ECF0), width: 1),
        ),
      ),
      navigationBarTheme: NavigationBarThemeData(
        backgroundColor: Colors.white,
        surfaceTintColor: Colors.transparent,
        elevation: 0,
        height: 66,
        indicatorColor: primary.withValues(alpha: 0.08),
        labelTextStyle: WidgetStateProperty.resolveWith((states) {
          final selected = states.contains(WidgetState.selected);
          return TextStyle(
            fontSize: 11,
            fontWeight: selected ? FontWeight.w700 : FontWeight.w500,
            color: selected ? primary : const Color(0xFF8899B0),
          );
        }),
      ),
      dividerTheme: const DividerThemeData(
        color: Color(0xFFE8ECF0),
        thickness: 1,
        space: 1,
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: const Color(0xFFF0F3F8),
        hintStyle: const TextStyle(color: Color(0xFF8899B0), fontSize: 15),
        border: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: Color(0xFFE8ECF0)),
        ),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: Color(0xFFE8ECF0)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: primary, width: 1.5),
        ),
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 12),
      ),
      textTheme: const TextTheme(
        headlineLarge: TextStyle(color: Color(0xFF1A1F36), fontWeight: FontWeight.w700, fontSize: 26, letterSpacing: -0.5),
        headlineMedium: TextStyle(color: Color(0xFF1A1F36), fontWeight: FontWeight.w700, fontSize: 22, letterSpacing: -0.3),
        titleLarge: TextStyle(color: Color(0xFF1A1F36), fontWeight: FontWeight.w700, fontSize: 18),
        titleMedium: TextStyle(color: Color(0xFF1A1F36), fontWeight: FontWeight.w600, fontSize: 16),
        bodyLarge: TextStyle(color: Color(0xFF1A1F36), fontSize: 16, height: 1.5),
        bodyMedium: TextStyle(color: Color(0xFF3D4F6F), fontSize: 15, height: 1.5),
        bodySmall: TextStyle(color: Color(0xFF8899B0), fontSize: 13),
        labelSmall: TextStyle(color: Color(0xFF8899B0), fontSize: 11, fontWeight: FontWeight.w600, letterSpacing: 0.5),
      ),
      iconTheme: const IconThemeData(color: Color(0xFF8899B0), size: 22),
      snackBarTheme: SnackBarThemeData(
        backgroundColor: const Color(0xFF1A1F36),
        contentTextStyle: const TextStyle(color: Colors.white, fontSize: 14),
        behavior: SnackBarBehavior.floating,
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
      ),
    );
  }
}
