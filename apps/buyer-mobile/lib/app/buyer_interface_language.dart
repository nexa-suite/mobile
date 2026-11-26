import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Language of this installation's interface, independent of the account.
final class BuyerInterfaceLanguage extends ValueNotifier<Locale> {
  BuyerInterfaceLanguage({Locale initial = english}) : super(initial);

  static const english = Locale('en', 'US');
  static const spanish = Locale('es', '419');
  static const supported = [english, spanish];
  static const _key = 'nexa_buyer_interface_language';
  static const _storage = FlutterSecureStorage();

  static Future<Locale> load() async {
    try {
      final language = await _storage.read(key: _key);
      return language == spanish.languageCode ? spanish : english;
    } on PlatformException {
      return english;
    }
  }

  Future<void> select(Locale locale) async {
    if (!supported.contains(locale)) {
      throw ArgumentError.value(locale, 'locale');
    }
    await _storage.write(key: _key, value: locale.languageCode);
    value = locale;
  }
}
