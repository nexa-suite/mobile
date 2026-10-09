final class BuyerPaymentProjection {
  const BuyerPaymentProjection({
    required this.id,
    required this.receivableId,
    required this.method,
    required this.status,
    required this.amount,
    required this.currency,
    required this.createdAt,
    this.reference,
    this.reviewReason,
    this.completedAt,
  });

  final String id;
  final String receivableId;
  final String method;
  final String status;
  final String amount;
  final String currency;
  final String? reference;
  final String? reviewReason;
  final DateTime createdAt;
  final DateTime? completedAt;
}

final class BuyerPaymentHistoryPageProjection {
  const BuyerPaymentHistoryPageProjection({
    required this.items,
    required this.page,
    required this.size,
    required this.total,
  });

  final List<BuyerPaymentProjection> items;
  final int page;
  final int size;
  final int total;
}

abstract interface class BuyerPaymentsRepository {
  Future<BuyerPaymentHistoryPageProjection> listForReceivable({
    required String receivableId,
    required int page,
    String? expectedClientAccountId,
  });

  Future<BuyerPaymentProjection> reportBankTransfer({
    required String receivableId,
    required String reference,
    required String idempotencyKey,
  });
}
