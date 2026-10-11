final class PurchaseRequestCreationRecord {
  const PurchaseRequestCreationRecord.unknown() : draftId = null;

  const PurchaseRequestCreationRecord.known(this.draftId);

  final String? draftId;

  bool get isUnknown => draftId == null;
}

abstract interface class PurchaseRequestIdempotencyStore {
  Future<String> keyFor({
    required String scopeKey,
    required String draftId,
    required int version,
  });

  Future<void> markCompleted({
    required String scopeKey,
    required String draftId,
    required int version,
  });

  Future<PurchaseRequestCreationRecord?> creationRecord(String scopeKey);

  Future<void> beginCreation(String scopeKey);

  Future<void> recordCreatedDraft(String scopeKey, String draftId);

  Future<void> clearCreationRecord(String scopeKey);
}
