import 'purchase_request_idempotency_store.dart';

final class BuyerDeliveryAddressProjection {
  const BuyerDeliveryAddressProjection({
    required this.id,
    required this.label,
    required this.line,
    required this.active,
    required this.defaultAddress,
    this.latitude,
    this.longitude,
  });

  final String id;
  final String label;
  final String line;
  final bool active;
  final bool defaultAddress;
  final num? latitude;
  final num? longitude;
}

final class BuyerPurchaseContextProjection {
  const BuyerPurchaseContextProjection({
    required this.clientAccountId,
    required this.buyerMembershipId,
    required this.businessName,
    required this.paymentCondition,
    required this.addresses,
  });

  final String clientAccountId;
  final String buyerMembershipId;
  final String businessName;
  final String paymentCondition;
  final List<BuyerDeliveryAddressProjection> addresses;
}

final class PurchaseRequestLineProjection {
  const PurchaseRequestLineProjection({
    required this.skuId,
    required this.quantity,
    required this.unit,
    this.effectiveUnitPrice,
    this.currency,
  });

  final String skuId;
  final String quantity;
  final String unit;
  final String? effectiveUnitPrice;
  final String? currency;
}

final class PurchaseRequestDraftProjection {
  const PurchaseRequestDraftProjection({
    required this.id,
    required this.clientAccountId,
    required this.status,
    required this.version,
    required this.etag,
    required this.lines,
    this.requestedDeliveryDate,
    this.paymentPreference,
    this.creditResult,
    this.routeProvider,
    this.routeEstimated,
    this.destinationAddressId,
    this.hasWarehouseSelection = false,
    this.submittedAt,
  });

  final String id;
  final String clientAccountId;
  final String status;
  final int version;
  final String etag;
  final List<PurchaseRequestLineProjection> lines;
  final String? requestedDeliveryDate;
  final String? paymentPreference;
  final String? creditResult;
  final String? routeProvider;
  final bool? routeEstimated;
  final String? destinationAddressId;
  final bool hasWarehouseSelection;
  final String? submittedAt;
}

final class PurchaseRequestReviewProjection {
  const PurchaseRequestReviewProjection({
    required this.draft,
    required this.readyToSubmit,
    required this.missing,
  });

  final PurchaseRequestDraftProjection draft;
  final bool readyToSubmit;
  final List<String> missing;
}

final class PurchaseRequestDraftSummaryProjection {
  const PurchaseRequestDraftSummaryProjection({
    required this.id,
    required this.status,
    required this.version,
    required this.lineCount,
    this.requestedDeliveryDate,
    this.createdAt,
    this.updatedAt,
  });

  final String id;
  final String status;
  final int version;
  final String? requestedDeliveryDate;
  final int lineCount;
  final DateTime? createdAt;
  final DateTime? updatedAt;
}

final class PurchaseRequestDraftPageProjection {
  const PurchaseRequestDraftPageProjection({
    required this.items,
    required this.page,
    required this.size,
    required this.totalItems,
    required this.totalPages,
  });

  final List<PurchaseRequestDraftSummaryProjection> items;
  final int page;
  final int size;
  final int totalItems;
  final int totalPages;
}

abstract interface class PurchaseRequestRepository {
  Future<BuyerPurchaseContextProjection> loadBuyerPurchaseContext();

  Future<PurchaseRequestDraftPageProjection> listDrafts({required int page});

  Future<PurchaseRequestCreationRecord?> creationRecord(String scopeKey);

  Future<void> beginCreation(String scopeKey);

  Future<void> recordCreatedDraft(String scopeKey, String draftId);

  Future<void> clearCreationRecord(String scopeKey);

  Future<PurchaseRequestDraftProjection> createDraft({
    required String clientAccountId,
    required String requestedDeliveryDate,
  });

  Future<PurchaseRequestDraftProjection> getDraft(String draftId);

  Future<PurchaseRequestDraftProjection> replaceLine({
    required PurchaseRequestDraftProjection draft,
    required String sellableSkuId,
    required num quantity,
    required String unit,
  });

  Future<PurchaseRequestDraftProjection> setDestination({
    required PurchaseRequestDraftProjection draft,
    required String addressId,
  });

  Future<PurchaseRequestDraftProjection> previewRoute(
    PurchaseRequestDraftProjection draft,
  );

  Future<PurchaseRequestDraftProjection> setPreferences({
    required PurchaseRequestDraftProjection draft,
    required String paymentPreference,
    required String requestedDeliveryDate,
  });

  Future<PurchaseRequestReviewProjection> review(String draftId);

  Future<PurchaseRequestDraftProjection> submit(
    PurchaseRequestDraftProjection draft, {
    required String scopeKey,
  });
}
