enum BuyerWalletState { active, notInitialized }

final class BuyerWalletMovementProjection {
  const BuyerWalletMovementProjection({
    required this.type,
    required this.amountDelta,
    required this.occurredAt,
  });

  final String type;
  final String amountDelta;
  final DateTime occurredAt;
}

final class BuyerWalletMovementsPageProjection {
  const BuyerWalletMovementsPageProjection({
    required this.items,
    required this.page,
    required this.size,
    required this.total,
  });

  final List<BuyerWalletMovementProjection> items;
  final int page;
  final int size;
  final int total;
}

final class BuyerWalletProjection {
  const BuyerWalletProjection({
    required this.state,
    required this.currency,
    required this.postedBalance,
    required this.reservedBalance,
    required this.availableBalance,
    required this.movements,
    this.orderPaymentSupported = false,
  });

  final BuyerWalletState state;
  final String currency;
  final String? postedBalance;
  final String? reservedBalance;
  final String? availableBalance;
  final BuyerWalletMovementsPageProjection movements;

  /// Server support only; does not imply funds, authorization, or purchase approval.
  final bool orderPaymentSupported;
}

abstract interface class BuyerWalletRepository {
  Future<BuyerWalletProjection> readCurrentWallet({required int page});
}
