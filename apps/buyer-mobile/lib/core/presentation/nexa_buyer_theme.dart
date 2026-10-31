import 'package:flutter/material.dart';

import 'nexa_buyer_palette.dart';

double _lerpDouble(double a, double b, double t) => a + (b - a) * t;

const _focusRingColor = Color(0x2E2563EB);
const _dangerColor = Color(0xFFDC2626);
const _radiusSheet = 24.0;

abstract final class NexaBuyerTheme {
  static const NexaThemeExtension tokens = NexaThemeExtension(
    success: Color(0xFF15803D),
    successContainer: Color(0xFFF0FDF4),
    successBorder: Color(0xFFBBF7D0),
    warning: Color(0xFFD97706),
    warningContainer: Color(0xFFFFFBEB),
    warningBorder: Color(0xFFFCD34D),
    warningText: Color(0xFF92400E),
    danger: Color(0xFFDC2626),
    dangerContainer: Color(0xFFFEF2F2),
    dangerBorder: Color(0xFFFECACA),
    dangerText: Color(0xFF991B1B),
    focusRing: _focusRingColor,
    radiusField: 10,
    radiusControl: 10,
    radiusCard: 16,
    radiusSheet: _radiusSheet,
    radiusChip: 8,
    radiusNavigationIndicator: 8,
  );

  static const Color _primary = Color(0xFF2563EB);
  static const Color _primary50 = Color(0xFFF0F5FF);
  static const Color _primary100 = Color(0xFFDCE8FF);
  static const Color _primary200 = Color(0xFFB9D2FF);
  static const Color _primary300 = Color(0xFF8CB4FF);
  static const Color _primary800 = Color(0xFF1A439A);
  static const Color _primary900 = Color(0xFF173470);
  static const Color _page = NexaBuyerPalette.canvas;
  static const Color _card = Color(0xFFFFFFFF);
  static const Color _panel = NexaBuyerPalette.cardSurface;
  static const Color _tint = Color(0xFFF1F5F9);
  static const Color _text = Color(0xFF0F172A);
  static const Color _secondaryText = NexaBuyerPalette.muted;
  static const Color _mutedText = NexaBuyerPalette.muted;
  static const Color _border = Color(0xFFE2E8F0);
  static const Color _strongBorder = Color(0xFFCBD5E1);
  static const Color _navy = Color(0xFF082846);
  static const Color _positive = Color(0xFF15803D);

