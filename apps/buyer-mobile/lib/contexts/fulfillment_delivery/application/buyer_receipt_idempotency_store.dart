final class BuyerReceiptCommandConflict implements Exception {
  const BuyerReceiptCommandConflict();
}

abstract interface class BuyerReceiptIdempotencyStore {
  /// Reuses one key for an exact delivery-attempt payload.
  /// A different payload for that attempt is rejected after dispatch.
  Future<String> keyFor({
    required String scopeKey,
    required String deliveryId,
    required String attemptId,
    required String payloadFingerprint,
  });

  /// Removes only a matching key after the API definitively rejects payload
  /// validation before creating a receipt fact.
  Future<void> release({
    required String scopeKey,
    required String deliveryId,
    required String attemptId,
    required String payloadFingerprint,
  });
}
