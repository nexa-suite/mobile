/// Safe, non-authoritative fields retained for an offline delivery list.
///
/// This projection deliberately excludes destination, delivery location,
/// contact data, event history, and receipt or payment evidence.
final class BuyerDeliverySummaryProjection {
  const BuyerDeliverySummaryProjection({
    required this.deliveryId,
    required this.orderNumber,
    required this.status,
    required this.updatedAt,
  });

  final String deliveryId;
  final String orderNumber;
  final String status;
  final DateTime updatedAt;
}

/// Partition identity for a local projection.
///
/// [authorityFingerprint] includes the server-provided user, membership,
/// Tenant, Workspace, authorization version, roles, and permissions. The
/// explicit identifiers remain part of the key material so the cache cannot
/// be shared across a different identity or scope.
final class BuyerDeliverySummaryCacheScope {
  const BuyerDeliverySummaryCacheScope({
    required this.userId,
    required this.tenantId,
    required this.workspaceId,
    required this.membershipId,
    required this.authorityFingerprint,
  });

  final String userId;
  final String tenantId;
  final String workspaceId;
  final String membershipId;
  final String authorityFingerprint;
}

final class BuyerDeliverySummaryCacheSnapshot {
  BuyerDeliverySummaryCacheSnapshot({
    required List<BuyerDeliverySummaryProjection> items,
    required this.asOf,
  }) : items = List.unmodifiable(items);

  final List<BuyerDeliverySummaryProjection> items;

  /// Local time when the server projection was fetched; it is advisory only.
  final DateTime asOf;
}

abstract interface class BuyerDeliverySummaryCache {
  Future<BuyerDeliverySummaryCacheSnapshot?> read({
    required BuyerDeliverySummaryCacheScope scope,
  });

  Future<void> write({
    required BuyerDeliverySummaryCacheScope scope,
    required BuyerDeliverySummaryCacheSnapshot snapshot,
  });

  Future<void> delete({required BuyerDeliverySummaryCacheScope scope});
}