  static final ThemeData light = ThemeData(
    useMaterial3: true,
    focusColor: _focusRingColor,
    colorScheme: const ColorScheme(
      brightness: Brightness.light,
      primary: _primary,
      onPrimary: _card,
      primaryContainer: _primary100,
      onPrimaryContainer: _primary800,
      primaryFixed: _primary100,
      primaryFixedDim: _primary200,
      onPrimaryFixed: _text,
      onPrimaryFixedVariant: _primary900,
      secondary: _navy,
      onSecondary: _card,
      secondaryContainer: _primary50,
      onSecondaryContainer: _primary,
      secondaryFixed: _primary50,
      secondaryFixedDim: _primary100,
      onSecondaryFixed: _navy,
      onSecondaryFixedVariant: _navy,
      tertiary: _positive,
      onTertiary: _card,
      tertiaryContainer: Color(0xFFF0FDF4),
      onTertiaryContainer: _positive,
      tertiaryFixed: Color(0xFFBBF7D0),
      tertiaryFixedDim: Color(0xFFBBF7D0),
      onTertiaryFixed: _text,
      onTertiaryFixedVariant: _positive,
      error: _dangerColor,
      onError: _card,
      errorContainer: Color(0xFFFEF2F2),
      onErrorContainer: Color(0xFF991B1B),
      surface: _card,
      onSurface: _text,
      surfaceDim: _tint,
      surfaceBright: _card,
      surfaceContainerLowest: _card,
      surfaceContainerLow: _panel,
      surfaceContainer: _tint,
      surfaceContainerHigh: _tint,
      surfaceContainerHighest: Color(0xFFE2E8F0),
      onSurfaceVariant: _secondaryText,
      outline: _strongBorder,
      outlineVariant: _border,
      shadow: _text,
      scrim: _text,
      inverseSurface: _text,
      onInverseSurface: _card,
      inversePrimary: _primary300,
      surfaceTint: _primary,
    ),
    scaffoldBackgroundColor: _page,
    fontFamily: 'Inter',
    textTheme: const TextTheme(
      displayLarge: TextStyle(
        fontFamily: 'Plus Jakarta Sans',
        fontSize: 30,
        height: 1.25,
        letterSpacing: -0.3,
        fontWeight: FontWeight.w700,
        color: _text,
      ),
      displayMedium: TextStyle(
        fontFamily: 'Plus Jakarta Sans',
        fontSize: 24,
        height: 1.25,
        letterSpacing: -0.3,
        fontWeight: FontWeight.w700,
        color: _text,
      ),
      displaySmall: TextStyle(
        fontFamily: 'Plus Jakarta Sans',
        fontSize: 20,
        height: 1.25,
        letterSpacing: -0.3,
        fontWeight: FontWeight.w700,
        color: _text,
      ),
      headlineLarge: TextStyle(
        fontFamily: 'Plus Jakarta Sans',
        fontSize: 24,
        height: 1.25,
        letterSpacing: -0.3,
        fontWeight: FontWeight.w700,
        color: _text,
      ),
      headlineMedium: TextStyle(
        fontFamily: 'Plus Jakarta Sans',
        fontSize: 20,
        height: 1.25,
        letterSpacing: -0.3,
        fontWeight: FontWeight.w700,
        color: _text,
      ),
      headlineSmall: TextStyle(
        fontFamily: 'Plus Jakarta Sans',
        fontSize: 18,
        height: 1.25,
        letterSpacing: -0.3,
        fontWeight: FontWeight.w600,
        color: _text,
      ),
      titleLarge: TextStyle(
        fontFamily: 'Plus Jakarta Sans',
        fontSize: 18,
        height: 1.25,
        letterSpacing: -0.3,
        fontWeight: FontWeight.w700,
        color: _text,
      ),
      titleMedium: TextStyle(
        fontFamily: 'Inter',
        fontSize: 15,
        height: 1.4,
        letterSpacing: 0,
        fontWeight: FontWeight.w600,
        color: _text,
      ),
      titleSmall: TextStyle(
        fontFamily: 'Inter',
        fontSize: 12.5,
        height: 1.4,
        letterSpacing: 0,
        fontWeight: FontWeight.w600,
        color: _text,
      ),
      bodyLarge: TextStyle(
        fontFamily: 'Inter',
        fontSize: 15,
        height: 1.4,
        letterSpacing: 0,
        fontWeight: FontWeight.w400,
        color: _text,
      ),
      bodyMedium: TextStyle(
        fontFamily: 'Inter',
        fontSize: 14,
        height: 1.4,
        letterSpacing: 0,
        fontWeight: FontWeight.w400,
        color: _text,
      ),
      bodySmall: TextStyle(
        fontFamily: 'Inter',
        fontSize: 12.5,
        height: 1.4,
        letterSpacing: 0,
        fontWeight: FontWeight.w400,
        color: _secondaryText,
      ),
      labelLarge: TextStyle(
        fontFamily: 'Inter',
        fontSize: 14,
        height: 1.4,
        letterSpacing: 0,
        fontWeight: FontWeight.w600,
      ),
      labelMedium: TextStyle(
        fontFamily: 'Inter',
        fontSize: 12.5,
        height: 1.4,
        letterSpacing: 0,
        fontWeight: FontWeight.w600,
      ),
      labelSmall: TextStyle(
        fontFamily: 'Inter',
        fontSize: 11,
        height: 1.4,
        letterSpacing: 0,
        fontWeight: FontWeight.w600,
        color: _mutedText,
      ),
    ),
    extensions: const <ThemeExtension<dynamic>>[tokens],
    appBarTheme: const AppBarTheme(
      backgroundColor: _card,
      foregroundColor: _text,
      surfaceTintColor: Colors.transparent,
      titleTextStyle: TextStyle(
        fontFamily: 'Plus Jakarta Sans',
        fontSize: 20,
        fontWeight: FontWeight.w700,
        color: _text,
      ),
    ),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: _tint,
      border: OutlineInputBorder(
        borderRadius: BorderRadius.circular(tokens.radiusField),
        borderSide: BorderSide.none,
      ),
      enabledBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(tokens.radiusField),
        borderSide: BorderSide.none,
      ),
      focusedBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(tokens.radiusField),
        borderSide: const BorderSide(color: _primary, width: 2),
      ),
      errorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(tokens.radiusField),
        borderSide: const BorderSide(color: _dangerColor),
      ),
      focusedErrorBorder: OutlineInputBorder(
        borderRadius: BorderRadius.circular(tokens.radiusField),
        borderSide: const BorderSide(color: _dangerColor, width: 2),
      ),
      labelStyle: const TextStyle(color: _secondaryText),
      floatingLabelStyle: const TextStyle(color: _primary),
    ),
    filledButtonTheme: FilledButtonThemeData(
      style: FilledButton.styleFrom(
        backgroundColor: _primary,
        foregroundColor: _card,
        minimumSize: const Size(64, 48),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(tokens.radiusControl),
        ),
        textStyle: const TextStyle(
          fontFamily: 'Inter',
          fontSize: 14,
          fontWeight: FontWeight.w600,
        ),
        overlayColor: tokens.focusRing,
      ).copyWith(side: _focusSide(_card)),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(
        backgroundColor: _card,
        foregroundColor: _primary,
        minimumSize: const Size(64, 48),
        side: BorderSide.none,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(tokens.radiusControl),
        ),
        textStyle: const TextStyle(
          fontFamily: 'Inter',
          fontSize: 14,
          fontWeight: FontWeight.w600,
        ),
        overlayColor: tokens.focusRing,
      ).copyWith(side: _focusSide(_primary)),
    ),
    textButtonTheme: TextButtonThemeData(
      style: TextButton.styleFrom(
        foregroundColor: _primary,
        minimumSize: const Size(64, 48),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(tokens.radiusControl),
        ),
        textStyle: const TextStyle(
          fontFamily: 'Inter',
          fontSize: 14,
          fontWeight: FontWeight.w600,
        ),
        overlayColor: tokens.focusRing,
      ).copyWith(side: _focusSide(_primary)),
    ),
    iconButtonTheme: IconButtonThemeData(
      style: IconButton.styleFrom(minimumSize: const Size(48, 48))
          .copyWith(side: _focusSide(_primary)),
    ),
    cardTheme: CardThemeData(
      elevation: 0,
      color: _panel,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(tokens.radiusCard),
        side: BorderSide.none,
      ),
    ),
    navigationBarTheme: NavigationBarThemeData(
      backgroundColor: _card,
      indicatorColor: _primary50,
      indicatorShape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(tokens.radiusNavigationIndicator),
      ),
      labelTextStyle: const WidgetStatePropertyAll<TextStyle>(
        TextStyle(
          fontFamily: 'Inter',
          fontSize: 12.5,
          fontWeight: FontWeight.w600,
        ),
      ),
    ),
    dialogTheme: DialogThemeData(
      backgroundColor: _card,
      surfaceTintColor: Colors.transparent,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(_radiusSheet),
      ),
    ),
    bottomSheetTheme: BottomSheetThemeData(
      backgroundColor: _card,
      surfaceTintColor: Colors.transparent,
      shape: const RoundedRectangleBorder(
        borderRadius: BorderRadius.vertical(top: Radius.circular(_radiusSheet)),
      ),
    ),
    snackBarTheme: SnackBarThemeData(
      backgroundColor: _text,
      contentTextStyle: const TextStyle(
        fontFamily: 'Inter',
        fontSize: 14,
        color: _card,
      ),
      behavior: SnackBarBehavior.floating,
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(tokens.radiusChip),
      ),
    ),
    dividerTheme: const DividerThemeData(color: _border, thickness: 1),
  );

  static WidgetStateProperty<BorderSide?> _focusSide(Color color) =>
      WidgetStateProperty.resolveWith(
        (states) =>
            states.contains(WidgetState.focused) &&
                !states.contains(WidgetState.disabled)
            ? BorderSide(color: color, width: 2)
            : BorderSide.none,
      );
}

