final class BuyerReceiptDraft {
  const BuyerReceiptDraft({
    required this.deliveryId,
    required this.attemptId,
    required this.decision,
    required this.acceptedQuantity,
    required this.reason,
  });

  final String deliveryId;
  final String attemptId;
  final String decision;
  final String acceptedQuantity;
  final String reason;
}

abstract interface class BuyerReceiptDraftStore {
  Future<BuyerReceiptDraft?> read({
    required String scopeKey,
    required String deliveryId,
  });

  Future<void> write({
    required String scopeKey,
    required BuyerReceiptDraft draft,
  });

  Future<void> delete({required String scopeKey, required String deliveryId});
}
