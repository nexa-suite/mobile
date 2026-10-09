final class BuyerDeliveryProjection {
  const BuyerDeliveryProjection({
    required this.id,
    required this.dispatchNumber,
    required this.status,
    required this.updatedAt,
    required this.alerts,
    this.salesOrderNumber,
    this.destination,
    this.deliveryWindowStart,
    this.deliveryWindowEnd,
    this.eta,
    this.podStatus,
    this.continuationDeliveryStatus,
  });

  final String id;
  final String dispatchNumber;
  final String? salesOrderNumber;
  final String status;
  final String? destination;
  final DateTime? deliveryWindowStart;
  final DateTime? deliveryWindowEnd;
  final DateTime? eta;
  final String? podStatus;
  final DateTime updatedAt;
  final List<String> alerts;
  final String? continuationDeliveryStatus;
}

final class BuyerDeliveryEventProjection {
  const BuyerDeliveryEventProjection({
    required this.id,
    required this.type,
    required this.occurredAt,
    required this.summary,
  });

  final String id;
  final String type;
  final DateTime occurredAt;
  final String summary;
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

  Future<BuyerDeliveryDetailProjection> detail(String dispatchId);
}
