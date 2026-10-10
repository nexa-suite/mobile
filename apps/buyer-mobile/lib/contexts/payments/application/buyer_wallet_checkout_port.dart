enum BuyerWalletCheckoutOutcome {
  completed,
  cancelled,
  failed,
  unavailable,
  leaseInvalidated,
}

/// Exact access and provider-intent identity allowed to open PaymentSheet.
final class BuyerWalletCheckoutLease {
  const BuyerWalletCheckoutLease({
    required this.authorityEpoch,
    required this.userId,
    required this.tenantId,
    required this.workspaceId,
    required this.membershipId,
    required this.authorizationVersion,
    required this.authorityFingerprint,
    required this.rechargeId,
    required this.providerPaymentIntentId,
  });

  final int authorityEpoch;
  final String userId;
  final String tenantId;
  final String workspaceId;
  final String membershipId;
  final int? authorizationVersion;
  final String authorityFingerprint;
  final String rechargeId;
  final String providerPaymentIntentId;

  @override
  bool operator ==(Object other) =>
      other is BuyerWalletCheckoutLease &&
      other.authorityEpoch == authorityEpoch &&
      other.userId == userId &&
      other.tenantId == tenantId &&
      other.workspaceId == workspaceId &&
      other.membershipId == membershipId &&
      other.authorizationVersion == authorizationVersion &&
      other.authorityFingerprint == authorityFingerprint &&
      other.rechargeId == rechargeId &&
      other.providerPaymentIntentId == providerPaymentIntentId;

  @override
  int get hashCode => Object.hash(
    authorityEpoch,
    userId,
    tenantId,
    workspaceId,
    membershipId,
    authorizationVersion,
    authorityFingerprint,
    rechargeId,
    providerPaymentIntentId,
  );
}

typedef BuyerWalletCheckoutLeasePredicate =
    bool Function(BuyerWalletCheckoutLease lease);

/// Opens an on-device provider checkout for one server-created payment intent.
abstract interface class BuyerWalletCheckoutPort {
  bool get isConfigured;

  Future<BuyerWalletCheckoutOutcome> present({
    required String clientSecret,
    required BuyerWalletCheckoutLease lease,
    required BuyerWalletCheckoutLeasePredicate isLeaseCurrent,
  });
}
