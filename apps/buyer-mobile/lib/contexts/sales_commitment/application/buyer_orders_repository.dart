final class BuyerOrderLineProjection {
  const BuyerOrderLineProjection({
    required this.itemName,
    required this.quantity,
    required this.unit,
    this.presentation,
    this.unitPriceAmount,
    this.unitPriceCurrency,
    this.lineSubtotal,
  });

  final String itemName;
  final num quantity;
  final String unit;
  final String? presentation;
  final num? unitPriceAmount;
  final String? unitPriceCurrency;
  final num? lineSubtotal;
}

final class BuyerOrderProjection {
  const BuyerOrderProjection({
    required this.id,
    required this.number,
    required this.status,
    required this.version,
    required this.lines,
    this.createdAt,
    this.requestedDeliveryDate,
    this.total,
    this.currency,
  });

  final String id;
  final String number;
  final String status;
  final int version;
  final List<BuyerOrderLineProjection> lines;
  final String? createdAt;
  final String? requestedDeliveryDate;
  final num? total;
  final String? currency;
}

final class BuyerOrdersPageProjection {
  const BuyerOrdersPageProjection({
    required this.items,
    required this.page,
    required this.size,
    required this.total,
  });

  final List<BuyerOrderProjection> items;
  final int page;
  final int size;
  final int total;
}

abstract interface class BuyerOrdersRepository {
  Future<BuyerOrdersPageProjection> list({required int page});

  Future<BuyerOrderProjection> detail(String orderId);
}
