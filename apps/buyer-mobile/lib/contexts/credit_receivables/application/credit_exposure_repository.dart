final class BuyerCreditExposureProjection {
  const BuyerCreditExposureProjection({
    required this.clientAccountId,
    required this.currency,
    required this.creditLimit,
    required this.ledgerExposure,
    required this.outstandingReceivables,
    required this.reservedExposure,
    required this.used,
    required this.availableCredit,
    required this.active,
    required this.asOf,
  });

  final String clientAccountId;
  final String currency;
  final String creditLimit;
  final String ledgerExposure;
  final String outstandingReceivables;
  final String reservedExposure;
  final String used;
  final String availableCredit;
  final bool active;
  final DateTime? asOf;
}

final class BuyerReceivableProjection {
  const BuyerReceivableProjection({
    required this.id,
    required this.clientAccountId,
    required this.number,
    required this.currency,
    required this.amount,
    required this.amountPaid,
    required this.remaining,
    required this.status,
    required this.version,
    this.dueAt,
  });

  final String id;
  final String clientAccountId;
  final String number;
  final String currency;
  final String amount;
  final String amountPaid;
  final String remaining;
  final String status;
  final int version;
  final DateTime? dueAt;
}

final class BuyerReceivablesPageProjection {
  const BuyerReceivablesPageProjection({
    required this.items,
    required this.page,
    required this.size,
    required this.total,
  });

  final List<BuyerReceivableProjection> items;
  final int page;
  final int size;
  final int total;
}

abstract interface class BuyerCreditExposureRepository {
  Future<BuyerCreditExposureProjection> readCurrentBuyerExposure({
    required String currency,
  });

  Future<BuyerReceivablesPageProjection> listBuyerReceivables({
    required int page,
  });
}
