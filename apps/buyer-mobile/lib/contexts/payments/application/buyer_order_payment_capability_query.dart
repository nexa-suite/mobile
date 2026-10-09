/// Reports server support only; it does not expose balance or authorize a purchase.
abstract interface class BuyerOrderPaymentCapabilityQuery {
  Future<bool> isOrderPaymentSupported();
}
