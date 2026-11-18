final class BuyerTransferProofProjection {
  const BuyerTransferProofProjection({
    required this.id,
    required this.receivableId,
    required this.filename,
    required this.lifecycleStatus,
  });

  final String id;
  final String receivableId;
  final String filename;
  final String lifecycleStatus;

  bool get isAvailable => lifecycleStatus == 'AVAILABLE';
  bool get isRejected =>
      lifecycleStatus == 'REJECTED' || lifecycleStatus == 'DELETED';
}

abstract interface class BuyerTransferProofRepository {
  Future<BuyerTransferProofProjection> uploadTransferProof({
    required String receivableId,
    required String filename,
    required String contentType,
    required List<int> bytes,
    required String idempotencyKey,
  });

  Future<BuyerTransferProofProjection> transferProof({
    required String receivableId,
    required String evidenceId,
  });
}
