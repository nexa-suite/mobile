final class BuyerDeliveryProjection {
  const BuyerDeliveryProjection({
    required this.id,
    required this.salesOrderNumber,
    required this.status,
    required this.version,
    required this.createdAt,
    required this.updatedAt,
    this.destination,
    this.scheduledAt,
    this.dispatchedAt,
    this.deliveredAt,
    this.proofOfDeliveryStatus,
  });

  final String id;
  final String salesOrderNumber;
  final String status;
  final int version;
  final DateTime createdAt;
  final DateTime updatedAt;
  final String? destination;
  final DateTime? scheduledAt;
  final DateTime? dispatchedAt;
  final DateTime? deliveredAt;
  final String? proofOfDeliveryStatus;
}

final class BuyerDeliveryEventProjection {
  const BuyerDeliveryEventProjection({
    required this.type,
    required this.occurredAt,
  });

  final String type;
  final DateTime occurredAt;
}

final class BuyerDeliveryPageProjection {
  const BuyerDeliveryPageProjection({
    required this.items,
    required this.page,
    required this.size,
    required this.total,
  });

  final List<BuyerDeliveryProjection> items;
  final int page;
  final int size;
  final int total;
}

final class BuyerDeliveryDetailProjection {
  const BuyerDeliveryDetailProjection({
    required this.delivery,
    required this.events,
    this.receipts = const [],
  });

  final BuyerDeliveryProjection delivery;

  /// Preserves the API's chronological order; the client does not rebuild it.
  final List<BuyerDeliveryEventProjection> events;
  final List<BuyerReceiptProjection> receipts;
}

final class BuyerDeliveryTrackingProjection {
  const BuyerDeliveryTrackingProjection({
    required this.deliveryId,
    required this.sampleId,
    required this.latitude,
    required this.longitude,
    required this.accuracyMeters,
    required this.capturedAt,
    required this.expiresAt,
  });

  final String deliveryId;
  final String sampleId;
  final double latitude;
  final double longitude;
  final double accuracyMeters;
  final DateTime capturedAt;
  final DateTime expiresAt;
}

final class BuyerHandoffValidationProjection {
  const BuyerHandoffValidationProjection({
    required this.handoffId,
    required this.deliveryId,
    required this.attemptId,
    required this.expiresAt,
    required this.deliveryStatus,
    required this.deliveredQuantity,
  });

  final String handoffId;
  final String deliveryId;
  final String attemptId;
  final DateTime expiresAt;
  final String deliveryStatus;
  final num deliveredQuantity;
}

final class BuyerReceiptProjection {
  const BuyerReceiptProjection({
    required this.id,
    required this.deliveryId,
    required this.attemptId,
    required this.decision,
    required this.driverDeliveredQuantity,
    required this.acceptedQuantity,
    required this.occurredAt,
    this.replayed = false,
    this.reason,
    this.recordedByMembershipId,
    this.evidence = const [],
  });

  final String id;
  final String deliveryId;
  final String attemptId;
  final String decision;
  final num driverDeliveredQuantity;
  final num acceptedQuantity;
  final String? reason;
  final String? recordedByMembershipId;
  final DateTime occurredAt;
  final bool replayed;
  final List<BuyerReceiptEvidenceProjection> evidence;

  BuyerReceiptProjection withEvidence(
    List<BuyerReceiptEvidenceProjection> value,
  ) => BuyerReceiptProjection(
    id: id,
    deliveryId: deliveryId,
    attemptId: attemptId,
    decision: decision,
    driverDeliveredQuantity: driverDeliveredQuantity,
    acceptedQuantity: acceptedQuantity,
    reason: reason,
    occurredAt: occurredAt,
    replayed: replayed,
    evidence: List.unmodifiable(value),
    recordedByMembershipId: recordedByMembershipId,
  );
}

final class BuyerReceiptEvidenceProjection {
  const BuyerReceiptEvidenceProjection({
    required this.id,
    required this.receiptId,
    required this.lifecycleStatus,
    required this.originalFilename,
    required this.contentType,
    required this.byteSize,
    required this.createdAt,
    this.scannedAt,
    this.failureCode,
    this.requestedByMembershipId,
  });

  final String id;
  final String receiptId;
  final String lifecycleStatus;
  final String originalFilename;
  final String contentType;
  final int byteSize;
  final DateTime createdAt;
  final DateTime? scannedAt;
  final String? failureCode;
  final String? requestedByMembershipId;
}

abstract interface class BuyerDeliveriesRepository {
  Future<BuyerDeliveryPageProjection> list({required int page});

  Future<BuyerDeliveryDetailProjection> detail(String deliveryId);

  Future<BuyerDeliveryTrackingProjection> liveLocation(String deliveryId);

  Future<BuyerHandoffValidationProjection> validateHandoff(String token);

  Future<BuyerReceiptProjection> recordReceipt({
    required String deliveryId,
    required String token,
    required String decision,
    required num acceptedQuantity,
    required String? reason,
    required String idempotencyKey,
  });

  Future<List<BuyerReceiptEvidenceProjection>> listReceiptEvidence(
    String receiptId,
  );

  Future<BuyerReceiptEvidenceProjection> uploadReceiptEvidence({
    required String receiptId,
    required String filename,
    required String contentType,
    required List<int> bytes,
    required String idempotencyKey,
  });
}
