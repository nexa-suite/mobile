final class BuyerPaymentMethod {
  const BuyerPaymentMethod({
    required this.id,
    required this.brand,
    required this.last4,
    required this.expiryMonth,
    required this.expiryYear,
    required this.isDefault,
  });
  final String id, brand, last4;
  final int expiryMonth, expiryYear;
  final bool isDefault;
}

final class BuyerPaymentSetup {
  const BuyerPaymentSetup({
    required this.id,
    required this.secret,
    required this.status,
    required this.publishableKey,
  });
  final String id, secret, status, publishableKey;
}

abstract interface class BuyerPaymentMethodsRepository {
  Future<List<BuyerPaymentMethod>> list();
  Future<BuyerPaymentSetup> setup(String key);
  Future<void> makeDefault(String id);
  Future<void> remove(String id);
}

abstract interface class BuyerSetupKeyStore {
  Future<String?> read(String scope);
  Future<void> write(String scope, String key);
  Future<void> clear(String scope);
}

enum BuyerSetupOutcome { completed, cancelled, failed, leaseInvalidated }

abstract interface class BuyerPaymentSetupPort {
  Future<BuyerSetupOutcome> present(
    BuyerPaymentSetup setup,
    bool Function() isCurrent,
  );
}
