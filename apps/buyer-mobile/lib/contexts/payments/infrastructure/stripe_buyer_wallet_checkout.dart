import 'package:flutter/foundation.dart';
import 'package:flutter_stripe/flutter_stripe.dart';

import '../application/buyer_wallet_checkout_port.dart';

/// PaymentSheet bridge. Its result describes the on-device flow only; Nexa's
/// signed provider event remains the sole authority for wallet credit.
final class StripeBuyerWalletCheckout implements BuyerWalletCheckoutPort {
  const StripeBuyerWalletCheckout._(this._configured);

  final bool _configured;

  static Future<StripeBuyerWalletCheckout> configure(
    String publishableKey,
  ) async {
    final key = publishableKey.trim();
    if (kIsWeb || !_looksLikePublishableKey(key)) {
      return const StripeBuyerWalletCheckout._(false);
    }
    try {
      Stripe.publishableKey = key;
      Stripe.urlScheme = 'nexa';
      await Stripe.instance.applySettings();
      return const StripeBuyerWalletCheckout._(true);
    } catch (_) {
      return const StripeBuyerWalletCheckout._(false);
    }
  }

  @override
  bool get isConfigured => _configured;

  @override
  Future<BuyerWalletCheckoutOutcome> present({
    required String clientSecret,
    required BuyerWalletCheckoutLease lease,
    required BuyerWalletCheckoutLeasePredicate isLeaseCurrent,
  }) async {
    String? secret = clientSecret;
    if (!_configured || !_matchesIntent(secret, lease)) {
      secret = null;
      return BuyerWalletCheckoutOutcome.unavailable;
    }
    if (!_leaseIsCurrent(lease, isLeaseCurrent)) {
      secret = null;
      return BuyerWalletCheckoutOutcome.leaseInvalidated;
    }
    try {
      await Stripe.instance.initPaymentSheet(
        paymentSheetParameters: SetupPaymentSheetParameters(
          merchantDisplayName: 'Nexa',
          paymentIntentClientSecret: secret,
          returnURL: 'nexa://stripe-redirect',
        ),
      );
      secret = null;
      if (!_leaseIsCurrent(lease, isLeaseCurrent)) {
        return BuyerWalletCheckoutOutcome.leaseInvalidated;
      }
      await Stripe.instance.presentPaymentSheet();
      if (!_leaseIsCurrent(lease, isLeaseCurrent)) {
        return BuyerWalletCheckoutOutcome.leaseInvalidated;
      }
      return BuyerWalletCheckoutOutcome.completed;
    } on StripeException catch (failure) {
      secret = null;
      if (!_leaseIsCurrent(lease, isLeaseCurrent)) {
        return BuyerWalletCheckoutOutcome.leaseInvalidated;
      }
      return failure.error.code == FailureCode.Canceled
          ? BuyerWalletCheckoutOutcome.cancelled
          : BuyerWalletCheckoutOutcome.failed;
    } catch (_) {
      secret = null;
      if (!_leaseIsCurrent(lease, isLeaseCurrent)) {
        return BuyerWalletCheckoutOutcome.leaseInvalidated;
      }
      return BuyerWalletCheckoutOutcome.failed;
    } finally {
      secret = null;
    }
  }

  static bool _matchesIntent(
    String? clientSecret,
    BuyerWalletCheckoutLease lease,
  ) {
    final secret = clientSecret;
    final prefix = '${lease.providerPaymentIntentId}_secret_';
    return secret != null &&
        secret.isNotEmpty &&
        secret.startsWith(prefix) &&
        secret.length > prefix.length;
  }

  static bool _leaseIsCurrent(
    BuyerWalletCheckoutLease lease,
    BuyerWalletCheckoutLeasePredicate isLeaseCurrent,
  ) {
    try {
      return isLeaseCurrent(lease);
    } catch (_) {
      return false;
    }
  }

  static bool _looksLikePublishableKey(String value) =>
      RegExp(r'^pk_(?:test|live)_[A-Za-z0-9]{24,}$').hasMatch(value) &&
      !value.toLowerCase().contains('placeholder');
}
