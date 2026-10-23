import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Stores only a rotating native refresh token; never stores a password.
final class BuyerSecureBiometricSessionStore {
  BuyerSecureBiometricSessionStore({FlutterSecureStorage? storage})
    : _storage = storage ?? const FlutterSecureStorage();

  static const _enabledKey = 'buyer.biometric_unlock.enabled';
  static const _refreshTokenKey = 'buyer.biometric_unlock.refresh_token';
  static const _scopeKey = 'buyer.biometric_unlock.scope';

  final FlutterSecureStorage _storage;

  Future<bool> isEnabled() async =>
      await _storage.read(key: _enabledKey) == 'true';

  Future<String?> readRefreshToken() => _storage.read(key: _refreshTokenKey);

  Future<String?> readScope() => _storage.read(key: _scopeKey);

  Future<void> enable(String refreshToken, String scope) async {
    await _storage.write(key: _refreshTokenKey, value: refreshToken);
    await _storage.write(key: _scopeKey, value: scope);
    await _storage.write(key: _enabledKey, value: 'true');
  }

  Future<bool> updateRefreshToken(String refreshToken, String scope) async {
    if (!await isEnabled()) return false;
    if (await readScope() != scope) {
      await disable();
      return false;
    }
    await _storage.write(key: _refreshTokenKey, value: refreshToken);
    return true;
  }

  Future<void> disable() async {
    await _storage.delete(key: _enabledKey);
    await _storage.delete(key: _refreshTokenKey);
    await _storage.delete(key: _scopeKey);
  }
}
