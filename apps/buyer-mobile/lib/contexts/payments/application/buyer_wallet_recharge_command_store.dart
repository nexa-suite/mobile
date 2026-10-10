final class BuyerWalletRechargeCommand {
  const BuyerWalletRechargeCommand({
    required this.amount,
    required this.idempotencyKey,
    this.rechargeId,
  });

  final String amount;
  final String idempotencyKey;
  final String? rechargeId;

  BuyerWalletRechargeCommand withRechargeId(String value) =>
      BuyerWalletRechargeCommand(
        amount: amount,
        idempotencyKey: idempotencyKey,
        rechargeId: value,
      );
}

abstract interface class BuyerWalletRechargeCommandStore {
  Future<BuyerWalletRechargeCommand?> load({required String scopeKey});

  /// Saves one exact amount and key before dispatch; an existing command wins.
  Future<BuyerWalletRechargeCommand> prepare({
    required String scopeKey,
    required String amount,
  });

  Future<bool> recordCreated({
    required String scopeKey,
    required String idempotencyKey,
    required String rechargeId,
  });

  Future<bool> clear({
    required String scopeKey,
    required String idempotencyKey,
  });
}
