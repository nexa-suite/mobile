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
  });

  final BuyerDeliveryProjection delivery;

  /// Preserves the API's chronological order; the client does not rebuild it.
  final List<BuyerDeliveryEventProjection> events;
}

abstract interface class BuyerDeliveriesRepository {
  Future<BuyerDeliveryPageProjection> list({required int page});

  Future<BuyerDeliveryDetailProjection> detail(String deliveryId);
}
