import 'package:flutter/foundation.dart';
import 'package:flutter_stripe/flutter_stripe.dart';

import '../application/buyer_payment_methods.dart';

final class StripeBuyerPaymentSetup implements BuyerPaymentSetupPort {
  const StripeBuyerPaymentSetup();
  @override
  Future<BuyerSetupOutcome> present(
    BuyerPaymentSetup setup,
    bool Function() isCurrent,
  ) async {
    // Match the configured key used by the wallet checkout and never change
    // Stripe's global account in the middle of another flow.
    const configuredKey = String.fromEnvironment('NEXA_STRIPE_PUBLISHABLE_KEY');
    if (kIsWeb ||
        configuredKey.isEmpty ||
        setup.publishableKey != configuredKey) {
      return BuyerSetupOutcome.failed;
    }
    if (!isCurrent()) return BuyerSetupOutcome.leaseInvalidated;
    try {
      await Stripe.instance.initPaymentSheet(
        paymentSheetParameters: SetupPaymentSheetParameters(
          merchantDisplayName: 'Nexa',
          setupIntentClientSecret: setup.secret,
          returnURL: 'nexa://stripe-redirect',
        ),
      );
      if (!isCurrent()) return BuyerSetupOutcome.leaseInvalidated;
      await Stripe.instance.presentPaymentSheet();
      return isCurrent()
          ? BuyerSetupOutcome.completed
          : BuyerSetupOutcome.leaseInvalidated;
    } on StripeException catch (error) {
      if (!isCurrent()) return BuyerSetupOutcome.leaseInvalidated;
      return error.error.code == FailureCode.Canceled
          ? BuyerSetupOutcome.cancelled
          : BuyerSetupOutcome.failed;
    } catch (_) {
      return isCurrent()
          ? BuyerSetupOutcome.failed
          : BuyerSetupOutcome.leaseInvalidated;
    }
  }
}
