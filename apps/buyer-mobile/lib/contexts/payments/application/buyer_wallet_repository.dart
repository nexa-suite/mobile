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

enum BuyerWalletRechargeStatus {
  preparing,
  awaitingPayment,
  succeeded,
  failed,
  cancelled,
  rejected,
}

final class BuyerWalletRechargeProjection {
  const BuyerWalletRechargeProjection({
    required this.id,
    required this.status,
    required this.amount,
    required this.currency,
    required this.provider,
    required this.providerPaymentIntentId,
    required this.createdAt,
    this.updatedAt,
    this.completedAt,
  });

  final String id;
  final BuyerWalletRechargeStatus status;
  final String amount;
  final String currency;
  final String provider;
  final String providerPaymentIntentId;
  final DateTime createdAt;
  final DateTime? updatedAt;
  final DateTime? completedAt;

  bool get isTerminal => switch (status) {
    BuyerWalletRechargeStatus.succeeded ||
    BuyerWalletRechargeStatus.failed ||
    BuyerWalletRechargeStatus.cancelled ||
    BuyerWalletRechargeStatus.rejected => true,
    BuyerWalletRechargeStatus.preparing ||
    BuyerWalletRechargeStatus.awaitingPayment => false,
  };
}

abstract interface class BuyerWalletRepository {
  Future<BuyerWalletProjection> readCurrentWallet({required int page});

  Future<BuyerWalletRechargeProjection> createRecharge({
    required String amount,
    required String idempotencyKey,
  });

  Future<BuyerWalletRechargeProjection> readRecharge({
    required String rechargeId,
  });
}
