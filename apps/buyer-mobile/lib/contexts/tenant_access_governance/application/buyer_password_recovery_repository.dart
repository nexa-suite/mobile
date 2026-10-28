abstract interface class BuyerPasswordRecoveryRepository {
  Future<void> requestReset({required String email});

  Future<void> resetPassword({
    required String token,
    required String newPassword,
  });
}