@immutable
final class NexaThemeExtension extends ThemeExtension<NexaThemeExtension> {
  const NexaThemeExtension({
    required this.success,
    required this.successContainer,
    required this.successBorder,
    required this.warning,
    required this.warningContainer,
    required this.warningBorder,
    required this.warningText,
    required this.danger,
    required this.dangerContainer,
    required this.dangerBorder,
    required this.dangerText,
    required this.focusRing,
    required this.radiusField,
    required this.radiusControl,
    required this.radiusCard,
    required this.radiusSheet,
    required this.radiusChip,
    required this.radiusNavigationIndicator,
  });

  final Color success;
  final Color successContainer;
  final Color successBorder;
  final Color warning;
  final Color warningContainer;
  final Color warningBorder;
  final Color warningText;
  final Color danger;
  final Color dangerContainer;
  final Color dangerBorder;
  final Color dangerText;
  final Color focusRing;
  final double radiusField;
  final double radiusControl;
  final double radiusCard;
  final double radiusSheet;
  final double radiusChip;
  final double radiusNavigationIndicator;

  @override
  NexaThemeExtension copyWith({
    Color? success,
    Color? successContainer,
    Color? successBorder,
    Color? warning,
    Color? warningContainer,
    Color? warningBorder,
    Color? warningText,
    Color? danger,
    Color? dangerContainer,
    Color? dangerBorder,
    Color? dangerText,
    Color? focusRing,
    double? radiusField,
    double? radiusControl,
    double? radiusCard,
    double? radiusSheet,
    double? radiusChip,
    double? radiusNavigationIndicator,
  }) => NexaThemeExtension(
    success: success ?? this.success,
    successContainer: successContainer ?? this.successContainer,
    successBorder: successBorder ?? this.successBorder,
    warning: warning ?? this.warning,
    warningContainer: warningContainer ?? this.warningContainer,
    warningBorder: warningBorder ?? this.warningBorder,
    warningText: warningText ?? this.warningText,
    danger: danger ?? this.danger,
    dangerContainer: dangerContainer ?? this.dangerContainer,
    dangerBorder: dangerBorder ?? this.dangerBorder,
    dangerText: dangerText ?? this.dangerText,
    focusRing: focusRing ?? this.focusRing,
    radiusField: radiusField ?? this.radiusField,
    radiusControl: radiusControl ?? this.radiusControl,
    radiusCard: radiusCard ?? this.radiusCard,
    radiusSheet: radiusSheet ?? this.radiusSheet,
    radiusChip: radiusChip ?? this.radiusChip,
    radiusNavigationIndicator:
        radiusNavigationIndicator ?? this.radiusNavigationIndicator,
  );

