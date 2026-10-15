abstract interface class BuyerReceiptEvidenceIdempotencyStore {
  /// Reuses a key for the same receipt and exact evidence payload fingerprint.
  Future<String> keyFor({
    required String scopeKey,
    required String receiptId,
    required String payloadFingerprint,
  });
}
