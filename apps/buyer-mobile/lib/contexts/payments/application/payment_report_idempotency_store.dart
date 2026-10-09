abstract interface class PaymentReportIdempotencyStore {
  /// Returns a stable key for this scoped report identity and persists it
  /// before any network dispatch. Successful responses retain the key so a
  /// reopened form cannot create a second report for the same reference.
  Future<String> keyFor({
    required String scopeKey,
    required String receivableId,
    required String referenceFingerprint,
  });
}
