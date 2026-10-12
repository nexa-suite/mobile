import 'buyer_wallet_repository.dart';

/// One-time intent response. Its client secret is memory-only and must never be
/// persisted or logged; status reads use [BuyerWalletRechargeProjection].
final class BuyerWalletRechargeIntent {
  const BuyerWalletRechargeIntent({
    required this.recharge,
    required this.clientSecret,
  });

  final BuyerWalletRechargeProjection recharge;
  final String? clientSecret;
}

/// Creates the provider intent response that may initialize an on-device checkout.
abstract interface class BuyerWalletRechargeIntentRepository {
  Future<BuyerWalletRechargeIntent> createRechargeIntent({
    required String amount,
    required String idempotencyKey,
  });
}
