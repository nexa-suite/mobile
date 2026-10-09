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

abstract interface class BuyerCreditExposureRepository {
  Future<BuyerCreditExposureProjection> readCurrentBuyerExposure({
    required String currency,
  });
}