  @override
  NexaThemeExtension lerp(covariant NexaThemeExtension? other, double t) {
    if (other == null) return this;
    return NexaThemeExtension(
      success: Color.lerp(success, other.success, t)!,
      successContainer: Color.lerp(
        successContainer,
        other.successContainer,
        t,
      )!,
      successBorder: Color.lerp(successBorder, other.successBorder, t)!,
      warning: Color.lerp(warning, other.warning, t)!,
      warningContainer: Color.lerp(
        warningContainer,
        other.warningContainer,
        t,
      )!,
      warningBorder: Color.lerp(warningBorder, other.warningBorder, t)!,
      warningText: Color.lerp(warningText, other.warningText, t)!,
      danger: Color.lerp(danger, other.danger, t)!,
      dangerContainer: Color.lerp(dangerContainer, other.dangerContainer, t)!,
      dangerBorder: Color.lerp(dangerBorder, other.dangerBorder, t)!,
      dangerText: Color.lerp(dangerText, other.dangerText, t)!,
      focusRing: Color.lerp(focusRing, other.focusRing, t)!,
      radiusField: _lerpDouble(radiusField, other.radiusField, t),
      radiusControl: _lerpDouble(radiusControl, other.radiusControl, t),
      radiusCard: _lerpDouble(radiusCard, other.radiusCard, t),
      radiusSheet: _lerpDouble(radiusSheet, other.radiusSheet, t),
      radiusChip: _lerpDouble(radiusChip, other.radiusChip, t),
      radiusNavigationIndicator: _lerpDouble(
        radiusNavigationIndicator,
        other.radiusNavigationIndicator,
        t,
      ),
    );
  }
}
