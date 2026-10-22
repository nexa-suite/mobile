import '../../../core/network/nexa_api_client.dart';
import '../application/buyer_password_recovery_repository.dart';

final class BuyerPasswordRecoveryRepositoryImpl
    implements BuyerPasswordRecoveryRepository {
  const BuyerPasswordRecoveryRepositoryImpl(this._api);

  final NexaApiClient _api;

  @override
  Future<void> requestReset({required String email}) async {
    await _api.post(
      '/auth/password-reset-requests',
      body: {'email': email.trim(), 'surface': 'PORTAL'},
      authenticated: false,
    );
  }

  @override
  Future<void> resetPassword({
    required String token,
    required String newPassword,
  }) async {
    await _api.post(
      '/auth/password-resets',
      body: {'token': token, 'newPassword': newPassword},
      authenticated: false,
    );
  }
}
