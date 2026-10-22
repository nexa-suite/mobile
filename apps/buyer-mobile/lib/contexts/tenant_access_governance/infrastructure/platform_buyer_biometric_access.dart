import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:local_auth/local_auth.dart';

import '../application/buyer_access_repository.dart';

final class PlatformBuyerBiometricAccess implements BuyerBiometricAccess {
  PlatformBuyerBiometricAccess({
    FlutterSecureStorage? storage,
    LocalAuthentication? authentication,
  }) : _storage = storage ?? const FlutterSecureStorage(),
       _authentication = authentication ?? LocalAuthentication();

  static const _credentialKey = 'nexa.buyer.biometric-credential.v1';

  final FlutterSecureStorage _storage;
  final LocalAuthentication _authentication;

  @override
  Future<bool> isAvailable() async {
    try {
      if (!await _authentication.canCheckBiometrics) return false;
      return (await _authentication.getAvailableBiometrics()).isNotEmpty;
    } catch (_) {
      return false;
    }
  }

  @override
  Future<bool> authenticate({required String reason}) async {
    if (!await isAvailable()) return false;
    try {
      return await _authentication.authenticate(
        localizedReason: reason,
        biometricOnly: true,
        persistAcrossBackgrounding: true,
      );
    } catch (_) {
      return false;
    }
  }

  @override
  Future<BuyerBiometricCredential?> readCredential() async {
    final encoded = await _storage.read(key: _credentialKey);
    if (encoded == null) return null;
    try {
      final value = jsonDecode(encoded);
      if (value is! Map<String, dynamic>) return null;
      final token = value['nativeRefreshToken'];
      final scopeKey = value['scopeKey'];
      if (token is! String ||
          token.isEmpty ||
          scopeKey is! String ||
          scopeKey.isEmpty) {
        return null;
      }
      return BuyerBiometricCredential(
        nativeRefreshToken: token,
        scopeKey: scopeKey,
      );
    } on FormatException {
      await clearCredential();
      return null;
    }
  }

  @override
  Future<void> writeCredential(BuyerBiometricCredential credential) =>
      _storage.write(
        key: _credentialKey,
        value: jsonEncode({
          'nativeRefreshToken': credential.nativeRefreshToken,
          'scopeKey': credential.scopeKey,
        }),
      );

  @override
  Future<void> clearCredential() => _storage.delete(key: _credentialKey);
}
